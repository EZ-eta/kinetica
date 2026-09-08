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
     * Live per-user PAIR counts, keyed "prev\u0000next", both lowercased and folded the way
     * the composer's context is. Same concurrent-map contract as [personalCounts].
     *
     * Strings rather than trie node ids, unlike the bundled [BigramTable]: a learned pair
     * may involve a word the trie does not hold, and dropping those is exactly the coverage
     * this store exists to add. Empty unless the user switched phrase learning on.
     */
    private val personalBigrams: Map<String, Int> = emptyMap(),
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
     * The boost this word earns for having followed [prev] before, in this user's own
     * typing. 1.0 when phrase learning is off, when there is no context, or when the pair
     * has never been seen.
     *
     * Deliberately the same log shape as [personalBoost] rather than the bundled table's
     * per-context normalisation: that normalisation gives the argmax continuation of EVERY
     * previous word the full cap, which is why the bundled boost promotes common short
     * continuations. A count-based shape says how often THIS pair happened and nothing
     * about how it ranks among rivals.
     */
    private fun personalBigramBoost(prev: String?, word: String): Float {
        if (prev == null || personalBigrams.isEmpty()) return 1f
        val count = personalBigrams["$prev\u0000$word"] ?: return 1f
        // Judged here rather than when the map is built, because a pair reaches the live map
        // the moment it is learned and would otherwise boost until the next dictionary load.
        if (count < KineticaConstants.PERSONAL_PAIR_MIN_COUNT) return 1f
        return 1f + KineticaConstants.PERSONAL_BIGRAM_BOOST * kotlin.math.ln(1f + count)
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

    /**
     * Whether [s] can still be extended into a word - i.e. the folded form reaches any
     * node at all, word or not.
     *
     * Deliberately weaker than [isWord]: `autom` is not a word and must answer true here,
     * because that is exactly the state a half-typed word is in. Spelling variants are not
     * consulted for the same reason - a prefix has not chosen its accents yet.
     */
    fun isLivePrefix(s: String): Boolean =
        s.isNotEmpty() && trie.prefixNode(AccentFolder.fold(s.lowercase())) != -1

    /** [context] = last committed words, oldest first (window of 2). */
    fun decode(tokens: List<InputToken>, context: List<String>): List<WordCandidate> {
        val g = geometry ?: return emptyList()
        if (tokens.isEmpty() || tokens.size > KineticaConstants.MAX_WORD_LEN) return emptyList()
        DecodeTrace.log { "decode in$langTag: " + tokens.sortedBy { it.tStart }.joinToString(" ") { traceToken(it) } + " ctx=$context" }
        val prevWord = context.lastOrNull()?.let { AccentFolder.fold(it.lowercase()) }
        val prevWordId = prevWord?.let { trie.nodeFor(it) } ?: -1

        val heap = CandidateHeap(KineticaConstants.TOP_K)
        val seqs = MergeAlternatives.sequences(tokens, dtw)
        val patterns = ArrayList<List<Matcher>>(4)
        for (seq in seqs) {
            patterns.add(Matcher.buildPattern(seq, g) ?: continue)
        }
        // The keys each swipe came within R_INNER_KW of but was never measurably ON.
        //
        // This is the one field the trace has been missing for the largest bucket in the
        // engine: 45% of labelled buffers are a gesture that never contacts some letter of
        // its own word, and from a capture alone there is no way to tell a crossing that
        // hysteresis dropped from a corner the thumb genuinely cut. Contacts are all the
        // trace records, and the contact list is the thing under suspicion.
        //
        // Here the question is answerable, because `nearPath` is computed from the REAL
        // resampled path rather than from a reconstruction through contact centres. Logged
        // once, off the primary pattern only, so a dense buffer does not repeat it per
        // sequence variant.
        if (DecodeTrace.enabled) {
            val line = nearMissLine(patterns.firstOrNull().orEmpty(), g)
            if (line != null) DecodeTrace.log { line }
        }
        for (p in patterns) {
            Search(p, g, prevWordId, prevWord, heap, fuzzyAnchors = false).run()
        }
        // One further pass over the PRIMARY sequence in which the search places its own
        // cuts. The generators above commit to a cut before anything knows which word is
        // being spelled, and the ceiling probe prices that guess at 104 buffers of 899, 97
        // of them a lead. Only the primary sequence cuts: the alternatives are already
        // somebody's guess at a cut, and cutting a guess again multiplies work for readings
        // this pass reaches directly.
        val primary = seqs.firstOrNull()
        if (heap.count < KineticaConstants.TOP_K && primary != null && primary.any { it is SwipeToken }) {
            val p = patterns.firstOrNull()
            if (p != null && p.size == primary.size) {
                val cuts = cutCandidates(primary)
                if (cuts.isNotEmpty()) {
                    Search(
                        p, g, prevWordId, prevWord, heap, fuzzyAnchors = false,
                        itemStart = LongArray(primary.size) { primary[it].tStart },
                        srcOf = Array(primary.size) { primary[it] as? SwipeToken },
                        cuts = cuts,
                    ).run()
                }
            }
        }

        // Fallback passes for sparse results: relaxed anchors (adjacent-key
        // typos, slightly missed taps in merged input), then transpositions
        // for all-tap sequences ("hte" -> "the").
        if (heap.count < KineticaConstants.TOP_K) {
            for (p in patterns) {
                if (p.any { it is Matcher.Anchor }) {
                    Search(p, g, prevWordId, prevWord, heap, fuzzyAnchors = true).run()
                }
            }
            val primary = patterns.firstOrNull()
            if (primary != null && tokens.all { it is TapToken } && primary.size >= 2) {
                for (tp in transposedPatterns(primary)) {
                    Search(
                        tp, g, prevWordId, prevWord, heap, fuzzyAnchors = false,
                        basePenalty = KineticaConstants.TRANSPOSE_PENALTY,
                    ).run()
                }
            }
        }
        val out = heap.sortedByScoreDesc()
        // Full score components per candidate: rank upsets are usually decided
        // by fw/boost arithmetic, not geometry, and d alone cannot show that.
        // Every factor of score = fw * geometricTerm(d) * bm * pb * ck is printed, so
        // a captured row closes arithmetically with no inversion, which every
        // earlier tuning pass had to do by hand. `bm` and `pb` are both
        // the APPLIED values, i.e. after their fit conditions: `pb` reads 1.0 on
        // a word with no commits AND on a reinforced word whose fit landed past
        // GEO_SATURATION_KW, and `bm` reads its attenuated value there rather
        // than the table's. Neither is recoverable from the score alone, which is
        // why they are fields (both go through appliedBoost). A `bm` or `pb`
        // strictly between 1.0 and its raw value is how a capture shows the
        // fade firing; exactly 1.0 past one key hop is the far end of it.
        //
        // `ck` was the fifth factor and went unprinted for one release, which cost a
        // hand division to establish that the uncontacted-letter charge fired at all
        // on the buffer where `happens` lost to `happiness`. It reads
        // UNCONTACTED_LETTER_KEEP once per never-touched letter, so 0.85 is one such
        // letter, 0.72 is two, and 1.0 is either a clean reading or a token buffer
        // with no contacts to judge by - a distinction the trace's own `keys=` settles.
        DecodeTrace.log {
            "decode out$langTag: " + if (out.isEmpty()) "<empty>" else
                out.take(5).joinToString(" ") {
                    "${it.word}(d=${(it.dtwDistance * 100).toInt() / 100f}," +
                        "s=${(it.score * 1000).toInt() / 1000f}," +
                        "fw=${(it.frequencyWeight * 100).toInt() / 100f}," +
                        "bm=${(it.bigramMultiplier * 100).toInt() / 100f}," +
                        "pb=${(it.personalBoost * 100).toInt() / 100f}," +
                        "ck=${(it.contactKeep * 100).toInt() / 100f}," +
                        "pbm=${(it.personalBigram * 100).toInt() / 100f},${it.source})"
                }
        }
        return out
    }

    /**
     * Where the other thumb was busy inside each swipe: the times a cut is worth trying.
     *
     * The same evidence every shipped generator draws on - the other stream's token
     * boundaries and its key-contact entries - because a cut nothing in the input points at
     * is not a reading, it is a guess with more arithmetic. Times land strictly inside the
     * swipe and are capped, since each one is a branch the search has to walk.
     */
    private fun cutCandidates(primary: List<InputToken>): Map<SwipeToken, LongArray> {
        val out = HashMap<SwipeToken, LongArray>()
        for ((i, tok) in primary.withIndex()) {
            if (tok !is SwipeToken) continue
            val times = sortedSetOf<Long>()
            for ((k, o) in primary.withIndex()) {
                if (k == i || o.streamId == tok.streamId) continue
                for (t in longArrayOf(o.tStart, o.tEnd)) {
                    if (t > tok.tStart && t < tok.tEnd) times.add(t)
                }
                if (o is SwipeToken) {
                    for (c in o.keyContacts) {
                        if (c.tEnter > tok.tStart && c.tEnter < tok.tEnd) times.add(c.tEnter)
                    }
                }
            }
            if (times.isEmpty()) continue
            out[tok] = times.take(KineticaConstants.MAX_SEARCH_CUT_TIMES).toLongArray()
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
            //
            // Contact TIMES are the same argument one level down, and the first
            // dual-thumb capture is what forced it: the letters alone show WHICH
            // keys each thumb crossed but not WHEN, and when is the whole of the
            // open question. Two overlapping swipes carry one event time between
            // them, which is why the merge can only ever cut the earlier one; the
            // handovers the reading actually needs are in here, on both streams'
            // shared clock. Offsets are relative to the token's own tStart -
            // absolute device uptime is six digits of noise per contact.
            (if (t.keyContacts.isEmpty()) "" else {
                t.keyContacts.joinToString(",", ",keys=") {
                    "${Alphabet.charOf(it.code)}@${it.tEnter - t.tStart}-${it.tExit - t.tStart}"
                }
            }) +
            // Dwell span, peak displacement and sample count. Displacement is
            // here because DWELL_RADIUS_KW could NOT be derived from an
            // earlier capture: it printed times only, so nothing in it
            // constrained the radius.
            (if (t.dwells.isEmpty()) "" else {
                t.dwells.joinToString(";", ",dwell=") { d ->
                    "${d.tEnter}-${d.tExit}/${dwellSpanKw(t, d)}kw/${d.exitIdx - d.enterIdx + 1}n"
                }
            }) +
            // Arc and sampling, appended LAST because TraceReplay's swipe pattern
            // requires `keys=` to follow the interval, and a field inserted between them
            // silently stops every committed fixture from parsing.
            //
            // Arc is the field the trace never carried and the one a reconstruction
            // destroys: a replayed buffer is a clean polyline through the contacted keys,
            // so its arc is shorter than the thumb's, and arc is what decides minLetters
            // and both length bands. Item 41's `provando` piece fails on the device at
            // arc 3.20 kw where the word needs one letter, and that had to be derived
            // from a fixture because no capture recorded it.
            //
            // Sample count and mean interval are for the other half of item 44. A key
            // contact is recorded only when a SAMPLE lands inside a different key's rect
            // after leaving the current key's inflated one (GestureStream.addPoint), so a
            // key crossed between two samples records nothing. Whether that happens at
            // the device's real sampling rate is unmeasured, and 158 of the 254 labelled
            // buffers that miss a letter miss exactly one.
            ",arc=${(t.arcLen * 100).toInt() / 100f}" +
            ",n=${t.rawPath.size}/${(t.tEnd - t.tStart) / maxOf(1, t.rawPath.size - 1)}ms" +
            "]"
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
    /** One cut: the two Segments it makes, and the tail's token so it can be cut again. */
    private class Halves(
        val head: Matcher.Segment,
        val tail: Matcher.Segment,
        val tailToken: SwipeToken,
    )

    private inner class Search(
        private val pattern: List<Matcher>,
        private val g: KeyboardGeometry,
        private val prevWordId: Int,
        /** The folded previous word, for the personal pair store which is keyed on strings. */
        private val prevWord: String?,
        private val heap: CandidateHeap,
        private val fuzzyAnchors: Boolean,
        private val basePenalty: Float = 0f,
        /** Token start times per pattern position; only a cutting pass needs them. */
        private val itemStart: LongArray? = null,
        /** The swipe behind each Segment position, so a piece can be re-cut. */
        private val srcOf: Array<SwipeToken?>? = null,
        /** Candidate cut times per swipe, from the other stream's own events. */
        private val cuts: Map<SwipeToken, LongArray>? = null,
    ) {
        private val cutting = itemStart != null && srcOf != null && cuts != null
        private val cutCache = HashMap<SwipeToken, HashMap<Long, Halves?>>()
        // Tails waiting to be resumed, one per cut this branch has open. A set rather
        // than a single slot because the reading the cut exists for is head, other thumb,
        // tail on BOTH swipes at once: with one slot only one swipe in a buffer can ever be
        // cut, and the corpus says that reaches nothing the generators do not already find.
        private val pendingTail = arrayOfNulls<Matcher.Segment>(KineticaConstants.MAX_SEARCH_CUTS + 1)
        private val pendingStart = LongArray(KineticaConstants.MAX_SEARCH_CUTS + 1)

        /**
         * The tail's own token and the WHOLE swipe it came from, per open cut.
         *
         * A tail is a SwipeToken like any other and can be cut again, which is what makes a
         * third cut reachable at all. Two references are needed rather than one: the token is
         * what gets split, and the original is what the candidate cut times are keyed on,
         * since those come from the other stream's events inside the whole gesture.
         */
        private val pendingToken = arrayOfNulls<SwipeToken>(KineticaConstants.MAX_SEARCH_CUTS + 1)
        private val pendingOrigin = arrayOfNulls<SwipeToken>(KineticaConstants.MAX_SEARCH_CUTS + 1)
        private var cutsOpen = 0
        private val letters = IntArray(KineticaConstants.MAX_WORD_LEN)
        // The pieces this branch has closed, in the order it closed them, with the letter
        // range each consumed. This replaces two arrays indexed by PATTERN position, which
        // could only describe a segmentation fixed before the search started: a piece is
        // now a thing the branch owns rather than a slot the pattern owns. The stack is
        // bounded by MAX_WORD_LEN because every piece consumes at least one letter.
        //
        // Byte-identical today. At emit() the search sits at ti == pattern.size with every
        // pattern segment closed exactly once, so the stack holds the same pieces the two
        // arrays did and in the same order.
        private val pieceSeg = arrayOfNulls<Matcher.Segment>(KineticaConstants.MAX_WORD_LEN + 1)
        private val pieceFrom = IntArray(KineticaConstants.MAX_WORD_LEN + 1)
        private val pieceTo = IntArray(KineticaConstants.MAX_WORD_LEN + 1)
        private var pieceCount = 0
        private val childOrder = Array(KineticaConstants.MAX_WORD_LEN + 1) { IntArray(Alphabet.SIZE + 1) }
        private val numSegs = pattern.count { it is Matcher.Segment }
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

        // Which segment gate refused a letter, and which refused a segment's
        // CLOSE. Every empty decode in the 2026-08-28 capture reported
        // attempts=0 on every one of its search lines - 93 decodes, 1 567 lines,
        // not one candidate ever scored and rejected - so an empty decode is
        // never the DTW budget and never ranking. It is these gates, and until
        // now the trace could say that nothing closed without saying what
        // stopped it. Counted only under a trace sink, like the arrays above.
        private var gateStart = 0      // first letter not near the path's start
        private var gatePass = 0       // no admissible pass at or after lastIdx
        private var gateBandHi = 0     // ideal length over the upper band
        private var gateMinLetters = 0 // could close but has consumed too few letters
        private var gateCloses = 0     // enough letters, but no letter may end the piece
        private var gateBandLo = 0     // closes, but the ideal is too short for the arc
        private var gateMaxLetters = 0 // segment full, cannot take another letter

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
                "attemptsByFirst=${top(attemptsByFirst)} nodesByFirst=${top(visitsByFirst)} " +
                "gates(start=$gateStart,pass=$gatePass,bandHi=$gateBandHi," +
                "minLetters=$gateMinLetters,closes=$gateCloses,bandLo=$gateBandLo," +
                "maxLetters=$gateMaxLetters)"
        }

        private fun dfs(node: Int, ti: Int, depth: Int, tapPen: Float, contactKeep: Float = 1f) {
            if (branchExhausted()) {
                if (traced) noteBudgetStop(depth)
                return
            }
            // A swipe cut open earlier resumes as soon as nothing else started before it.
            // This is the ordering `MergeAlternatives.orderByTime` applies after the fact,
            // applied here instead, which is the whole point of cutting in the search: the
            // interesting readings are head, other thumb, tail.
            val due = earliestPending(ti)
            if (due >= 0) {
                val tail = pendingTail[due]!!
                val start = pendingStart[due]
                val token = pendingToken[due]
                val origin = pendingOrigin[due]
                pendingTail[due] = null
                descend(
                    node, ti, depth,
                    segStartDepth = depth, lettersInSeg = 0, idealLen = 0f,
                    lastIdx = -KineticaConstants.MONOTONE_SLACK, prevLetter = -1, tapPen = tapPen,
                    contactKeep = contactKeep, seg = tail, nextTi = ti,
                )
                // And the tail may itself be cut, which is the only way a third piece of one
                // swipe is ever reachable. It closes back to ti like the tail it replaces.
                if (token != null && origin != null) {
                    offerCuts(node, ti, depth, tapPen, contactKeep, token, origin, nextTi = ti)
                }
                pendingTail[due] = tail
                pendingStart[due] = start
                pendingToken[due] = token
                pendingOrigin[due] = origin
                return
            }
            if (ti == pattern.size) {
                if (depth > 0 && trie.isWord(node)) emit(node, depth, tapPen, contactKeep)
                if (completesPrefix) completeFrom(node, depth, extra = 0, tapPen = tapPen, contactKeep = contactKeep)
                return
            }
            when (val item = pattern[ti]) {
                is Matcher.Anchor -> anchorStep(node, ti, depth, tapPen, contactKeep, allowApostrophe = true)
                is Matcher.Segment -> {
                    descend(
                        node, ti, depth,
                        segStartDepth = depth, lettersInSeg = 0, idealLen = 0f,
                        lastIdx = -KineticaConstants.MONOTONE_SLACK, prevLetter = -1, tapPen = tapPen,
                        contactKeep = contactKeep, seg = item, nextTi = ti + 1,
                    )
                    srcOf?.get(ti)?.let { src ->
                        offerCuts(node, ti, depth, tapPen, contactKeep, src, src, nextTi = ti + 1)
                    }
                }
            }
        }

        /**
         * The same swipe, read as a head and a tail with the other thumb's letters between.
         *
         * The cut is chosen HERE, before any letter is assigned, so each half is validated
         * against its own geometry by the ordinary gates rather than inherited from a whole
         * segment it is not. That is what makes it correct by construction: a head is a
         * softEnd piece and a tail a softStart one, exactly as the split generators produce,
         * and nothing downstream can tell where the piece came from.
         *
         * Bounded three ways, because this multiplies the search: only the primary pattern
         * cuts at all, only [KineticaConstants.MAX_SEARCH_CUTS] cuts may be open at once,
         * and the candidate times are the other stream's own events, which is where every
         * shipped generator looks too.
         */
        private fun offerCuts(
            node: Int,
            ti: Int,
            depth: Int,
            tapPen: Float,
            contactKeep: Float,
            token: SwipeToken,
            origin: SwipeToken,
            nextTi: Int,
        ) {
            if (!cutting) return
            if (cutsOpen >= KineticaConstants.MAX_SEARCH_CUTS) return
            val times = cuts!![origin] ?: return
            val slot = cutsOpen
            for (t in times) {
                if (passExhausted()) return
                // A tail only owns the part of the gesture after its own start, so a time
                // outside it would re-cut travel this branch has already spent.
                if (t <= token.tStart || t >= token.tEnd) continue
                val halves = cutHalves(token, t) ?: continue
                cutsOpen++
                pendingTail[slot] = halves.tail
                pendingStart[slot] = t
                pendingToken[slot] = halves.tailToken
                pendingOrigin[slot] = origin
                descend(
                    node, ti, depth,
                    segStartDepth = depth, lettersInSeg = 0, idealLen = 0f,
                    lastIdx = -KineticaConstants.MONOTONE_SLACK, prevLetter = -1, tapPen = tapPen,
                    contactKeep = contactKeep, seg = halves.head, nextTi = nextTi,
                )
                pendingTail[slot] = null
                pendingToken[slot] = null
                pendingOrigin[slot] = null
                cutsOpen--
            }
        }

        /**
         * The tail due next at pattern position [ti], or -1 while something started first.
         *
         * Earliest start wins, which is the rule MergeAlternatives.orderByTime applies to a
         * finished sequence. A tail whose swipe resumed before the next token is consumed
         * first; otherwise the token goes in between, which is the whole reading.
         */
        private fun earliestPending(ti: Int): Int {
            var best = -1
            for (i in 0 until pendingTail.size) {
                val t = pendingTail[i] ?: continue
                if (best < 0 || pendingStart[i] < pendingStart[best]) best = i
            }
            if (best < 0) return -1
            if (ti < pattern.size && itemStart!![ti] < pendingStart[best]) return -1
            return best
        }

        /** Memoized per (swipe, cut time): building a Segment costs 26 keys x 32 samples. */
        private fun cutHalves(src: SwipeToken, t: Long): Halves? {
            val perSwipe = cutCache.getOrPut(src) { HashMap() }
            if (perSwipe.containsKey(t)) return perSwipe[t]
            val halves = MergeAlternatives.splitSwipe(src, t, dtw)
            val built = halves?.let {
                Halves(Matcher.buildSegment(it.first, g), Matcher.buildSegment(it.second, g), it.second)
            }
            perSwipe[t] = built
            return built
        }

        private fun anchorStep(
            node: Int,
            ti: Int,
            depth: Int,
            tapPen: Float,
            contactKeep: Float,
            allowApostrophe: Boolean,
        ) {
            if (depth >= KineticaConstants.MAX_WORD_LEN) return
            val m = pattern[ti] as Matcher.Anchor
            if (!fuzzyAnchors) {
                tryAnchor(node, m.code, 0f, ti, depth, tapPen, contactKeep)
            } else {
                for (code in 0 until Alphabet.LETTERS) {
                    if (!g.hasKey(code)) continue
                    val d = g.distToCenter(m.x, m.y, code)
                    if (code != m.code && d > KineticaConstants.FUZZY_TAP_RADIUS_KW) continue
                    val pen = if (code == m.code) 0f else KineticaConstants.FUZZY_TAP_LAMBDA * d
                    tryAnchor(node, code, pen, ti, depth, tapPen, contactKeep)
                }
            }
            if (allowApostrophe) {
                // Consume a dictionary apostrophe without consuming input, so a
                // tapped t after swiping d-o-n still reaches "don't".
                val apo = trie.child(node, Alphabet.APOSTROPHE)
                if (apo != -1 && depth + 1 < KineticaConstants.MAX_WORD_LEN) {
                    letters[depth] = Alphabet.APOSTROPHE
                    anchorStep(apo, ti, depth + 1, tapPen, contactKeep, allowApostrophe = false)
                }
            }
        }

        private fun tryAnchor(
            node: Int,
            code: Int,
            penalty: Float,
            ti: Int,
            depth: Int,
            tapPen: Float,
            contactKeep: Float,
        ) {
            val next = trie.child(node, code)
            if (next == -1) return
            letters[depth] = code
            dfs(next, ti + 1, depth + 1, tapPen + penalty, contactKeep)
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
            contactKeep: Float,
            // The piece being consumed and where to go when it closes. They used to be
            // `pattern[ti]` and `ti + 1`, which is only true while the segmentation is
            // fixed: a resumed half is consumed AT the pattern position it interrupts, so
            // it closes back to the same index rather than past it.
            seg: Matcher.Segment,
            nextTi: Int,
        ) {
            if (branchExhausted()) {
                if (traced) noteBudgetStop(depth)
                return
            }
            if (depth >= KineticaConstants.MAX_WORD_LEN) return
            if (traced && depth > 0) visitsByFirst!![letters[0]]++
            val m = seg
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
                    descend(
                        child, ti, depth + 1, segStartDepth, lettersInSeg, idealLen,
                        lastIdx, prevLetter, tapPen, contactKeep, seg, nextTi,
                    )
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
                        if (pass == -1) {
                            if (traced) gatePass++
                            continue
                        }
                    } else {
                        if (!m.isStart(code)) {
                            if (traced) gateStart++
                            continue
                        }
                    }
                } else {
                    if (pass == -1) {
                        if (traced) gatePass++
                        continue
                    }
                }
                val step = if (lettersInSeg == 0 || prevLetter == code) 0f else g.keyDist(prevLetter, code)
                val len2 = idealLen + step
                if (len2 > KineticaConstants.LEN_BAND_HI * m.arcLen + KineticaConstants.LEN_BAND_MARGIN_KW) {
                    if (traced) gateBandHi++
                    continue
                }

                letters[depth] = code
                val consumed = lettersInSeg + 1
                // Charged where the letter is taken, so a reading pays once per letter
                // the finger was never measurably on. m.contacted is empty for a token
                // that carries no contacts at all, which charges nothing - a missing
                // contact list is no evidence rather than evidence against.
                val keep = if (m.contacted.isEmpty() || m.contacted[code]) {
                    contactKeep
                } else {
                    contactKeep * KineticaConstants.UNCONTACTED_LETTER_KEEP
                }
                // A split first half (softEnd) ends at the cut sample, which is
                // mid-travel whenever the interrupted thumb was moving - its
                // real last letter can sit far behind the cut point, so any
                // letter with a pass on the path may close it. The length band
                // below still polices closings, so mid-path junk stays blocked.
                val closes = m.isEnd(code) || (m.softEnd && pass >= 0)
                // Lower band reads letterArcLen, not arcLen: on a softStart
                // piece the lead-in travel is not evidence that more letters
                // were spelled (Matcher.buildSegment).
                // The three reasons a close is refused are counted separately,
                // because they are three different fixes. Order is deliberate and
                // is the order of increasing evidence: too few letters to be a
                // piece at all, then no letter that may END the piece, then a
                // reading too short for the arc travelled. Each buffer's refusals
                // are attributed once per letter reached, which is the same
                // denominator attemptsByFirst uses.
                val bandLoOk = len2 >= KineticaConstants.LEN_BAND_LO * m.letterArcLen -
                    KineticaConstants.LEN_BAND_MARGIN_KW
                if (consumed >= m.minLetters && closes && bandLoOk) {
                    pieceSeg[pieceCount] = m
                    pieceFrom[pieceCount] = segStartDepth
                    pieceTo[pieceCount] = depth + 1
                    pieceCount++
                    dfs(child, nextTi, depth + 1, tapPen, keep)
                    pieceCount--
                } else if (traced) {
                    when {
                        consumed < m.minLetters -> gateMinLetters++
                        !closes -> gateCloses++
                        else -> gateBandLo++
                    }
                }
                if (consumed >= m.maxLetters && traced) gateMaxLetters++
                if (consumed < m.maxLetters) {
                    descend(
                        child, ti, depth + 1, segStartDepth, consumed, len2,
                        if (pass >= 0) maxOf(lastIdx, pass) else lastIdx, code, tapPen, keep,
                        seg, nextTi,
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
        private fun completeFrom(
            node: Int,
            depth: Int,
            extra: Int,
            tapPen: Float,
            contactKeep: Float,
        ) {
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
                        contactKeep,
                        completion = true,
                    )
                }
                completeFrom(child, depth + 1, extra + 1, tapPen, contactKeep)
            }
        }

        private fun emit(
            node: Int,
            depth: Int,
            tapPen: Float,
            contactKeep: Float,
            completion: Boolean = false,
        ) {
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

            // The normalizer counts the pieces this branch closed, which for a pattern
            // fixed up front is the same numSegs it always was.
            val totalSteps = pieceCount * KineticaConstants.RESAMPLE_N
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
            for (pi in 0 until pieceCount) {
                val m = pieceSeg[pi]!!
                if (!dtw.idealPath(letters, pieceFrom[pi], pieceTo[pi], g, idealScratch)) {
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
                val pbm = KineticaConstants.appliedBoost(personalBigramBoost(prevWord, word), geoFit)
                val score = fw * geo * bmApplied * pb * pbm * contactKeep
                heap.offer(
                    WordCandidate(
                        word, score, dTotal, fw, bmApplied, node, source, language, pb,
                        contactKeep, pbm,
                    ),
                )
            } else {
                // One geometric match, several spellings ("senti"/"sentì"):
                // each variant competes with its own frequency.
                for (v in variants!!) {
                    val fwV = KineticaConstants.FREQ_WEIGHT_FLOOR +
                        (1f - KineticaConstants.FREQ_WEIGHT_FLOOR) * v.freqByte / 255f
                    val pbV = KineticaConstants.appliedBoost(personalBoost(v.display), geoFit)
                    val pbmV =
                        KineticaConstants.appliedBoost(personalBigramBoost(prevWord, v.display), geoFit)
                    val score = fwV * geo * bmApplied * pbV * pbmV * contactKeep
                    heap.offer(
                        WordCandidate(
                            v.display, score, dTotal, fwV, bmApplied, node, source, language, pbV,
                            contactKeep, pbmV,
                        ),
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

/**
 * One line naming, per swipe piece, the keys its path came within R_INNER_KW of but was
 * never measurably ON - or null when there are none, so a quiet buffer costs no line.
 *
 * This is the field the trace has been missing for the largest bucket in the engine. 45%
 * of labelled buffers are a gesture that never contacts some letter of its own word, and
 * from a capture alone a crossing that hysteresis dropped cannot be told apart from a
 * corner the thumb genuinely cut: contacts are all the trace records, and the contact list
 * is the thing under suspicion. Reconstructing a path through the contact centres cannot
 * settle it either - every neighbour of a touched key sits about 1.0 kw from such a
 * polyline whether the thumb went there or not.
 *
 * Here it is answerable, because `nearPath` is computed from the real resampled path.
 * `[-]` marks a piece carrying no contacts at all, which is no evidence rather than
 * evidence of a miss.
 */
internal fun nearMissLine(pattern: List<Matcher>, g: KeyboardGeometry): String? {
    val sb = StringBuilder("  nearmiss")
    var any = false
    for (m in pattern) {
        if (m !is Matcher.Segment) continue
        sb.append(' ')
        if (m.contacted.isEmpty()) {
            sb.append("[-]")
            continue
        }
        // Closest approach per uncontacted key, nearest first. The DISTANCE is the whole
        // point: the first version of this line reported nearPath as a boolean and was
        // useless, because nearPath means "within R_INNER_KW = 1.8 kw", the decoder's
        // admissibility radius - nearly two key widths either side of the whole stroke. It
        // named 4 to 15 keys per piece, median 7 or 8, mostly the middle of the keyboard.
        // With a distance the question item 44 asks is answerable: a key the path went
        // within a fraction of a key width of and did not record is a crossing hysteresis
        // dropped; one at 1.5 kw is scenery.
        val near = ArrayList<Pair<Int, Float>>(4)
        for (code in 0 until Alphabet.LETTERS) {
            if (m.contacted[code] || !g.hasKey(code)) continue
            var best = Float.MAX_VALUE
            for (k in 0 until KineticaConstants.RESAMPLE_N) {
                val d = g.distToCenter(m.resampled[2 * k], m.resampled[2 * k + 1], code)
                if (d < best) best = d
            }
            if (best <= KineticaConstants.R_INNER_KW) near.add(code to best)
        }
        near.sortBy { it.second }
        sb.append('[')
        for ((i, e) in near.withIndex()) {
            // Capped so a sloppy stroke cannot flood the capture; the tail is scenery by
            // construction, since the list is sorted.
            if (i >= NEARMISS_PER_PIECE) break
            if (i > 0) sb.append(' ')
            sb.append(Alphabet.charOf(e.first))
            sb.append(((e.second * 10).toInt() / 10f).toString())
            any = true
        }
        sb.append(']')
    }
    return if (any) sb.toString() else null
}

/** Keys reported per piece, nearest first; the rest are further away by construction. */
private const val NEARMISS_PER_PIECE = 6

/**
 * One line per commit naming, per letter of the committed word and in word order, how
 * close the gesture came to that letter and whether it ever touched it.
 *
 * The third iteration of the near-miss instrument and the first one that is aimed. The
 * first reported every key within R_INNER_KW of a piece and named 4 to 15 of them per
 * piece; the second added distances and saturated, 128 of 152 lists full at the cap of
 * six, which measures how crowded a QWERTY neighbourhood is. Both asked about keys. The
 * question is about the letters of a NAMED word, and the name is something only the
 * developer can supply, which they do by retyping until the word commits. So the label
 * arrives free at commit time, and the population it labels is 254 of 570 labelled
 * buffers that never touch some letter of their own word, 158 of them missing one.
 *
 * Two things make it answer what the earlier iterations could not:
 *
 *  - **Per occurrence, not per key.** `praticamente` needs `a` twice and the trace could
 *    not say so, because `a` was contacted once and was therefore absent from a per-key
 *    list of what was missed.
 *  - **In order.** Each occurrence is credited only to a contact at or after the previous
 *    occurrence's, so a letter the thumb reached only too early is marked rather than
 *    credited. That is the other half of that reading: the left thumb held `t` at 256 ms
 *    and reached `a` only at 607.
 *
 * Distances come off the real resampled path. A reconstruction through contact centres
 * cannot answer this, and the measurement saying so is emphatic: all 414 never-contacted
 * letter instances read "near the path" at a median 1.00 kw when measured that way.
 *
 * Format, one token per letter occurrence: `<letter><piece>:<kw>@<index>` for the closest
 * the path ever came, plus a mark: `!` a contact at or after the previous occurrence's,
 * `=` the second of a doubled letter sharing one contact, `<` contacted but only earlier
 * than this occurrence needs, and nothing at all for a letter no thumb ever touched.
 * `note=seeded` or `note=unrelated` flags a line whose buffer is not the word's gesture.
 *
 * Those two marks are the two mechanisms the attribution pass counts, per occurrence
 * instead of per buffer: `missing=` is the letter no thumb ever touched, 75% of the
 * inadmissible bucket, and `back=` is the letter touched in the wrong order, which is
 * most of the rest. Neither needs a threshold, which is why the order mark is
 * contact-based rather than a distance comparison: with 32 resample points there is
 * always some index left to match against, so a "no candidate" test would never fire.
 */
internal fun commitMissLine(
    word: String,
    tokens: List<InputToken>,
    pattern: List<Matcher>,
    g: KeyboardGeometry,
    src: String,
    gapMs: Long,
): String? {
    if (word.isEmpty() || pattern.isEmpty()) return null
    // Contacts carry a TIME and the Matcher's `contacted` array does not: it is one
    // boolean per piece, so within a piece it cannot say whether a letter was touched
    // before or after the point a reading needs it. So the ORDER comes off this timeline
    // and the DISTANCE off the path, and the two are kept separate on purpose.
    //
    // They were not, in the first version, and its own first capture caught it: the
    // distance was measured over the path remaining after the previous letter's position
    // while the mark came from the unconstrained timeline, so 146 of 1 506 contacted
    // letters reported over 1.8 kw - impossible for a key the finger was on. The distance
    // is the closest the path ever came, full stop.
    val timeline = ArrayList<Triple<Long, Int, Int>>()
    for ((i, t) in tokens.withIndex()) {
        when (t) {
            is TapToken -> timeline.add(Triple(t.tStart, t.code, i))
            is SwipeToken -> for (c in t.keyContacts) timeline.add(Triple(c.tEnter, c.code, i))
        }
    }
    timeline.sortBy { it.first }
    var cursor = 0

    val letters = StringBuilder()
    var counted = 0
    var missing = 0
    var broke = 0
    var prev = ' '
    for (ch in word) {
        val code = ch - 'a'
        if (code !in 0 until Alphabet.LETTERS || !g.hasKey(code)) continue
        val hit = closest(pattern, g, code) ?: continue
        counted++
        letters.append(' ').append(ch).append(hit.piece).append(':')
        letters.append((hit.dist * 100).toInt() / 100f).append('@').append(hit.idx)
        val at = (cursor until timeline.size).firstOrNull { timeline[it].second == code }
        when {
            at != null -> { letters.append('!'); cursor = at + 1 }
            // A doubled letter is drawn with ONE contact, so the second occurrence has no
            // later contact to claim and is not an order violation. 78 of 431 `<` marks in
            // the first capture were this, which overstated the order class by 18%.
            ch == prev -> letters.append('=')
            timeline.any { it.second == code } -> { letters.append('<'); broke++ }
            else -> missing++
        }
        prev = ch
    }
    if (letters.isEmpty()) return null

    val sb = StringBuilder("  commitmiss src=")
    sb.append(src)
    sb.append(" word=").append(word)
    if (gapMs >= 0) sb.append(" gap=").append(gapMs)
    // A commit whose buffer is not this word's gesture at all - a picked suggestion, a
    // completion, or the synthetic reload that seeds anchors from committed text - reports
    // every letter missing and would otherwise be counted as evidence about a thumb.
    // `let's` and `really` arrived that way in the first capture.
    val note = ArrayList<String>(2)
    if (isSeeded(tokens)) note.add("seeded")
    if (counted > 0 && missing == counted) note.add("unrelated")
    if (note.isNotEmpty()) sb.append(" note=").append(note.joinToString(","))
    sb.append(" missing=").append(missing).append(" back=").append(broke)
    sb.append(letters)
    return sb.toString()
}

/**
 * The synthetic buffer `reloadWordUnderCursor` seeds from already-committed text, by item
 * 46's signature: every token a tap, timestamps a millisecond apart because they come from
 * one `uptimeMillis()` call rather than from real touches.
 */
private fun isSeeded(tokens: List<InputToken>): Boolean {
    if (tokens.size < 2 || tokens.any { it !is TapToken }) return false
    val t = tokens.map { it.tStart }.sorted()
    return t.last() - t.first() <= SEEDED_SPAN_MS * (t.size - 1)
}

/** The closest [code]'s key centre ever comes to [pattern]'s path, anywhere on it. */
private fun closest(pattern: List<Matcher>, g: KeyboardGeometry, code: Int): Approach? {
    var out: Approach? = null
    for ((p, m) in pattern.withIndex()) {
        when (m) {
            is Matcher.Anchor ->
                out = better(out, Approach(p, 0, g.distToCenter(m.x, m.y, code), m.code == code))
            is Matcher.Segment -> {
                var bestD = Float.MAX_VALUE
                var bestI = -1
                for (k in 0 until KineticaConstants.RESAMPLE_N) {
                    val d = g.distToCenter(m.resampled[2 * k], m.resampled[2 * k + 1], code)
                    if (d < bestD) { bestD = d; bestI = k }
                }
                if (bestI >= 0) out = better(out, Approach(p, bestI, bestD, m.contacted[code]))
            }
        }
    }
    return out
}

/** A contacting piece always wins; distance decides between equals. */
private fun better(a: Approach?, b: Approach): Approach? {
    if (a == null) return b
    if (a.contacted != b.contacted) return if (a.contacted) a else b
    return if (b.dist < a.dist) b else a
}

/** Milliseconds a seeded anchor may sit from its neighbour; real taps are 100-600 apart. */
private const val SEEDED_SPAN_MS = 2L

private class Approach(val piece: Int, val idx: Int, val dist: Float, val contacted: Boolean)
