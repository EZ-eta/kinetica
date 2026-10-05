package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.Dwell
import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
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
 * highly selective; pure-swipe patterns rely on the endpoint/inner prunes.
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
     * Live per-user pair counts, keyed "prev\u0000next", both lowercased and folded the way
     * the composer's context is. Same concurrent-map contract as [personalCounts].
     *
     * Strings, not trie node ids as in the bundled [BigramTable]: a learned pair may involve a
     * word the trie does not hold, and those pairs are the coverage this store adds. Empty
     * unless the user switched phrase learning on.
     */
    private val personalBigrams: Map<String, Int> = emptyMap(),
    /**
     * Language code stamped onto every candidate this predictor emits. Empty
     * for the single-language fixtures, so it is defaulted and last.
     */
    val language: String = "",
) {
    /** The letters this predictor's trie and geometry are spelled in. */
    val alphabet: Alphabet get() = trie.alphabet

    private val dtw = DtwMatcher()
    private val idealScratch = FloatArray(2 * KineticaConstants.RESAMPLE_N)

    /**
     * Trace suffix identifying which dictionary a decode line belongs to.
     * Empty (so single-language traces are unchanged) unless a language was
     * given. With two predictors running per word, the in/out pairs are
     * otherwise told apart only by position.
     */
    private val langTag: String = if (language.isEmpty()) "" else "[$language]"

    /** Commit count for [word], 0 when never committed. */
    fun personalCount(word: String): Int = personalCounts[word.lowercase()] ?: 0

    /**
     * The boost [word]'s commit count earns at a perfect fit. A candidate
     * receives this passed through [KineticaConstants.appliedBoost], which
     * fades it out as the candidate's own geometry stops carrying information.
     * Only the DTW abandon budget uses the raw value, because that bound must
     * stay optimistic.
     */
    private fun personalBoost(word: String): Float {
        val count = personalCounts[word] ?: return 1f
        return 1f + KineticaConstants.PERSONAL_BOOST * kotlin.math.ln(1f + count)
    }

    companion object {
        /**
         * The personal pair store's key: both words lowercase and accent-folded, as the trie
         * holds them. Stored unfolded, `perché` never found its pairs, in the decode or in the
         * next-word bar.
         */
        fun pairKey(prev: String, next: String): String =
            AccentFolder.fold(prev.lowercase()) + "\u0000" + AccentFolder.fold(next.lowercase())
    }

    /**
     * The boost this word earns for having followed [prev] before, in this user's own typing.
     * 1.0 when phrase learning is off, when there is no context, or when the pair has never
     * been seen.
     *
     * The same log shape as [personalBoost], not the bundled table's per-context normalisation,
     * which gives the argmax continuation of every previous word the full cap and so promotes
     * common short continuations. A count-based shape says how often this pair happened and
     * nothing about how it ranks among rivals.
     */
    private fun personalBigramBoost(prev: String?, word: String): Float {
        if (prev == null || personalBigrams.isEmpty()) return 1f
        val count = personalBigrams[pairKey(prev, word)] ?: return 1f
        // Judged here, not when the map is built: a pair reaches the live map the moment it is
        // learned and would otherwise boost until the next dictionary load.
        if (count < KineticaConstants.PERSONAL_PAIR_MIN_COUNT) return 1f
        return 1f + KineticaConstants.PERSONAL_BIGRAM_BOOST * kotlin.math.ln(1f + count)
    }

    /** A word offered before any gesture: what usually follows the previous one. */
    data class NextWord(val word: String, val score: Float, val language: String)

    /**
     * The words that most often follow [prev], strongest first, for the idle bar.
     *
     * Ranked by the bundled pair's count byte, times the learned pair's boost when [personal]
     * allows it: the same two shapes the decoder weighs a word by after this context. A
     * learned pair whose word the dictionary no longer holds (blocked, or never merged) is
     * skipped.
     */
    fun nextWords(prev: String, limit: Int, personal: Boolean): List<NextWord> {
        if (limit <= 0) return emptyList()
        val key = AccentFolder.fold(prev.lowercase())
        val prevId = trie.nodeFor(key)
        val score = HashMap<Int, Float>()
        for ((next, byte) in bigrams.successors(prevId, KineticaConstants.NEXT_WORD_POOL)) {
            if (trie.isWord(next)) score[next] = 1f + KineticaConstants.BIGRAM_BOOST_MAX * byte / 255f
        }
        if (personal && personalBigrams.isNotEmpty()) {
            val prefix = pairKey(key, "")
            for ((k, count) in personalBigrams) {
                if (!k.startsWith(prefix) || count < KineticaConstants.PERSONAL_PAIR_MIN_COUNT) continue
                val node = trie.nodeFor(k.substring(prefix.length))
                if (node < 0) continue
                score[node] = (score[node] ?: 1f) *
                    (1f + KineticaConstants.PERSONAL_BIGRAM_BOOST * kotlin.math.ln(1f + count))
            }
        }
        return score.entries
            .sortedWith(
                compareByDescending<Map.Entry<Int, Float>> { it.value }
                    .thenByDescending { trie.frequency(it.key) }
                    .thenBy { it.key },
            )
            .take(limit)
            .map { (node, s) -> NextWord(displayOf(node), s, language) }
    }

    /** The spelling shown for [node]: its most frequent display form, else the trie's own. */
    private fun displayOf(node: Int): String =
        forms[node]?.maxByOrNull { it.freqByte }?.display ?: trie.wordOf(node)

    /**
     * A string is a word when its folded form reaches a word node and it is
     * one of that node's spellings: "perche" folds onto the "perché" node but
     * is not itself a word, so autocorrect can restore the accent.
     */
    fun isWord(word: String): Boolean {
        val w = word.lowercase()
        val node = trie.nodeFor(AccentFolder.fold(w))
        if (node == -1) return false
        val variants = forms[node] ?: return true
        return variants.any { it.display == w }
    }

    /**
     * Whether [word] is one of this lexicon's spellings as written. [isWord] reads a key with no
     * display forms as holding every spelling that folds onto it, so English would claim Italian
     * `è` through its `e` and the merge would drop it as shared. Here a key with no forms holds
     * only its own unaccented spelling.
     */
    fun holdsSpelling(word: String): Boolean {
        val w = word.lowercase()
        val folded = AccentFolder.fold(w)
        val node = trie.nodeFor(folded)
        if (node == -1) return false
        val variants = forms[node] ?: return folded == w
        return variants.any { it.display.lowercase() == w }
    }

    /**
     * Whether [word] is a word of this lexicon with its accents left off: `pojsc` for `pójść`.
     * Such letters are not offered back as typed; a real pair like `ze`/`że` holds both spellings
     * and answers false.
     */
    fun leavesAccentsOff(word: String): Boolean {
        val w = word.lowercase()
        if (AccentFolder.fold(w) != w) return false
        val node = trie.nodeFor(w)
        if (node == -1) return false
        val variants = forms[node] ?: return false
        return variants.none { it.display.lowercase() == w }
    }

    /** The frequency byte fw is built from, of [word]'s own spelling; -1 when it is not a word. */
    fun frequencyByte(word: String): Int {
        val w = word.lowercase()
        val node = trie.nodeFor(AccentFolder.fold(w))
        if (node == -1) return -1
        val variants = forms[node] ?: return trie.frequency(node)
        return variants.firstOrNull { it.display == w }?.freqByte ?: -1
    }

    /**
     * Whether [s] can still be extended into a word: the folded form reaches any node, word or
     * not.
     *
     * Weaker than [isWord]: `autom` is not a word and must answer true, since a half-typed word
     * is in that state. Spelling variants are not consulted either: a prefix has not chosen its
     * accents yet.
     */
    fun isLivePrefix(s: String): Boolean =
        s.isNotEmpty() && trie.prefixNode(AccentFolder.fold(s.lowercase())) != -1

    /**
     * Ranked candidates for [tokens]. [context] is the last committed words, oldest first
     * (window of 2). [beam] lets the thumb-cursor beam read the buffer too (per
     * [KineticaConstants.SEARCH_ENGINE]); the composer turns it off for the second language,
     * whose stronger foreign readings would otherwise crowd the active list.
     */
    fun decode(tokens: List<InputToken>, context: List<String>, beam: Boolean = true): List<WordCandidate> {
        val g = geometry ?: return emptyList()
        // A board in another script, for the moment a language switch leaves them apart.
        if (g.alphabet !== trie.alphabet) return emptyList()
        if (tokens.isEmpty() || tokens.size > KineticaConstants.MAX_WORD_LEN) return emptyList()
        // Timed only under a trace sink, so a release build pays nothing for it.
        val startNs = if (DecodeTrace.enabled) System.nanoTime() else 0L
        DecodeTrace.log { "decode in$langTag: " + tokens.sortedBy { it.tStart }.joinToString(" ") { traceToken(it) } + " ctx=$context" }
        val prevWord = context.lastOrNull()?.let { AccentFolder.fold(it.lowercase()) }
        val prevWordId = prevWord?.let { trie.nodeFor(it) } ?: -1

        val heap = CandidateHeap(KineticaConstants.TOP_K)
        val seqs = MergeAlternatives.sequences(tokens, dtw)
        val patterns = ArrayList<List<Matcher>>(4)
        for (seq in seqs) {
            patterns.add(Matcher.buildPattern(seq, g) ?: continue)
        }
        // The keys each swipe came within R_INNER_KW of but never touched. 45% of labelled
        // buffers are a gesture that never contacts some letter of its own word, and a capture's
        // contact list alone cannot tell a crossing hysteresis dropped from a corner the thumb
        // cut. `nearPath` is computed from the real resampled path, so here it can. Logged once,
        // off the primary pattern, so a dense buffer does not repeat it per sequence variant.
        if (DecodeTrace.enabled) {
            val line = nearMissLine(patterns.firstOrNull().orEmpty(), g)
            if (line != null) DecodeTrace.log { line }
        }
        // Which search reads a swipe buffer: the passes below (1), the thumb-cursor beam
        // alone (2), or both into one heap (3), the beam last so every pass sees the heap it
        // would see without it. An all-tap buffer always takes the passes, which own tap
        // completion and transposition.
        val engine = KineticaConstants.SEARCH_ENGINE
        val beamReads = beam && engine != 1 && ThumbBeam.applies(seqs.first(), patterns.firstOrNull())
        if (beamReads && engine == 2) {
            ThumbBeam(this, g, seqs.first(), patterns.first(), heap, prevWordId, prevWord).run()
            return finish(heap.sortedByScoreDesc(), startNs)
        }
        for (p in patterns) {
            Search(p, g, prevWordId, prevWord, heap, fuzzyAnchors = false).run()
        }
        // One more pass over the primary sequence in which the search places its own cuts. The
        // generators above commit to a cut before anything knows which word is being spelled,
        // and that guess costs 104 of 899 labelled buffers, 97 of them a lead. Only the primary
        // sequence is cut: the alternatives are already a guess at a cut, and cutting them
        // again multiplies work for readings this pass reaches directly.
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

        // The two-thumb pass: one cursor per thumb, a cut wherever the reading hands over,
        // and a bounded step back in time at each hand-over, so a thumb that reached its key
        // before the other left the letter before it still reads in the word's order.
        if (primary != null && streamGateOpen(heap)) {
            val p = patterns.firstOrNull()
            val plan = if (p != null) streamPlan(primary, p) else null
            if (plan != null) {
                Search(p!!, g, prevWordId, prevWord, heap, fuzzyAnchors = false, plan = plan).run()
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
            // One tap too many or one letter never tapped, only when the letters as typed are
            // no word and no start of one: ungated, `moltep` led `molte` over `molteplici`.
            if (primary != null && tokens.all { it is TapToken } && primary.size >= KineticaConstants.TAP_EDIT_MIN_TAPS &&
                KineticaConstants.TAP_EDIT_PEN_KW >= 0f && heap.sortedByScoreDesc().none { it.source.spelledAsTyped() }
            ) {
                Search(primary, g, prevWordId, prevWord, heap, fuzzyAnchors = false, edits = true).run()
            }
        }
        // Rescue: the lead fits its gesture poorly, which is where retyped words
        // sit (median d 0.59 against 0.25 for accepted commits), or nothing was
        // found. One more pass reads the refused gates as costs, under its own budget.
        val first = seqs.firstOrNull()
        if (first != null && first.any { it is SwipeToken } && KineticaConstants.RESCUE_MIN_LEAD_D.isFinite()) {
            val lead = heap.best()
            val p = patterns.firstOrNull()
            if (p != null && (lead == null || lead.dtwDistance > KineticaConstants.RESCUE_MIN_LEAD_D)) {
                Search(
                    p, g, prevWordId, prevWord, heap, fuzzyAnchors = false,
                    plan = streamPlan(first, p), rescue = true,
                ).run()
            }
        }
        if (!beamReads) return finish(heap.sortedByScoreDesc(), startNs)
        // The beam scores into a copy of the passes' heap, so its abandon budget starts where
        // theirs ended, and may add at most BEAM_MAX_NEW words: a better reading still leads,
        // and the passes' own list keeps its order behind it.
        val passes = heap.sortedByScoreDesc()
        val beamHeap = CandidateHeap(KineticaConstants.TOP_K)
        for (c in passes) beamHeap.offer(c)
        ThumbBeam(this, g, seqs.first(), patterns.first(), beamHeap, prevWordId, prevWord).run()
        return finish(mergeBeam(passes, beamHeap.sortedByScoreDesc()), startNs)
    }

    /**
     * The passes' list, re-scored where the beam read a word better, plus the beam's own words
     * that outscore the passes' lead, at most BEAM_MAX_NEW of them. Below the lead a beam word
     * only takes a free slot: pushing the passes' tail out loses `sarei`, a free slot holds
     * `landscape`. When the passes found nothing, the beam's list is the list.
     */
    private fun mergeBeam(passes: List<WordCandidate>, beam: List<WordCandidate>): List<WordCandidate> {
        if (passes.isEmpty()) return beam
        val known = passes.mapTo(HashSet()) { it.word }
        val lead = passes.first().score
        val out = ArrayList<WordCandidate>(KineticaConstants.TOP_K)
        var added = 0
        var filled = 0
        for (c in beam) {
            if (c.word in known) continue
            if (added < KineticaConstants.BEAM_MAX_NEW && c.score > lead) {
                out.add(c)
                added++
            } else if (passes.size + added + filled < KineticaConstants.TOP_K) {
                out.add(c)
                filled++
            }
        }
        val byWord = beam.associateBy { it.word }
        for (c in passes) out.add(byWord[c.word]?.takeIf { it.score > c.score } ?: c)
        out.sortByDescending { it.score }
        return if (out.size > KineticaConstants.TOP_K) out.subList(0, KineticaConstants.TOP_K) else out
    }

    /** The ranked list's trace lines, which every decode ends with. */
    private fun finish(out: List<WordCandidate>, startNs: Long): List<WordCandidate> {
        // Full score components per candidate: rank upsets are usually decided by fw/boost
        // arithmetic, not geometry. Every factor of score = fw * geometricTerm(d) * bm * pb * ck
        // is printed, so a captured row closes arithmetically. `bm` and `pb` are the applied
        // values, after their fit conditions (appliedBoost): `pb` reads 1.0 on a word with no
        // commits and on a reinforced word whose fit landed past GEO_SATURATION_KW, and `bm`
        // reads its attenuated value there. A `bm` or `pb` between 1.0 and its raw value shows
        // the fade firing; 1.0 past one key hop is its far end.
        //
        // `ck` reads UNCONTACTED_LETTER_KEEP once per never-touched letter, so 0.85 is one such
        // letter, 0.72 two, and 1.0 either a clean reading or a buffer with no contacts to judge
        // by, which the trace's own `keys=` settles.
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
        // The phone's own decode cost, which the JVM corpus can only bound from below.
        DecodeTrace.log { "decode time$langTag: ${"%.2f".format((System.nanoTime() - startNs) / 1e6)}ms" }
        return out
    }

    /**
     * The stream pass alone, whatever [KineticaConstants.STREAM_SEARCH_GATE] says, so a test
     * can check its own rules without the other passes finding the same words first.
     */
    internal fun streamPassOnly(tokens: List<InputToken>): List<WordCandidate> {
        val g = geometry ?: return emptyList()
        val heap = CandidateHeap(KineticaConstants.TOP_K)
        val primary = MergeAlternatives.sequences(tokens, dtw).firstOrNull() ?: return emptyList()
        val p = Matcher.buildPattern(primary, g) ?: return emptyList()
        val plan = streamPlan(primary, p) ?: return emptyList()
        Search(p, g, -1, null, heap, fuzzyAnchors = false, plan = plan).run()
        return heap.sortedByScoreDesc()
    }

    private fun streamGateOpen(heap: CandidateHeap): Boolean = when (KineticaConstants.STREAM_SEARCH_GATE) {
        1 -> heap.count < KineticaConstants.TOP_K
        3 -> true
        else -> false
    }

    /**
     * Each thumb's items in time order, for the stream pass, or null when the buffer is not
     * two thumbs with at least one swipe between them.
     */
    private fun streamPlan(primary: List<InputToken>, pattern: List<Matcher>): StreamPlan? {
        if (pattern.size != primary.size || primary.none { it is SwipeToken }) return null
        val byStream = Array(StreamId.values().size) { ArrayList<Int>() }
        for ((i, t) in primary.withIndex()) byStream[t.streamId.ordinal].add(i)
        if (byStream.any { it.isEmpty() }) return null
        return StreamPlan(
            Array(byStream.size) { s -> byStream[s].map { pattern[it] } },
            Array(byStream.size) { s -> byStream[s].map { primary[it] } },
            cutCandidates(primary),
        )
    }

    /**
     * Where the other thumb was busy inside each swipe: the times a cut is worth trying.
     *
     * The same evidence the generators draw on, the other stream's token boundaries and its
     * key-contact entries: a cut nothing in the input points at is a guess. Times land strictly
     * inside the swipe and are capped, since each one is a branch the search has to walk.
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
        is TapToken -> "tap[${alphabet.charOf(t.code)},${t.streamId},t=${t.tStart}]"
        is SwipeToken -> "swipe[${t.streamId},t=${t.tStart}..${t.tEnd}" +
            (if (t.softStart) ",softStart" else "") + (if (t.softEnd) ",softEnd" else "") +
            // Contact letters make a captured gesture reconstructible as a golden fixture;
            // without them a failing buffer's swipe paths are unknowable.
            //
            // Contact times too: letters show which keys each thumb crossed but not when, and
            // two overlapping swipes carry one event time between them, so the handovers a
            // reading needs are only here, on both streams' shared clock. Offsets are relative
            // to the token's own tStart; absolute device uptime is six digits of noise.
            (if (t.keyContacts.isEmpty()) "" else {
                t.keyContacts.joinToString(",", ",keys=") {
                    "${alphabet.charOf(it.code)}@${it.tEnter - t.tStart}-${it.tExit - t.tStart}"
                }
            }) +
            // Dwell span, peak displacement and sample count. Displacement is
            // here because times alone cannot constrain DWELL_RADIUS_KW.
            (if (t.dwells.isEmpty()) "" else {
                t.dwells.joinToString(";", ",dwell=") { d ->
                    "${d.tEnter}-${d.tExit}/${dwellSpanKw(t, d)}kw/${d.exitIdx - d.enterIdx + 1}n"
                }
            }) +
            // Arc and sampling, appended last because TraceReplay's swipe pattern
            // requires `keys=` to follow the interval, and a field inserted between them
            // silently stops every committed fixture from parsing.
            //
            // Arc is what a reconstruction destroys: a replayed buffer is a clean polyline
            // through the contacted keys, shorter than the thumb's path, and arc decides
            // minLetters and both length bands (a recorded `provando` piece fails on the
            // device at arc 3.20 kw, where the word needs one letter).
            //
            // Sample count and mean interval show whether a contact can be missed: one is recorded only
            // when a sample lands inside a different key's rect after leaving the current
            // key's inflated one (GestureStream.addPoint), so a key crossed between two
            // samples would record nothing.
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
        // Completions are pick-only: the bar may offer "the" for t,h, but a
        // delimiter keeps the typed letters.
        if (best.source == WordCandidate.Source.COMPLETION) return null
        if (best.word == literal) return null
        val confidence = 1f / (1f + best.dtwDistance)
        return if (confidence > confidenceThreshold) best else null
    }

    /**
     * The autocorrection a delimiter applies to a tapped word, or null to keep the letters.
     *
     * Only [lead] may correct, the candidate [WordComposer.merge] cleared to lead, and never
     * the head of the list. They differ in one case: the active language decoded nothing, the
     * head is a word from the other language the merge refused, and correcting to it would
     * turn Italian `conquesta` into English `conquests`. With one language they are the same
     * candidate, so single-language autocorrect is unchanged.
     */
    fun tapAutocorrect(literal: String, lead: WordCandidate?, confidenceThreshold: Float): WordCandidate? =
        autocorrectTarget(literal, listOfNotNull(lead), confidenceThreshold)

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

    /** The stream pass's input: each thumb's matchers and tokens in time order, and cut times. */
    private class StreamPlan(
        val items: Array<List<Matcher>>,
        val tokens: Array<List<InputToken>>,
        val cuts: Map<SwipeToken, LongArray>,
    )

    /** One cut: the two Segments it makes, and the tail's token so it can be cut again. */
    private class Halves(
        val head: Matcher.Segment,
        val tail: Matcher.Segment,
        val tailToken: SwipeToken,
    )

    /** How scoring one reading ended, for the calling pass's own counters. */
    internal enum class Scored { OFFERED, SCORE, IDEAL, DTW }

    /**
     * Scores the word at [node], spelled by [letters] to [depth], against [pieceCount] pieces,
     * and offers it to [heap]. Piece `pi` is `pieceSeg[pi]` drawn through letters
     * `pieceFrom[pi]` until `pieceTo[pi]` of `pieceLetters[pi]`, or of the word itself when
     * [pieceLetters] is null: the DFS reads contiguous stretches of the word, a thumb-cursor
     * search the thumb's own subsequence.
     */
    internal fun scoreReading(
        g: KeyboardGeometry,
        heap: CandidateHeap,
        prevWordId: Int,
        prevWord: String?,
        node: Int,
        letters: IntArray,
        depth: Int,
        pieceCount: Int,
        pieceSeg: Array<Matcher.Segment?>,
        pieceLetters: Array<IntArray?>?,
        pieceFrom: IntArray,
        pieceTo: IntArray,
        tapPen: Float,
        contactKeep: Float,
        source: WordCandidate.Source,
        unsaturated: Boolean = false,
    ): Scored {
        val fw = KineticaConstants.FREQ_WEIGHT_FLOOR +
            (1f - KineticaConstants.FREQ_WEIGHT_FLOOR) * trie.frequency(node) / 255f
        val bm = bigrams.multiplier(prevWordId, node)
        val variants = forms[node]
        val word: String?
        // The abandon budget must use the best score this node can still reach, every boost
        // included (word, context and pair), or a boosted word would be abandoned on its
        // unboosted numerator. So it takes the unattenuated values: the fit is not known yet,
        // and the bound has to stay optimistic. Too loose costs latency, too tight drops a
        // winner (see maxDTotalForScore); appliedBoost is <= its raw input for every
        // multiplier, which keeps this admissible.
        val maxNumerator: Float
        if (variants == null) {
            val sb = StringBuilder(depth)
            for (i in 0 until depth) sb.append(alphabet.charOf(letters[i]))
            word = sb.toString()
            maxNumerator = fw * bm * personalBoost(word) * personalBigramBoost(prevWord, word)
        } else {
            word = null
            var best = 0f
            for (v in variants) {
                val fwV = KineticaConstants.FREQ_WEIGHT_FLOOR +
                    (1f - KineticaConstants.FREQ_WEIGHT_FLOOR) * v.freqByte / 255f
                val n = fwV * personalBoost(v.display) * personalBigramBoost(prevWord, v.display)
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
            // Inverse of the score's geometric term. Past GEO_SATURATION_KW the term is flat, so
            // a node whose saturated score still clears the heap minimum has no distance bound
            // and the budget stays infinite: correctness first, latency is held by
            // MAX_EMIT_ATTEMPTS and measured by the *LatencyIsBounded goldens.
            val dMax = KineticaConstants.maxDTotalForScore(maxNumerator, minScore) - tapPen
            if (dMax <= 0f) {
                return Scored.SCORE
            }
            if (totalSteps > 0 && dMax.isFinite()) budget = dMax * totalSteps
        }

        var accum = 0f
        for (pi in 0 until pieceCount) {
            val m = pieceSeg[pi]!!
            if (!dtw.idealPath(pieceLetters?.get(pi) ?: letters, pieceFrom[pi], pieceTo[pi], g, idealScratch)) {
                return Scored.IDEAL
            }
            val d = dtw.distanceAccum(m.resampled, idealScratch, budget - accum)
            if (d == Float.POSITIVE_INFINITY) {
                return Scored.DTW
            }
            accum += d
        }
        // geoFit is the shape evidence alone; dTotal adds the tap and
        // completion penalties on top of it. The personal boost is
        // conditioned on the former, the geometric term on the latter.
        val geoFit = if (totalSteps > 0) accum / totalSteps else 0f
        val dTotal = geoFit + tapPen

        val geo = if (unsaturated) {
            KineticaConstants.geometricTermUnsaturated(dTotal)
        } else {
            KineticaConstants.geometricTerm(dTotal)
        }
        // Both boosts are weighted by geoFit through the same rule and stored
        // as applied, so a captured row closes arithmetically. The abandon
        // budget above keeps the raw values.
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
        return Scored.OFFERED
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
        /** Present only for the stream pass, which reads each thumb with its own cursor. */
        private val plan: StreamPlan? = null,
        /**
         * A rescue pass: run only when the decode fits poorly, it reads an off-radius
         * first letter and a close through any letter with a pass as charged costs, not
         * refusals, under its own step budget.
         */
        private val rescue: Boolean = false,
        /** An all-tap pass that may skip one tap or add one untapped letter, charged. */
        private val edits: Boolean = false,
    ) {
        private var editsLeft = if (edits) 1 else 0
        private val cutting = itemStart != null && srcOf != null && cuts != null
        private val streaming = plan != null
        private val cutCache = HashMap<SwipeToken, HashMap<Long, Halves?>>()
        // Tails waiting to be resumed, one per cut this branch has open. A set, not one slot:
        // the reading the cut exists for is head, other thumb, tail on both swipes at once,
        // and with one slot only one swipe per buffer could be cut, which on the corpus
        // reaches nothing the generators do not already find.
        private val pendingTail = arrayOfNulls<Matcher.Segment>(KineticaConstants.MAX_SEARCH_CUTS + 1)
        private val pendingStart = LongArray(KineticaConstants.MAX_SEARCH_CUTS + 1)

        /**
         * The tail's own token and the whole swipe it came from, per open cut.
         *
         * A tail is a SwipeToken like any other and can be cut again, which makes a third cut
         * reachable. The token is what gets split; the original keys the candidate cut times,
         * which come from the other stream's events inside the whole gesture.
         */
        private val pendingToken = arrayOfNulls<SwipeToken>(KineticaConstants.MAX_SEARCH_CUTS + 1)
        private val pendingOrigin = arrayOfNulls<SwipeToken>(KineticaConstants.MAX_SEARCH_CUTS + 1)
        private var cutsOpen = 0
        private val letters = IntArray(KineticaConstants.MAX_WORD_LEN)
        // The pieces this branch has closed, in closing order, with the letter range each
        // consumed. A piece belongs to the branch, not to a pattern slot, so the search can
        // place its own cuts. Bounded by MAX_WORD_LEN because every piece consumes a letter.
        // For a fixed pattern, emit sits at ti == pattern.size with every segment closed
        // once, so the stack holds the pattern's segments in order.
        private val pieceSeg = arrayOfNulls<Matcher.Segment>(KineticaConstants.MAX_WORD_LEN + 1)
        private val pieceFrom = IntArray(KineticaConstants.MAX_WORD_LEN + 1)
        private val pieceTo = IntArray(KineticaConstants.MAX_WORD_LEN + 1)
        private var pieceCount = 0
        private val childOrder = Array(KineticaConstants.MAX_WORD_LEN + 1) { IntArray(Alphabet.MAX_SIZE + 1) }
        private val numSegs = pattern.count { it is Matcher.Segment }

        // The fewest letters the input from each position on still has to spell: one per tap,
        // a piece's minLetters per swipe. A subtree whose longest word is shorter cannot emit,
        // so descend skips it. Cuts and pending tails only
        // add pieces, and apostrophes, doubled taps and completions only add letters, so the
        // bound never removes a reading.
        private val restMin = suffixMin(pattern)
        private val streamRestMin: Array<IntArray>? = plan?.items?.map { suffixMin(it) }?.toTypedArray()

        private fun suffixMin(items: List<Matcher>): IntArray {
            val out = IntArray(items.size + 1)
            for (j in items.size - 1 downTo 0) {
                val m = items[j]
                out[j] = out[j + 1] + if (m is Matcher.Segment) maxOf(1, m.minLetters) else 1
            }
            return out
        }

        /** Letters the rest of the input needs once the open piece holds [consumed] of its own. */
        private fun lettersStillNeeded(m: Matcher.Segment, consumed: Int, nextTi: Int): Int {
            var need = maxOf(0, m.minLetters - consumed)
            val sr = streamRestMin
            if (sr != null) {
                for (s in sr.indices) {
                    need += sr[s][minOf(sCursor[s], sr[s].size - 1)]
                    if (sParked[s] != null) need++
                }
            } else {
                need += restMin[minOf(nextTi, pattern.size)]
                for (t in pendingTail) if (t != null) need++
            }
            return need
        }
        private var emitted = 0

        // Two separate budgets:
        //   emitted:  words that reached the heap, bounded by MAX_CANDIDATES
        //             and sliced per start subtree below;
        //   attempts: emit calls, bounded by MAX_EMIT_ATTEMPTS, the DTW work
        //             the search may spend looking for those candidates.
        // One counter for both would bound walked words, and since the abandon
        // prunes reject ~94% of them, a long zigzag path could exhaust its slice
        // on words that never became candidates and lose its own word ("parlare").
        private var attempts = 0

        // Per-branch emit ceiling. MAX_CANDIDATES by default; at the pattern
        // root, descend tightens it so each admissible start-letter subtree
        // gets an even slice of the budget. Without the slice, frequency-first
        // child order lets one giant neighbour subtree (es: d-, holding "de")
        // exhaust the budget before the intended word's subtree is visited, and
        // "siempre" was unreachable on its own clean path (StartSubtreeFairnessTest).
        private var emitCap = KineticaConstants.MAX_CANDIDATES

        /** Branch budget: this subtree's candidate slice, or the global work ceiling. */
        private fun branchExhausted(): Boolean =
            emitted >= emitCap || attempts >= KineticaConstants.MAX_EMIT_ATTEMPTS || stepsExhausted()

        /** Whole-pass budget, for the sites that ignore the per-subtree slice. */
        private fun passExhausted(): Boolean =
            emitted >= KineticaConstants.MAX_CANDIDATES ||
                attempts >= KineticaConstants.MAX_EMIT_ATTEMPTS || stepsExhausted()

        // The stream pass's cost is trie walking, not DTW: the slow phone decodes ran hundreds
        // of thousands of gate checks for a handful of attempts, which the two budgets above
        // never see. So the pass also stops after a number of descend steps.
        private var steps = 0

        private fun stepsExhausted(): Boolean = when {
            rescue -> steps >= KineticaConstants.RESCUE_STEP_BUDGET ||
                attempts >= KineticaConstants.RESCUE_MAX_ATTEMPTS
            streaming -> steps >= KineticaConstants.STREAM_SEARCH_STEP_BUDGET
            else -> false
        }

        // Live completions extend only the exact all-anchor pass: a fuzzy or
        // transposed prefix is already a guess, and completing a guess would
        // dress typos up as confident-looking words.
        private val completesPrefix = numSegs == 0 && !fuzzyAnchors && basePenalty == 0f && !edits &&
            pattern.size >= KineticaConstants.COMPLETION_MIN_PREFIX

        // Emit accounting, for diagnosing decode reachability. It also exposes an open
        // property: the fairness slice exists only at a segment's first letter, so the leading
        // second letters can still consume a start subtree's whole slice before a later one is
        // visited. On the clean "parlare" path against the full it dictionary, with one shared
        // budget: attempts=533, cands=34, dtw-abandoned=499 (93.6% waste), the p- slice
        // exhausted at MAX_CANDIDATES/2, firstStop="pregate", 267 units of the global budget
        // unspent; with the two budgets, attempts=853 and stops=0.
        // Allocated and counted only while a DecodeTrace sink is attached; the
        // flag is read once, so a disabled trace costs one field test per emit.
        private val traced = DecodeTrace.enabled
        private var abandonedScore = 0
        private var abandonedIdeal = 0
        private var abandonedDtw = 0
        private var budgetStops = 0
        private var firstStopPrefix: String? = null
        private val attemptsByFirst = if (traced) IntArray(Alphabet.MAX_SIZE) else null
        private val visitsByFirst = if (traced) IntArray(Alphabet.MAX_SIZE) else null

        // Which segment gate refused a letter, and which refused a segment's
        // close. On one capture every empty decode reported attempts=0 on every
        // search line (93 decodes, 1 567 lines), so an empty decode is never the
        // DTW budget or ranking; it is these gates. Counted only under a trace
        // sink, like the arrays above.
        private var gateStart = 0      // first letter not near the path's start
        private var gatePass = 0       // no admissible pass at or after lastIdx
        private var gateBandHi = 0     // ideal length over the upper band
        private var gateMinLetters = 0 // could close but has consumed too few letters
        private var gateCloses = 0     // enough letters, but no letter may end the piece
        private var gateBandLo = 0     // closes, but the ideal is too short for the arc
        private var gateMaxLetters = 0 // segment full, cannot take another letter

        fun run() {
            if (streaming) streamNext(trie.root, 0, basePenalty, 1f) else dfs(trie.root, 0, 0, basePenalty)
            if (traced) DecodeTrace.log { searchSummary() }
        }

        // ---- the stream pass: one cursor per thumb ------------------------------------
        //
        // A reading is built letter by letter on either thumb. Each thumb keeps its own next
        // item and, after a cut, its own parked tail, so a thumb's pieces stay in its own
        // order while the other thumb is free. A piece that opens on the other thumb must
        // have its first letter no earlier than the last letter's time minus
        // HANDOVER_ALLOWANCE_MS, which is the only order the two thumbs owe each other.
        // Every piece is an ordinary Segment or Anchor, checked by descend's own rules and
        // scored by emit, so nothing here scores anything.

        private val sCursor = IntArray(StreamId.values().size)
        private val sParked = arrayOfNulls<Matcher.Segment>(StreamId.values().size)
        private val sParkedToken = arrayOfNulls<SwipeToken>(StreamId.values().size)
        private val sParkedOrigin = arrayOfNulls<SwipeToken>(StreamId.values().size)
        private var sLastT = Long.MIN_VALUE
        private var sLastStream = -1
        private var sMustSwitch = false
        private var sCutsMade = 0

        /** The first letter of the piece being opened may not be earlier than this. */
        private var openFloorT = Long.MIN_VALUE

        /**
         * The letter the open piece must close on, or -1. A head cut where one of the thumb's
         * own keys ends claims that key was its last letter, so it may close on nothing else.
         */
        private var openCloseLetter = -1

        /** When the key the head's cut leaves was entered: a letter matched after it is on that key. */
        private var openCloseEnter = Long.MAX_VALUE

        private var handbacks = 0
        private var gateHandover = 0

        private fun streamNext(node: Int, depth: Int, tapPen: Float, contactKeep: Float) {
            if (branchExhausted()) {
                if (traced) noteBudgetStop(depth)
                return
            }
            val p = plan!!
            var done = true
            for (s in sCursor.indices) {
                if (sParked[s] != null || sCursor[s] < p.items[s].size) done = false
            }
            if (done) {
                if (depth > 0 && trie.isWord(node)) emit(node, depth, tapPen, contactKeep)
                return
            }
            // The thumb whose next piece started first is tried first, so the time order is
            // walked before any reading that steps back from it.
            val first = if (nextStart(0) <= nextStart(1)) 0 else 1
            for (k in 0..1) {
                val s = if (k == 0) first else 1 - first
                if (sMustSwitch && s == sLastStream) continue
                openOn(s, node, depth, tapPen, contactKeep)
            }
        }

        private fun nextStart(s: Int): Long {
            sParkedToken[s]?.let { return it.tStart }
            val p = plan!!
            return if (sCursor[s] < p.tokens[s].size) p.tokens[s][sCursor[s]].tStart else Long.MAX_VALUE
        }

        /** Opens thumb [s]'s next piece: its parked tail if it has one, else its next item. */
        private fun openOn(s: Int, node: Int, depth: Int, tapPen: Float, contactKeep: Float) {
            val p = plan!!
            val floor = if (sLastStream >= 0 && sLastStream != s) {
                sLastT - KineticaConstants.HANDOVER_ALLOWANCE_MS
            } else {
                Long.MIN_VALUE
            }
            val parked = sParked[s]
            if (parked != null) {
                val token = sParkedToken[s]!!
                val origin = sParkedOrigin[s]!!
                sParked[s] = null
                sParkedToken[s] = null
                sParkedOrigin[s] = null
                openPiece(s, node, depth, tapPen, contactKeep, parked, token, origin, floor)
                sParked[s] = parked
                sParkedToken[s] = token
                sParkedOrigin[s] = origin
                return
            }
            val i = sCursor[s]
            if (i >= p.items[s].size) return
            sCursor[s] = i + 1
            when (val item = p.items[s][i]) {
                is Matcher.Anchor -> if (item.t >= floor) {
                    streamAnchor(node, s, item, depth, tapPen, contactKeep, allowApostrophe = true)
                } else if (traced) {
                    gateHandover++
                }
                is Matcher.Segment -> {
                    val token = p.tokens[s][i] as SwipeToken
                    openPiece(s, node, depth, tapPen, contactKeep, item, token, token, floor)
                }
            }
            sCursor[s] = i
        }

        /**
         * One piece of a swipe on thumb [s]: whole, or cut at one of [origin]'s candidate
         * times with the tail parked for the thumb's next turn.
         */
        private fun openPiece(
            s: Int,
            node: Int,
            depth: Int,
            tapPen: Float,
            contactKeep: Float,
            seg: Matcher.Segment,
            token: SwipeToken,
            origin: SwipeToken,
            floor: Long,
        ) {
            val savedFloor = openFloorT
            val savedClose = openCloseLetter
            val savedCloseEnter = openCloseEnter
            openFloorT = floor
            openCloseLetter = -1
            openCloseEnter = Long.MAX_VALUE
            descend(
                node, 0, depth,
                segStartDepth = depth, lettersInSeg = 0, idealLen = 0f,
                lastIdx = -KineticaConstants.MONOTONE_SLACK, prevLetter = -1, tapPen = tapPen,
                contactKeep = contactKeep, seg = seg, nextTi = 0,
            )
            val times = streamCutTimes(token, origin)
            val ownStart = ownFrom
            if (times.isNotEmpty() && sCutsMade < KineticaConstants.STREAM_SEARCH_MAX_CUTS) {
                for (i in times.indices) {
                    if (passExhausted()) break
                    val t = times[i]
                    if (t <= token.tStart || t >= token.tEnd) continue
                    val halves = (if (i < ownStart) cutHalves(token, t) else ownHalves(token, t)) ?: continue
                    sCutsMade++
                    sParked[s] = halves.tail
                    sParkedToken[s] = halves.tailToken
                    sParkedOrigin[s] = origin
                    val left = if (i >= ownStart && KineticaConstants.STREAM_SEARCH_OWN_CUT_CLOSES_ON_KEY) {
                        token.keyContacts.firstOrNull { it.tExit == t }
                    } else {
                        null
                    }
                    openCloseLetter = left?.code ?: -1
                    openCloseEnter = if (left != null && KineticaConstants.STREAM_SEARCH_CLOSE_INSIDE_LAST_KEY) {
                        left.tEnter
                    } else {
                        Long.MAX_VALUE
                    }
                    descend(
                        node, 0, depth,
                        segStartDepth = depth, lettersInSeg = 0, idealLen = 0f,
                        lastIdx = -KineticaConstants.MONOTONE_SLACK, prevLetter = -1, tapPen = tapPen,
                        contactKeep = contactKeep, seg = halves.head, nextTi = 0,
                    )
                    sParked[s] = null
                    sParkedToken[s] = null
                    sParkedOrigin[s] = null
                    sCutsMade--
                }
            }
            openFloorT = savedFloor
            openCloseLetter = savedClose
            openCloseEnter = savedCloseEnter
        }

        // Where the swipe's own key boundaries begin in the last list streamCutTimes built.
        private var ownFrom = 0

        /**
         * The candidate cut times for [token]: the other thumb's events inside the whole
         * swipe, then, from [ownFrom], the token's own interior key boundaries.
         */
        private fun streamCutTimes(token: SwipeToken, origin: SwipeToken): LongArray {
            val other = plan!!.cuts[origin] ?: LongArray(0)
            ownFrom = other.size
            if (!KineticaConstants.STREAM_SEARCH_OWN_CUTS) return other
            val own = ArrayList<Long>(token.keyContacts.size)
            for (c in token.keyContacts) {
                if (c.tExit > token.tStart && c.tExit < token.tEnd && c.tExit !in other) own.add(c.tExit)
            }
            return other + own.toLongArray()
        }

        private val ownCache = HashMap<SwipeToken, HashMap<Long, Halves?>>()

        /** Memoized like [cutHalves], for a cut exactly at one of the swipe's own key boundaries. */
        private fun ownHalves(src: SwipeToken, t: Long): Halves? {
            val perSwipe = ownCache.getOrPut(src) { HashMap() }
            if (perSwipe.containsKey(t)) return perSwipe[t]
            val halves = MergeAlternatives.splitAtOwnContact(src, t, dtw)
            val built = halves?.let {
                Halves(Matcher.buildSegment(it.first, g), Matcher.buildSegment(it.second, g), it.second)
            }
            perSwipe[t] = built
            return built
        }

        private fun streamAnchor(
            node: Int,
            s: Int,
            m: Matcher.Anchor,
            depth: Int,
            tapPen: Float,
            contactKeep: Float,
            allowApostrophe: Boolean,
        ) {
            if (depth >= KineticaConstants.MAX_WORD_LEN) return
            val next = trie.child(node, m.code)
            if (next != -1) {
                letters[depth] = m.code
                afterLetter(next, depth + 1, tapPen, contactKeep, s, m.t, isHead = false)
                val twice = doubledChild(next, m.code, depth + 1)
                if (twice != null) {
                    letters[depth + 1] = m.code
                    afterLetter(
                        twice, depth + 2, tapPen + KineticaConstants.DOUBLE_TAP_PEN_KW, contactKeep, s, m.t,
                        isHead = false,
                    )
                }
            }
            if (allowApostrophe) {
                // A dictionary apostrophe costs no input, as in anchorStep.
                val apo = trie.child(node, alphabet.apostrophe)
                if (apo != -1 && depth + 1 < KineticaConstants.MAX_WORD_LEN) {
                    letters[depth] = alphabet.apostrophe
                    streamAnchor(apo, s, m, depth + 1, tapPen, contactKeep, allowApostrophe = false)
                }
            }
        }

        /**
         * A piece or a tap on thumb [s] has just ended with a letter at time [t]: record it
         * as the reading's latest letter and go on to the next piece.
         */
        private fun afterLetter(
            node: Int,
            depth: Int,
            tapPen: Float,
            contactKeep: Float,
            s: Int,
            t: Long,
            isHead: Boolean,
        ) {
            val savedT = sLastT
            val savedStream = sLastStream
            val savedSwitch = sMustSwitch
            if (sLastStream >= 0 && sLastStream != s && t < sLastT) handbacks++
            sLastT = t
            sLastStream = s
            // A head must hand over: reading its own tail next would be the unsplit swipe
            // read in two pieces, a cheaper fit for rivals and never a new reading.
            sMustSwitch = isHead
            streamNext(node, depth, tapPen, contactKeep)
            sLastT = savedT
            sLastStream = savedStream
            sMustSwitch = savedSwitch
        }


        private fun prefixOf(depth: Int): String {
            val sb = StringBuilder(depth)
            for (i in 0 until depth) sb.append(alphabet.charOf(letters[i]))
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
                ?.joinToString(",") { "${alphabet.charOf(it.index)}:${it.value}" }
                ?: ""
            val label = (if (rescue) "rescue " else "") + if (streaming) {
                "stream[segs=$numSegs,anchors=${pattern.size - numSegs},handbacks=$handbacks," +
                    "refusedHandover=$gateHandover] "
            } else {
                "search[segs=$numSegs,anchors=${pattern.size - numSegs}," +
                    (if (fuzzyAnchors) "fuzzy" else "exact") + "] "
            }
            return label +
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
            // A swipe cut open earlier resumes as soon as nothing else started before it, the
            // ordering `MergeAlternatives.orderByTime` applies to a finished sequence. Doing it
            // here lets the search place the cut: the readings worth finding are head, other
            // thumb, tail.
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
                // And the tail may itself be cut, the only way to reach a third piece of one
                // swipe. It closes back to ti like the tail it replaces.
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
                // The last letter was never tapped.
                if (editsLeft > 0 && depth > 0 && depth < KineticaConstants.MAX_WORD_LEN) {
                    val first = trie.firstChild(node)
                    for (i in 0 until trie.childCount(node)) {
                        val child = first + i
                        val code = trie.letter(child)
                        if (code == alphabet.apostrophe || !trie.isWord(child)) continue
                        letters[depth] = code
                        emit(child, depth + 1, tapPen + KineticaConstants.TAP_EDIT_PEN_KW, contactKeep)
                    }
                }
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
         * The cut is chosen here, before any letter is assigned, so each half is validated
         * against its own geometry by the ordinary gates. A head is a softEnd piece and a tail
         * a softStart one, as the split generators produce, and nothing downstream can tell
         * where a piece came from.
         *
         * Bounded three ways, because this multiplies the search: only the primary pattern
         * cuts, only [KineticaConstants.MAX_SEARCH_CUTS] cuts may be open at once, and the
         * candidate times are the other stream's own events, where the generators look too.
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
         * finished sequence. A tail cut before the next token started is consumed first;
         * a token starting before or at the cut goes in between ([tailResumesFirst]).
         */
        private fun earliestPending(ti: Int): Int {
            var best = -1
            for (i in 0 until pendingTail.size) {
                val t = pendingTail[i] ?: continue
                if (best < 0 || pendingStart[i] < pendingStart[best]) best = i
            }
            if (best < 0) return -1
            if (ti < pattern.size && !tailResumesFirst(itemStart!![ti], pendingStart[best])) return -1
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
            if (editsLeft > 0) {
                val pen = tapPen + KineticaConstants.TAP_EDIT_PEN_KW
                editsLeft--
                // This tap was a stray: the word goes on from the next one.
                dfs(node, ti + 1, depth, pen, contactKeep)
                // A letter before this tap was never tapped.
                if (depth + 1 < KineticaConstants.MAX_WORD_LEN) {
                    val first = trie.firstChild(node)
                    for (i in 0 until trie.childCount(node)) {
                        val child = first + i
                        val code = trie.letter(child)
                        if (code == alphabet.apostrophe) continue
                        letters[depth] = code
                        anchorStep(child, ti, depth + 1, pen, contactKeep, allowApostrophe)
                    }
                }
                editsLeft++
            }
            if (!fuzzyAnchors) {
                tryAnchor(node, m.code, 0f, ti, depth, tapPen, contactKeep)
            } else {
                for (code in 0 until alphabet.letterCount) {
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
                val apo = trie.child(node, alphabet.apostrophe)
                if (apo != -1 && depth + 1 < KineticaConstants.MAX_WORD_LEN) {
                    letters[depth] = alphabet.apostrophe
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
            val twice = doubledChild(next, code, depth + 1) ?: return
            letters[depth + 1] = code
            dfs(twice, ti + 1, depth + 2, tapPen + penalty + KineticaConstants.DOUBLE_TAP_PEN_KW, contactKeep)
        }

        /**
         * The node one tap reaches when it stands for a doubled letter, `tt` in `ottima` from a
         * single `t`: the child of [node] with [code] again, or null when the allowance is off or
         * the word does not double here.
         */
        private fun doubledChild(node: Int, code: Int, depth: Int): Int? {
            if (KineticaConstants.DOUBLE_TAP_PEN_KW < 0f || depth >= KineticaConstants.MAX_WORD_LEN) return null
            val twice = trie.child(node, code)
            return if (twice == -1) null else twice
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
            // The piece being consumed and where to go when it closes. A resumed half is
            // consumed at the pattern position it interrupts, so it closes back to the same
            // index, not past it.
            seg: Matcher.Segment,
            nextTi: Int,
        ) {
            if (branchExhausted()) {
                if (traced) noteBudgetStop(depth)
                return
            }
            if (depth >= KineticaConstants.MAX_WORD_LEN) return
            if (traced && depth > 0) visitsByFirst!![letters[0]]++
            if (streaming || rescue) steps++
            val m = seg
            val count = orderChildrenByFreq(node, depth)
            if (count == 0) return
            val order = childOrder[depth]
            val shortest = if (KineticaConstants.LENGTH_BOUND_PRUNE) {
                depth + 1 + lettersStillNeeded(m, lettersInSeg + 1, nextTi)
            } else {
                0
            }

            // Root fairness: the key nearest the path's start point is the strongest geometric
            // signal, so its subtree is explored first with half the emit budget (deep words
            // behind a flood of higher-frequency siblings, "cuñado" behind con-/co-, need room).
            // The other admissible starts share the rest, each capped at an even rollover slice
            // so no giant subtree (es: d-, holding "de") exhausts the budget before a later start
            // letter is visited.
            val rootFairness = ti == 0 && depth == 0 && lettersInSeg == 0
            var remainingStarts = 0
            if (rootFairness) {
                for (i in 0 until count) {
                    val code = trie.letter(order[i])
                    if (code == alphabet.apostrophe || !g.hasKey(code)) continue
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
                    if (code == alphabet.apostrophe || !g.hasKey(code)) continue
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
                if (code != alphabet.apostrophe && trie.maxWordLen(child) < shortest) continue

                if (code == alphabet.apostrophe) {
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
                var startKeep = 1f
                if (lettersInSeg == 0) {
                    // A normal segment's first letter must sit near the path's
                    // start point. A split second half (softStart) resumes
                    // mid-word after a rest, so its first letter may be any key
                    // the resumed path passes near; the peak-trimmed resume
                    // usually starts on it, and this covers straight resumes
                    // that fell back to the fixed trim.
                    if (m.softStart) {
                        if (pass == -1) {
                            if (traced) gatePass++
                            continue
                        }
                    } else if (!m.isStart(code)) {
                        if (rescue && pass != -1) {
                            startKeep = KineticaConstants.RESCUE_GATE_KEEP
                        } else {
                            if (traced) gateStart++
                            continue
                        }
                    }
                    // The first letter of a piece on the other thumb may step back from the
                    // last letter by HANDOVER_ALLOWANCE_MS and no further.
                    if (streaming && openFloorT != Long.MIN_VALUE &&
                        m.letterTime(code, if (pass >= 0) pass else 0) < openFloorT
                    ) {
                        if (traced) gateHandover++
                        continue
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
                // the finger was never on. m.contacted is empty for a token that carries
                // no contacts, which charges nothing: a missing contact list is no
                // evidence either way.
                val keep = if (m.contacted.isEmpty() || m.contacted[code]) {
                    contactKeep
                } else {
                    contactKeep * KineticaConstants.UNCONTACTED_LETTER_KEEP
                }
                // A split first half (softEnd) ends at the cut sample, which is
                // mid-travel whenever the interrupted thumb was moving; its
                // real last letter can sit far behind the cut point, so any
                // letter with a pass on the path may close it. The length band
                // below still polices closings, so mid-path junk stays blocked.
                val closesStrict = m.isEnd(code) || (m.softEnd && pass >= 0)
                val closes = closesStrict || (rescue && pass >= 0)
                val k = keep * startKeep
                val closeKeep = if (closesStrict) k else k * KineticaConstants.RESCUE_GATE_KEEP
                // Lower band reads letterArcLen, not arcLen: on a softStart
                // piece the lead-in travel is not evidence that more letters
                // were spelled (Matcher.buildSegment).
                // The three reasons a close is refused are counted separately
                // because they need different fixes, in order of increasing
                // evidence: too few letters to be a piece, then no letter that
                // may end the piece, then a reading too short for the arc
                // travelled. Refusals count once per letter reached, the same
                // denominator attemptsByFirst uses.
                val bandLoOk = len2 >= KineticaConstants.LEN_BAND_LO * m.letterArcLen -
                    KineticaConstants.LEN_BAND_MARGIN_KW
                val closeLetterOk = !streaming || openCloseLetter < 0 || code == openCloseLetter ||
                    m.sampleT[(if (pass >= 0) pass else maxOf(lastIdx, 0)).coerceIn(0, m.sampleT.size - 1)] >= openCloseEnter
                if (consumed >= m.minLetters && closes && bandLoOk && closeLetterOk) {
                    pieceSeg[pieceCount] = m
                    pieceFrom[pieceCount] = segStartDepth
                    pieceTo[pieceCount] = depth + 1
                    pieceCount++
                    if (streaming) {
                        val s = m.stream.ordinal
                        val closeIdx = if (pass >= 0) pass else maxOf(lastIdx, 0)
                        afterLetter(child, depth + 1, tapPen, closeKeep, s, m.letterTime(code, closeIdx), sParked[s] != null)
                    } else {
                        dfs(child, nextTi, depth + 1, tapPen, closeKeep)
                    }
                    pieceCount--
                } else if (traced) {
                    when {
                        consumed < m.minLetters -> gateMinLetters++
                        !closes || !closeLetterOk -> gateCloses++
                        else -> gateBandLo++
                    }
                }
                if (consumed >= m.maxLetters && traced) gateMaxLetters++
                if (consumed < m.maxLetters) {
                    descend(
                        child, ti, depth + 1, segStartDepth, consumed, len2,
                        if (pass >= 0) maxOf(lastIdx, pass) else lastIdx, code, tapPen, k,
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
            // candidate is charged below, only once this word survives every
            // abandon prune and reaches the heap.
            attempts++
            if (traced) attemptsByFirst!![letters[0]]++
            val source = when {
                completion -> WordCandidate.Source.COMPLETION
                rescue -> WordCandidate.Source.RESCUE
                fuzzyAnchors || basePenalty > 0f || edits -> WordCandidate.Source.FUZZY_TAP
                numSegs == 0 -> WordCandidate.Source.EXACT_TAP
                pattern.size == 1 -> WordCandidate.Source.SWIPE
                else -> WordCandidate.Source.MERGED
            }
            val outcome = scoreReading(
                g, heap, prevWordId, prevWord, node, letters, depth,
                pieceCount, pieceSeg, null, pieceFrom, pieceTo, tapPen, contactKeep, source,
            )
            when (outcome) {
                Scored.OFFERED -> emitted++
                Scored.SCORE -> if (traced) abandonedScore++
                Scored.IDEAL -> if (traced) abandonedIdeal++
                Scored.DTW -> if (traced) abandonedDtw++
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

    fun best(): WordCandidate? = items.maxByOrNull { it.score }
}

/**
 * Whether a swipe tail cut at [cutAt] resumes before the next pattern item, which starts at
 * [nextStart]. An item starting at the cut goes first, as in `MergeAlternatives.orderByTime`:
 * cuts are offered at other tokens' own starts, so a tie is the commonest case, and reading
 * the tail first there spells the swipe whole with the other thumb after it.
 */
internal fun tailResumesFirst(nextStart: Long, cutAt: Long): Boolean = nextStart > cutAt

/**
 * One line naming, per swipe piece, the keys its path came within R_INNER_KW of but never
 * touched, or null when there are none, so a quiet buffer costs no line.
 *
 * 45% of labelled buffers are a gesture that never contacts some letter of its own word, and
 * the contact list alone cannot tell a crossing hysteresis dropped from a corner the thumb
 * cut. A path rebuilt through the contact centres cannot settle it either: every neighbour of
 * a touched key sits about 1.0 kw from such a polyline whether the thumb went there or not.
 * `nearPath` is computed from the real resampled path, so here it can. `[-]` marks a piece
 * carrying no contacts, which is no evidence of a miss.
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
        // Closest approach per uncontacted key, nearest first. The distance is what matters:
        // nearPath alone means "within R_INNER_KW = 1.8 kw", nearly two key widths either side
        // of the stroke, and names 4 to 15 keys per piece, median 7 or 8. With a distance,
        // a missed contact can be told from scenery: a key the path came within a fraction of a key
        // width of without recording is a crossing hysteresis dropped; one at 1.5 kw is scenery.
        val near = ArrayList<Pair<Int, Float>>(4)
        for (code in 0 until g.letterCount) {
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
            sb.append(g.alphabet.charOf(e.first))
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
 * Per-key near-miss lists saturate (128 of 152 full at a cap of six) and measure how crowded a
 * QWERTY neighbourhood is. The question is about the letters of a named word, and the
 * developer names it by retyping until it commits, so the label arrives free at commit time.
 * 254 of 570 labelled buffers never touch some letter of their own word, 158 of them one.
 *
 *  - Per occurrence, not per key: `praticamente` needs `a` twice, and a per-key list
 *    misses the second because `a` was contacted once.
 *  - In order: each occurrence is credited only to a contact at or after the previous
 *    occurrence's, so a letter the thumb reached only too early is marked, not credited (in
 *    that `praticamente` the left thumb held `t` at 256 ms and reached `a` only at 607).
 *
 * Distances come off the real resampled path: measured through contact centres, all 414
 * never-contacted letter instances read "near the path" at a median 1.00 kw.
 *
 * Format, one token per letter occurrence: `<letter><piece>:<kw>@<index>` for the closest
 * the path ever came, plus a mark: `!` a contact at or after the previous occurrence's,
 * `=` the second of a doubled letter sharing one contact, `<` contacted but only earlier
 * than this occurrence needs, and nothing for a letter no thumb ever touched.
 * `note=seeded` or `note=unrelated` flags a line whose buffer is not the word's gesture.
 *
 * `missing=` counts letters no thumb touched (75% of the inadmissible bucket) and `back=`
 * letters touched in the wrong order, most of the rest. Neither needs a threshold, so the
 * order mark is contact-based: with 32 resample points some index is always left to match
 * against, and a distance test would never fire.
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
    // Contacts carry a time and the Matcher's `contacted` array does not: it is one boolean
    // per piece, so it cannot say whether a letter was touched before or after the point a
    // reading needs it. So the order comes off this timeline and the distance off the path,
    // kept separate: measuring distance over the path remaining after the previous letter
    // put 146 of 1 506 contacted letters over 1.8 kw, impossible for a key the finger was
    // on. The distance is the closest the path ever came.
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
        val code = g.alphabet.codeOf(ch)
        if (code !in 0 until g.letterCount || !g.hasKey(code)) continue
        val hit = closest(pattern, g, code) ?: continue
        counted++
        letters.append(' ').append(ch).append(hit.piece).append(':')
        letters.append((hit.dist * 100).toInt() / 100f).append('@').append(hit.idx)
        val at = (cursor until timeline.size).firstOrNull { timeline[it].second == code }
        when {
            at != null -> { letters.append('!'); cursor = at + 1 }
            // A doubled letter is drawn with one contact, so the second occurrence has no
            // later contact to claim and is not an order violation; on one capture 78 of 431
            // `<` marks were this case.
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
    // A commit whose buffer is not this word's gesture (a picked suggestion, a completion,
    // or the synthetic reload that seeds anchors from committed text) reports every letter
    // missing and would otherwise count as evidence about a thumb.
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
 * one `uptimeMillis` call, not from real touches.
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
                val touched = m.contacted.isNotEmpty() && m.contacted[code]
                if (bestI >= 0) out = better(out, Approach(p, bestI, bestD, touched))
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

/** The typed letters are a word or the start of one, so nothing was mistyped. */
private fun WordCandidate.Source.spelledAsTyped(): Boolean =
    this == WordCandidate.Source.EXACT_TAP || this == WordCandidate.Source.COMPLETION
