package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.Dwell
import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.TapToken
import com.kinetica.keyboard.engine.models.WordCandidate

/**
 * Turns a merged token sequence into scored word candidates.
 *
 * Pipeline per decode: enumerate alternative merge orders, build a MatchPattern
 * per order, run the Anchored Segmental Trie Search (DFS discovering every
 * (word, segmentation) decomposition, geometric prunes shielding DTW), then
 * DTW-score survivors into a top-K heap. Anchors from taps make patterns
 * brutally selective; pure-swipe patterns rely on the endpoint/inner prunes.
 *
 * Thread-confined to the decode thread; [geometry] is swapped from the UI
 * thread and read once per decode.
 */
class WordPredictor(
    val trie: Trie,
    private val bigrams: BigramTable,
    @Volatile var geometry: KeyboardGeometry?,
    private val forms: Map<Int, List<WordForm>> = emptyMap(),
    /** Live per-user commit counts (concurrent map: main thread writes,
     *  decode thread reads); see KineticaConstants.PERSONAL_BOOST. */
    private val personalCounts: Map<String, Int> = emptyMap(),
    /**
     * Language code stamped onto every candidate this predictor emits. Empty
     * for the single-language fixtures, which is why it is defaulted and last:
     * every existing construction site stays source-compatible.
     */
    val language: String = "",
) {
    private val dtw = DtwMatcher()
    private val idealScratch = FloatArray(2 * KineticaConstants.RESAMPLE_N)

    /**
     * Trace suffix identifying which dictionary a decode line belongs to.
     * Empty (so single-language traces are unchanged) unless a language was
     * given. With two predictors running per word the in/out pairs are
     * otherwise indistinguishable except by position, which is exactly what
     * made earlier captures ambiguous to read back.
     */
    private val langTag: String = if (language.isEmpty()) "" else "[$language]"

    /** Commit count for [word], 0 when never committed. */
    fun personalCount(word: String): Int = personalCounts[word.lowercase()] ?: 0

    /**
     * The boost [word]'s commit count earns at a perfect fit. What a candidate
     * actually receives is this passed through
     * [KineticaConstants.appliedBoost], which fades it out as the
     * candidate's own geometry stops carrying information. Only the DTW abandon
     * budget uses the raw value, and only because that bound must stay
     * optimistic.
     */
    private fun personalBoost(word: String): Float {
        val count = personalCounts[word] ?: return 1f
        return 1f + KineticaConstants.PERSONAL_BOOST * kotlin.math.ln(1f + count)
    }

    /**
     * A string is a word when its folded form reaches a word node AND it is
     * one of that node's spellings: "perche" folds onto the "perché" node but
     * is not itself a word, which is exactly what lets autocorrect restore
     * the accent.
     */
    fun isWord(word: String): Boolean {
        val w = word.lowercase()
        val node = trie.nodeFor(AccentFolder.fold(w))
        if (node == -1) return false
        val variants = forms[node] ?: return true
        return variants.any { it.display == w }
    }

    /** [context] = last committed words, oldest first (window of 2). */
    fun decode(tokens: List<InputToken>, context: List<String>): List<WordCandidate> {
        val g = geometry ?: return emptyList()
        if (tokens.isEmpty() || tokens.size > KineticaConstants.MAX_WORD_LEN) return emptyList()
        DecodeTrace.log { "decode in$langTag: " + tokens.sortedBy { it.tStart }.joinToString(" ") { traceToken(it) } + " ctx=$context" }
        val prevWordId = context.lastOrNull()
            ?.let { trie.nodeFor(AccentFolder.fold(it.lowercase())) } ?: -1

        val heap = CandidateHeap(KineticaConstants.TOP_K)
        val patterns = ArrayList<List<Matcher>>(4)
        for (seq in MergeAlternatives.sequences(tokens, dtw)) {
            patterns.add(Matcher.buildPattern(seq, g) ?: continue)
        }
        for (p in patterns) {
            Search(p, g, prevWordId, heap, fuzzyAnchors = false).run()
        }

        // Fallback passes for sparse results: relaxed anchors (adjacent-key
        // typos, slightly missed taps in merged input), then transpositions
        // for all-tap sequences ("hte" -> "the").
        if (heap.count < KineticaConstants.TOP_K) {
            for (p in patterns) {
                if (p.any { it is Matcher.Anchor }) {
                    Search(p, g, prevWordId, heap, fuzzyAnchors = true).run()
                }
            }
            val primary = patterns.firstOrNull()
            if (primary != null && tokens.all { it is TapToken } && primary.size >= 2) {
                for (tp in transposedPatterns(primary)) {
                    Search(
                        tp, g, prevWordId, heap, fuzzyAnchors = false,
                        basePenalty = KineticaConstants.TRANSPOSE_PENALTY,
                    ).run()
                }
            }
        }
        val out = heap.sortedByScoreDesc()
        // Full score components per candidate: rank upsets are usually decided
        // by fw/boost arithmetic, not geometry, and d alone cannot show that.
        // Every factor of score = fw * geometricTerm(d) * bm * pb is printed, so
        // a captured row closes arithmetically with no inversion, which every
        // earlier tuning pass had to do by hand. `bm` and `pb` are both
        // the APPLIED values, i.e. after their fit conditions: `pb` reads 1.0 on
        // a word with no commits AND on a reinforced word whose fit landed past
        // GEO_SATURATION_KW, and `bm` reads its attenuated value there rather
        // than the table's. Neither is recoverable from the score alone, which is
        // why they are fields (both go through appliedBoost). A `bm` or `pb`
        // strictly between 1.0 and its raw value is how a capture shows the
        // fade firing; exactly 1.0 past one key hop is the far end of it.
        DecodeTrace.log {
            "decode out$langTag: " + if (out.isEmpty()) "<empty>" else
                out.take(5).joinToString(" ") {
                    "${it.word}(d=${(it.dtwDistance * 100).toInt() / 100f}," +
                        "s=${(it.score * 1000).toInt() / 1000f}," +
                        "fw=${(it.frequencyWeight * 100).toInt() / 100f}," +
                        "bm=${(it.bigramMultiplier * 100).toInt() / 100f}," +
                        "pb=${(it.personalBoost * 100).toInt() / 100f},${it.source})"
                }
        }
        return out
    }

    /** Compact token label for DecodeTrace: type, stream, letter/interval. */
    private fun traceToken(t: InputToken): String = when (t) {
        is TapToken -> "tap[${Alphabet.charOf(t.code)},${t.streamId},t=${t.tStart}]"
        is SwipeToken -> "swipe[${t.streamId},t=${t.tStart}..${t.tEnd}" +
            (if (t.softStart) ",softStart" else "") + (if (t.softEnd) ",softEnd" else "") +
            // Contact letters make a captured gesture RECONSTRUCTIBLE. An
            // earlier capture recorded intervals only, so the failing
            // "siempre" buffer could not be rebuilt as a golden fixture - its
            // swipe paths were unknowable. keyContacts already ride on the token
            // and had no reader anywhere; printing them closes that gap.
            (if (t.keyContacts.isEmpty()) "" else {
                t.keyContacts.joinToString("", ",keys=") { Alphabet.charOf(it.code).toString() }
            }) +
            // Dwell span, peak displacement and sample count. Displacement is
            // here because DWELL_RADIUS_KW could NOT be derived from an
            // earlier capture: it printed times only, so nothing in it
            // constrained the radius.
            (if (t.dwells.isEmpty()) "" else {
                t.dwells.joinToString(";", ",dwell=") { d ->
                    "${d.tEnter}-${d.tExit}/${dwellSpanKw(t, d)}kw/${d.exitIdx - d.enterIdx + 1}n"
                }
            }) + "]"
    }

    /** Peak displacement (kw, 2dp) of [d]'s samples from where the run began. */
    private fun dwellSpanKw(t: SwipeToken, d: Dwell): Float {
        val path = t.rawPath
        if (d.enterIdx !in path.indices || d.exitIdx !in path.indices) return -1f
        val a = path[d.enterIdx]
        var peak = 0f
        for (i in d.enterIdx..d.exitIdx) {
            val dx = path[i].x - a.x
            val dy = path[i].y - a.y
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            if (dist > peak) peak = dist
        }
        return (peak * 100).toInt() / 100f
    }

    /**
     * Autocorrect decision at a word delimiter: replace only when the literal
     * tap string is not a word and the best candidate's *geometric* confidence
     * clears the threshold. Frequency and context chose which alternative;
     * confidence decides whether to act at all.
     */
    fun autocorrectTarget(
        literal: String,
        candidates: List<WordCandidate>,
        confidenceThreshold: Float,
    ): WordCandidate? {
        if (literal.isEmpty() || isWord(literal)) return null
        val best = candidates.firstOrNull() ?: return null
        // Completions are pick-only by product decision: the bar may offer
        // "the" for t,h, but a delimiter must always keep the typed letters -
        // the user typed exactly what they typed.
        if (best.source == WordCandidate.Source.COMPLETION) return null
        if (best.word == literal) return null
        val confidence = 1f / (1f + best.dtwDistance)
        return if (confidence > confidenceThreshold) best else null
    }

    private fun transposedPatterns(pattern: List<Matcher>): List<List<Matcher>> {
        val out = ArrayList<List<Matcher>>()
        for (i in 0 until pattern.size - 1) {
            val a = pattern[i] as? Matcher.Anchor ?: continue
            val b = pattern[i + 1] as? Matcher.Anchor ?: continue
            if (a.code == b.code) continue
            val v = ArrayList(pattern)
            v[i] = b
            v[i + 1] = a
            out.add(v)
        }
        return out
    }

    /**
     * DFS over the trie discovering all pattern-consistent words. DTW is
     * deferred to complete words so the cheap prunes shield the expensive
     * metric; a running top-K minimum feeds DTW early-abandon budgets.
     */
    private inner class Search(
        private val pattern: List<Matcher>,
        private val g: KeyboardGeometry,
        private val prevWordId: Int,
        private val heap: CandidateHeap,
        private val fuzzyAnchors: Boolean,
        private val basePenalty: Float = 0f,
    ) {
        private val letters = IntArray(KineticaConstants.MAX_WORD_LEN)
        private val segStart = IntArray(pattern.size)
        private val segEnd = IntArray(pattern.size)
        private val childOrder = Array(KineticaConstants.MAX_WORD_LEN + 1) { IntArray(Alphabet.SIZE + 1) }
        private val numSegs = pattern.count { it is Matcher.Segment }
        private val totalSteps = numSegs * KineticaConstants.RESAMPLE_N
        private var emitted = 0

        // Two budgets, deliberately separate:
        //   emitted  - words that reached the heap, bounded by MAX_CANDIDATES
        //              and sliced per start subtree below;
        //   attempts - emit() calls, bounded by MAX_EMIT_ATTEMPTS, i.e. the DTW
        //              work the search may spend looking for those candidates.
        // Charging both to one counter made a nominal "candidate" budget bound
        // walked words instead, and since the abandon prunes reject ~94% of
        // them, a long zigzag path could exhaust its whole slice on words that
        // never became candidates and lose its own word ("parlare").
        private var attempts = 0

        // Per-branch emit ceiling. MAX_CANDIDATES by default; at the pattern
        // root, descend tightens it so each admissible start-letter subtree
        // gets an even slice of the budget. Without the slice, frequency-first
        // child order lets one giant neighbor subtree (es: d-, holding "de")
        // exhaust the whole budget before the intended word's subtree is even
        // visited - "siempre" was unreachable on its own exact path
        // (StartSubtreeFairnessTest, found on a live trace).
        private var emitCap = KineticaConstants.MAX_CANDIDATES

        /** Branch budget: this subtree's candidate slice, or the global work ceiling. */
        private fun branchExhausted(): Boolean =
            emitted >= emitCap || attempts >= KineticaConstants.MAX_EMIT_ATTEMPTS

        /** Whole-pass budget, for the sites that ignore the per-subtree slice. */
        private fun passExhausted(): Boolean =
            emitted >= KineticaConstants.MAX_CANDIDATES ||
                attempts >= KineticaConstants.MAX_EMIT_ATTEMPTS

        // Live completions extend only the exact all-anchor pass: a fuzzy or
        // transposed prefix is already a guess, and completing a guess would
        // dress typos up as confident-looking words.
        private val completesPrefix = numSegs == 0 && !fuzzyAnchors && basePenalty == 0f &&
            pattern.size >= KineticaConstants.COMPLETION_MIN_PREFIX

        // Emit accounting, for diagnosing decode reachability. It is what
        // separated the two budgets above from each other,
        // and it stays because the second property it exposes is NOT fixed:
        // the fairness slice exists only at a segment's FIRST letter, so the
        // leading second letters can still consume a start subtree's whole
        // candidate slice before a later one is visited even once.
        // Measured on the clean "parlare" path against the full it dictionary,
        // pre-fix: attempts=533, cands=34, dtw-abandoned=499 (93.6% waste), the
        // p- slice exhausted at exactly MAX_CANDIDATES/2, firstStop="pregate",
        // 267 units of the GLOBAL budget never spent; post-fix attempts=853 with
        // stops=0. (The trace field was named "emits" before the split made
        // "attempts" the accurate name for the same number.)
        // Allocated and counted only while a DecodeTrace sink is attached; the
        // flag is read once, so a disabled trace costs one field test per emit.
        private val traced = DecodeTrace.enabled
        private var abandonedScore = 0
        private var abandonedIdeal = 0
        private var abandonedDtw = 0
        private var budgetStops = 0
        private var firstStopPrefix: String? = null
        private val attemptsByFirst = if (traced) IntArray(Alphabet.SIZE) else null
        private val visitsByFirst = if (traced) IntArray(Alphabet.SIZE) else null

        fun run() {
            dfs(trie.root, 0, 0, basePenalty)
            if (traced) DecodeTrace.log { searchSummary() }
        }

        private fun prefixOf(depth: Int): String {
            val sb = StringBuilder(depth)
            for (i in 0 until depth) sb.append(Alphabet.charOf(letters[i]))
            return sb.toString()
        }

        /** Records where the emit budget first cut exploration off. */
        private fun noteBudgetStop(depth: Int) {
            budgetStops++
            if (firstStopPrefix == null) firstStopPrefix = prefixOf(depth)
        }

        /** One line per Search pass; read via DecodeTrace on device or in tests. */
        private fun searchSummary(): String {
            fun top(counts: IntArray?): String = counts
                ?.withIndex()
                ?.filter { it.value > 0 }
                ?.sortedByDescending { it.value }
                ?.take(6)
                ?.joinToString(",") { "${Alphabet.charOf(it.index)}:${it.value}" }
                ?: ""
            return "search[segs=$numSegs,anchors=${pattern.size - numSegs}," +
                (if (fuzzyAnchors) "fuzzy" else "exact") + "] " +
                "attempts=$attempts cands=$emitted " +
                "abandon(score=$abandonedScore,ideal=$abandonedIdeal,dtw=$abandonedDtw) " +
                "cap=$emitCap stops=$budgetStops firstStop=${firstStopPrefix ?: "-"} " +
                "attemptsByFirst=${top(attemptsByFirst)} nodesByFirst=${top(visitsByFirst)}"
        }

        private fun dfs(node: Int, ti: Int, depth: Int, tapPen: Float) {
            if (branchExhausted()) {
                if (traced) noteBudgetStop(depth)
                return
            }
            if (ti == pattern.size) {
                if (depth > 0 && trie.isWord(node)) emit(node, depth, tapPen)
                if (completesPrefix) completeFrom(node, depth, extra = 0, tapPen = tapPen)
                return
            }
            when (pattern[ti]) {
                is Matcher.Anchor -> anchorStep(node, ti, depth, tapPen, allowApostrophe = true)
                is Matcher.Segment -> descend(
                    node, ti, depth,
                    segStartDepth = depth, lettersInSeg = 0, idealLen = 0f,
                    lastIdx = -KineticaConstants.MONOTONE_SLACK, prevLetter = -1, tapPen = tapPen,
                )
            }
        }

        private fun anchorStep(node: Int, ti: Int, depth: Int, tapPen: Float, allowApostrophe: Boolean) {
            if (depth >= KineticaConstants.MAX_WORD_LEN) return
            val m = pattern[ti] as Matcher.Anchor
            if (!fuzzyAnchors) {
                tryAnchor(node, m.code, 0f, ti, depth, tapPen)
            } else {
                for (code in 0 until Alphabet.LETTERS) {
                    if (!g.hasKey(code)) continue
                    val d = g.distToCenter(m.x, m.y, code)
                    if (code != m.code && d > KineticaConstants.FUZZY_TAP_RADIUS_KW) continue
                    val pen = if (code == m.code) 0f else KineticaConstants.FUZZY_TAP_LAMBDA * d
                    tryAnchor(node, code, pen, ti, depth, tapPen)
                }
            }
            if (allowApostrophe) {
                // Consume a dictionary apostrophe without consuming input, so a
                // tapped t after swiping d-o-n still reaches "don't".
                val apo = trie.child(node, Alphabet.APOSTROPHE)
                if (apo != -1 && depth + 1 < KineticaConstants.MAX_WORD_LEN) {
                    letters[depth] = Alphabet.APOSTROPHE
                    anchorStep(apo, ti, depth + 1, tapPen, allowApostrophe = false)
                }
            }
        }

        private fun tryAnchor(node: Int, code: Int, penalty: Float, ti: Int, depth: Int, tapPen: Float) {
            val next = trie.child(node, code)
            if (next == -1) return
            letters[depth] = code
            dfs(next, ti + 1, depth + 1, tapPen + penalty)
        }

        private fun descend(
            node: Int,
            ti: Int,
            depth: Int,
            segStartDepth: Int,
            lettersInSeg: Int,
            idealLen: Float,
            lastIdx: Int,
            prevLetter: Int,
            tapPen: Float,
        ) {
            if (branchExhausted()) {
                if (traced) noteBudgetStop(depth)
                return
            }
            if (depth >= KineticaConstants.MAX_WORD_LEN) return
            if (traced && depth > 0) visitsByFirst!![letters[0]]++
            val m = pattern[ti] as Matcher.Segment
            val count = orderChildrenByFreq(node, depth)
            if (count == 0) return
            val order = childOrder[depth]

            // Root fairness: the key nearest the path's start point is the
            // strongest geometric signal, so its subtree is explored FIRST
            // with half the emit budget (deep words behind a flood of
            // higher-frequency siblings - "cuñado" behind con-/co- - need
            // room); the neighboring admissible starts then share the
            // remainder, each capped at an even rollover slice so no single
            // giant subtree (es: d-, holding "de") can exhaust the budget
            // before a later start letter is visited ("siempre" was
            // unreachable on its own exact path pre-fix).
            val rootFairness = ti == 0 && depth == 0 && lettersInSeg == 0
            var remainingStarts = 0
            if (rootFairness) {
                for (i in 0 until count) {
                    val code = trie.letter(order[i])
                    if (code == Alphabet.APOSTROPHE || !g.hasKey(code)) continue
                    val ok = if (m.softStart) {
                        m.passAtOrAfter(code, lastIdx - KineticaConstants.MONOTONE_SLACK) != -1
                    } else {
                        m.isStart(code)
                    }
                    if (ok) remainingStarts++
                }
                // Move the nearest-start child to the front of the frequency
                // order; the fallback keeps plain frequency order when the
                // nearest key has no subtree in this dictionary.
                var best = -1
                var bestD = Float.MAX_VALUE
                for (i in 0 until count) {
                    val code = trie.letter(order[i])
                    if (code == Alphabet.APOSTROPHE || !g.hasKey(code)) continue
                    val d = g.distToCenter(m.resampled[0], m.resampled[1], code)
                    if (d < bestD) {
                        bestD = d
                        best = i
                    }
                }
                if (best > 0) {
                    val nearest = order[best]
                    for (j in best downTo 1) order[j] = order[j - 1]
                    order[0] = nearest
                }
            }
            var firstStart = rootFairness

            for (i in 0 until count) {
                if (passExhausted()) {
                    if (traced) noteBudgetStop(depth)
                    return
                }
                if (rootFairness && remainingStarts > 0) {
                    val share = if (firstStart) {
                        KineticaConstants.MAX_CANDIDATES / 2
                    } else {
                        maxOf(1, (KineticaConstants.MAX_CANDIDATES - emitted) / remainingStarts)
                    }
                    emitCap = minOf(KineticaConstants.MAX_CANDIDATES, emitted + share)
                }
                val child = order[i]
                val code = trie.letter(child)

                if (code == Alphabet.APOSTROPHE) {
                    // Transparent: apostrophes have no key and zero path length.
                    letters[depth] = code
                    descend(child, ti, depth + 1, segStartDepth, lettersInSeg, idealLen, lastIdx, prevLetter, tapPen)
                    continue
                }
                if (!g.hasKey(code)) continue

                // A letter may match any pass of the path near its key (first
                // or second visit, or a flyover between two other keys); the
                // earliest admissible pass keeps the order constraint loosest.
                val pass = m.passAtOrAfter(code, lastIdx - KineticaConstants.MONOTONE_SLACK)
                if (lettersInSeg == 0) {
                    // A normal segment's first letter must sit near the path's
                    // start point. A split second half (softStart) resumes
                    // mid-word after a rest, so its first letter may be any key
                    // the resumed path passes near - the peak-trimmed resume
                    // usually starts right on it, this covers straight resumes
                    // that fell back to the fixed trim.
                    if (m.softStart) {
                        if (pass == -1) continue
                    } else {
                        if (!m.isStart(code)) continue
                    }
                } else {
                    if (pass == -1) continue
                }
                val step = if (lettersInSeg == 0 || prevLetter == code) 0f else g.keyDist(prevLetter, code)
                val len2 = idealLen + step
                if (len2 > KineticaConstants.LEN_BAND_HI * m.arcLen + KineticaConstants.LEN_BAND_MARGIN_KW) continue

                letters[depth] = code
                val consumed = lettersInSeg + 1
                // A split first half (softEnd) ends at the cut sample, which is
                // mid-travel whenever the interrupted thumb was moving - its
                // real last letter can sit far behind the cut point, so any
                // letter with a pass on the path may close it. The length band
                // below still polices closings, so mid-path junk stays blocked.
                val closes = m.isEnd(code) || (m.softEnd && pass >= 0)
                // Lower band reads letterArcLen, not arcLen: on a softStart
                // piece the lead-in travel is not evidence that more letters
                // were spelled (Matcher.buildSegment).
                if (consumed >= m.minLetters && closes &&
                    len2 >= KineticaConstants.LEN_BAND_LO * m.letterArcLen -
                    KineticaConstants.LEN_BAND_MARGIN_KW
                ) {
                    segStart[ti] = segStartDepth
                    segEnd[ti] = depth + 1
                    dfs(child, ti + 1, depth + 1, tapPen)
                }
                if (consumed < m.maxLetters) {
                    descend(
                        child, ti, depth + 1, segStartDepth, consumed, len2,
                        if (pass >= 0) maxOf(lastIdx, pass) else lastIdx, code, tapPen,
                    )
                }
                // This start subtree is done (only start letters reach here at
                // the root): move the rollover accounting to the next one.
                if (rootFairness) {
                    firstStart = false
                    remainingStarts--
                }
            }
        }

        /**
         * Fills childOrder[depth] with [node]'s children sorted best-first by
         * subtree frequency and returns the child count: if the candidate cap
         * ever bites, it bites the rarest subtrees.
         */
        private fun orderChildrenByFreq(node: Int, depth: Int): Int {
            val count = trie.childCount(node)
            if (count == 0) return 0
            val first = trie.firstChild(node)
            val order = childOrder[depth]
            for (i in 0 until count) order[i] = first + i
            for (i in 1 until count) {
                val v = order[i]
                val key = trie.maxDescendantFreq(v)
                var j = i - 1
                while (j >= 0 && trie.maxDescendantFreq(order[j]) < key) {
                    order[j + 1] = order[j]
                    j--
                }
                order[j + 1] = v
            }
            return count
        }

        /**
         * Bounded frequency-first descent below a fully-consumed all-anchor
         * prefix, emitting every dictionary word that extends the typed
         * letters as a pick-only Source.COMPLETION candidate. Each extra
         * letter (apostrophes included) adds COMPLETION_PENALTY_PER_LETTER to
         * dTotal, so the exact-length word always outranks its own extensions
         * and shorter completions outrank longer ones at equal frequency.
         */
        private fun completeFrom(node: Int, depth: Int, extra: Int, tapPen: Float) {
            if (passExhausted()) return
            if (extra >= KineticaConstants.COMPLETION_MAX_EXTRA) return
            if (depth >= KineticaConstants.MAX_WORD_LEN) return
            val count = orderChildrenByFreq(node, depth)
            val order = childOrder[depth]
            for (i in 0 until count) {
                if (passExhausted()) return
                val child = order[i]
                letters[depth] = trie.letter(child)
                if (trie.isWord(child)) {
                    emit(
                        child, depth + 1,
                        tapPen + KineticaConstants.COMPLETION_PENALTY_PER_LETTER * (extra + 1),
                        completion = true,
                    )
                }
                completeFrom(child, depth + 1, extra + 1, tapPen)
            }
        }

        private fun emit(node: Int, depth: Int, tapPen: Float, completion: Boolean = false) {
            // An attempt is charged here, where the work is about to happen; a
            // CANDIDATE is charged below, only once this word survives every
            // abandon prune and reaches the heap.
            attempts++
            if (traced) attemptsByFirst!![letters[0]]++
            val fw = KineticaConstants.FREQ_WEIGHT_FLOOR +
                (1f - KineticaConstants.FREQ_WEIGHT_FLOOR) * trie.frequency(node) / 255f
            val bm = bigrams.multiplier(prevWordId, node)
            val variants = forms[node]
            val word: String?
            // The abandon budget must use the best score this node can still
            // reach, both boosts included, or a reinforced or context-boosted
            // word would be DTW-abandoned on its unboosted numerator. That means
            // the UNATTENUATED values: the fit is not known yet here, so neither
            // fit condition can be evaluated, and the bound has to stay
            // optimistic - too loose only costs latency, too tight drops a
            // winner (see maxDTotalForScore). appliedBoost is <= its raw
            // input for both multipliers, which is what keeps this admissible.
            val maxNumerator: Float
            if (variants == null) {
                val sb = StringBuilder(depth)
                for (i in 0 until depth) sb.append(Alphabet.charOf(letters[i]))
                word = sb.toString()
                maxNumerator = fw * bm * personalBoost(word)
            } else {
                word = null
                var best = 0f
                for (v in variants) {
                    val fwV = KineticaConstants.FREQ_WEIGHT_FLOOR +
                        (1f - KineticaConstants.FREQ_WEIGHT_FLOOR) * v.freqByte / 255f
                    val n = fwV * personalBoost(v.display)
                    if (n > best) best = n
                }
                maxNumerator = best * bm
            }

            var budget = Float.POSITIVE_INFINITY
            val minScore = heap.minScoreIfFull()
            if (minScore > 0f) {
                // Inverse of the score's geometric term. Past GEO_SATURATION_KW
                // the term is flat, so a node whose saturated score still clears
                // the heap minimum has no usable distance bound at all and the
                // budget stays infinite - correctness first, latency is held by
                // MAX_EMIT_ATTEMPTS and measured by the *LatencyIsBounded goldens.
                val dMax = KineticaConstants.maxDTotalForScore(maxNumerator, minScore) - tapPen
                if (dMax <= 0f) {
                    if (traced) abandonedScore++
                    return
                }
                if (totalSteps > 0 && dMax.isFinite()) budget = dMax * totalSteps
            }

            var accum = 0f
            for (ti in pattern.indices) {
                val m = pattern[ti] as? Matcher.Segment ?: continue
                if (!dtw.idealPath(letters, segStart[ti], segEnd[ti], g, idealScratch)) {
                    if (traced) abandonedIdeal++
                    return
                }
                val d = dtw.distanceAccum(m.resampled, idealScratch, budget - accum)
                if (d == Float.POSITIVE_INFINITY) {
                    if (traced) abandonedDtw++
                    return
                }
                accum += d
            }
            emitted++
            // geoFit is the shape evidence alone; dTotal adds the tap and
            // completion penalties on top of it. The personal boost is
            // conditioned on the former, the geometric term on the latter.
            val geoFit = if (totalSteps > 0) accum / totalSteps else 0f
            val dTotal = geoFit + tapPen

            val source = when {
                completion -> WordCandidate.Source.COMPLETION
                fuzzyAnchors || basePenalty > 0f -> WordCandidate.Source.FUZZY_TAP
                numSegs == 0 -> WordCandidate.Source.EXACT_TAP
                pattern.size == 1 -> WordCandidate.Source.SWIPE
                else -> WordCandidate.Source.MERGED
            }
            val geo = KineticaConstants.geometricTerm(dTotal)
            // Both boosts are weighted by geoFit through the SAME rule, and
            // both are stored as applied so a captured row closes
            // arithmetically. The abandon budget above deliberately keeps the
            // RAW values.
            val bmApplied = KineticaConstants.appliedBoost(bm, geoFit)
            if (word != null) {
                val pb = KineticaConstants.appliedBoost(personalBoost(word), geoFit)
                val score = fw * geo * bmApplied * pb
                heap.offer(WordCandidate(word, score, dTotal, fw, bmApplied, node, source, language, pb))
            } else {
                // One geometric match, several spellings ("senti"/"sentì"):
                // each variant competes with its own frequency.
                for (v in variants!!) {
                    val fwV = KineticaConstants.FREQ_WEIGHT_FLOOR +
                        (1f - KineticaConstants.FREQ_WEIGHT_FLOOR) * v.freqByte / 255f
                    val pbV = KineticaConstants.appliedBoost(personalBoost(v.display), geoFit)
                    val score = fwV * geo * bmApplied * pbV
                    heap.offer(
                        WordCandidate(v.display, score, dTotal, fwV, bmApplied, node, source, language, pbV),
                    )
                }
            }
        }
    }
}

/** Tiny fixed-capacity top-K set, deduped by word (best score wins). */
class CandidateHeap(private val cap: Int) {
    private val items = ArrayList<WordCandidate>(cap + 1)

    val count: Int get() = items.size

    /** Minimum retained score once full; 0 while below capacity (no abandon budget yet). */
    fun minScoreIfFull(): Float {
        if (items.size < cap) return 0f
        var m = Float.MAX_VALUE
        for (c in items) if (c.score < m) m = c.score
        return m
    }

    fun offer(c: WordCandidate) {
        for (i in items.indices) {
            if (items[i].word == c.word) {
                if (c.score > items[i].score) items[i] = c
                return
            }
        }
        if (items.size < cap) {
            items.add(c)
            return
        }
        var minIdx = 0
        for (i in 1 until items.size) {
            if (items[i].score < items[minIdx].score) minIdx = i
        }
        if (c.score > items[minIdx].score) items[minIdx] = c
    }

    fun sortedByScoreDesc(): List<WordCandidate> = items.sortedByDescending { it.score }
}
