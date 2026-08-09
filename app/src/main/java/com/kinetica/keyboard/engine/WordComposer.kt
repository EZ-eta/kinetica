package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.TapToken
import com.kinetica.keyboard.engine.models.WordCandidate
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * Buffers the tokens of the word in progress, re-decodes the *full* token list
 * after every finalized token (so candidates refine gesture by gesture), and
 * keeps the two-word commit context for bigram boosting.
 *
 * Threading: tokens are owned by the main thread; each decode runs on
 * [decodeExecutor] over an immutable snapshot stamped with a generation
 * counter, and stale results (a newer token arrived while decoding) are
 * dropped on the way back through [mainExecutor].
 */
class WordComposer(
    private val predictor: WordPredictor,
    private val decodeExecutor: Executor,
    private val mainExecutor: Executor,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        /**
         * Fresh candidates for the current word, ranked across every enabled
         * language and each tagged with the dictionary it came from. [literal]
         * is the exact tap string when every token is a tap, else empty.
         *
         * [tentative] is the one candidate that may auto-commit - always
         * [candidates] first when it is non-null, and null when nothing here
         * has earned the editor: an undecodable gesture, or a word only a
         * non-active language can explain (see [merge]). A null tentative is
         * the bug-1 stale-tentative path, NOT an empty bar: the candidates are
         * still shown and still pickable, which is the whole point of ranking
         * the languages together instead of swapping between them.
         *
         * Main thread.
         */
        fun onCandidates(
            candidates: List<WordCandidate>,
            tentative: WordCandidate?,
            literal: String,
            generation: Int,
        )
    }

    private val tokens = ArrayList<InputToken>()
    private val context = ArrayDeque<String>()
    private val generation = AtomicInteger()

    /**
     * Second enabled language: when set, swipe-bearing words also decode
     * against it and BOTH lists are ranked together into one (see [merge]).
     * Main thread writes, decode thread reads.
     */
    @Volatile
    var alternatePredictor: WordPredictor? = null

    val hasPendingWord: Boolean get() = tokens.isNotEmpty()
    val tokenCount: Int get() = tokens.size

    fun hasSwipeToken(): Boolean = tokens.any { it !is TapToken }

    fun onToken(token: InputToken) {
        tokens.add(token)
        requestDecode()
    }

    /**
     * Seeds the buffer from already-committed text: when backspace edits into
     * a committed word, its remaining characters return as exact tap anchors
     * so subsequent tokens continue that word instead of starting a fresh
     * fragment - and the eventual commit carries the whole word into the
     * personal weighting, not a stub.
     */
    fun seed(seedTokens: List<InputToken>) {
        tokens.clear()
        tokens.addAll(seedTokens)
        requestDecode()
    }

    fun literal(): String = buildLiteral(tokens)

    /** Word committed to the editor: becomes bigram context, buffer resets. */
    fun commitWord(word: String) {
        context.addLast(word)
        while (context.size > 2) context.removeFirst()
        tokens.clear()
        generation.incrementAndGet()
    }

    /** The correction strip swapped the last committed word. */
    fun replaceLastCommit(word: String) {
        if (context.isNotEmpty()) context.removeLast()
        context.addLast(word)
    }

    /** Abandon the pending word (cursor moved, field changed, backspace). */
    fun clear() {
        tokens.clear()
        generation.incrementAndGet()
    }

    /** Also forget commit context (new input field). */
    fun reset() {
        clear()
        context.clear()
    }

    fun contextSnapshot(): List<String> = context.toList()

    private fun requestDecode() {
        val snapshot = ArrayList(tokens)
        val ctx = context.toList()
        val gen = generation.incrementAndGet()
        val literal = buildLiteral(snapshot)
        val alternate = alternatePredictor
        decodeExecutor.execute {
            val active = predictor.decode(snapshot, ctx)
            // Cross-language ranking applies to swipe-bearing words only (empty
            // literal): all-tap words feed autocorrect, whose isWord semantics
            // are tied to the active language.
            val merged = if (alternate != null && literal.isEmpty()) {
                val other = alternate.decode(snapshot, ctx)
                merge(active, other).also { m ->
                    DecodeTrace.log {
                        val a = active.firstOrNull()
                        val o = other.firstOrNull()
                        val t = m.tentative
                        "merge ${predictor.language}=${a?.word}(d=${a?.dtwDistance}) " +
                            "${alternate.language}=${o?.word}(d=${o?.dtwDistance}) " +
                            "shared=${other.size - m.foreignKept} -> " +
                            "lead=${t?.word ?: "<none>"}[${t?.language ?: "-"}] ${m.reason}"
                    }
                }
            } else {
                Merged(active, active.firstOrNull(), foreignKept = 0, reason = "single")
            }
            mainExecutor.execute {
                if (gen == generation.get()) {
                    callbacks.onCandidates(merged.candidates, merged.tentative, literal, gen)
                }
            }
        }
    }

    /**
     * One ranked list plus the candidate allowed to auto-commit.
     * [foreignKept] and [reason] exist for DecodeTrace and the goldens.
     */
    internal data class Merged(
        val candidates: List<WordCandidate>,
        val tentative: WordCandidate?,
        val foreignKept: Int,
        val reason: String,
    )

    /**
     * Ranks the active and other language's candidates into ONE list.
     *
     * This replaces an earlier whole-list swap. That design asked "which
     * language is this word?" and answered it from a confidence ratio, and
     * measurement showed the question to be unanswerable: on
     * real device geometry the same-language and foreign ratio populations
     * OVERLAP in [1.000, 1.095], so no threshold separates them (it detected 2
     * of 10 real foreign words). Worse, a wrong answer was unrecoverable - the
     * active language's candidates were discarded, so the right word was not
     * even pickable and the word had to be retyped by hand.
     *
     * Ranking them together needs no such answer, and no threshold. Scores are
     * already comparable across the bundled dictionaries: Trie.freqByteFor
     * normalizes each asset against its OWN maximum count, and all three
     * assets are the same construction (FrequencyWords top-50k), so fw at
     * matched rank percentiles agrees to within 1.02-1.04x - worth about
     * 0.015 kw of distance against a geometric term that moves 1.335x between
     * d=0.25 and d=0.35. No cross-dictionary frequency normalization is
     * applied because none is needed, and any that were would have to be a
     * per-language MULTIPLICATIVE constant: score is a product, so a
     * percentile or z-score remap reorders candidates WITHIN a language and
     * would move the geometric-term goldens.
     *
     * Two provenance rules, neither of them a tuned threshold:
     *
     * 1. A candidate of the other language whose word the ACTIVE dictionary
     *    already holds is DROPPED. This is the isWord veto applied per
     *    candidate instead of to the list head: a shared word carries no
     *    cross-language information, and keeping it would re-rank an
     *    active-language word by foreign frequency ("sergei" is in both
     *    wordlists, and importing the Spanish entry is exactly how the swap
     *    used to lose the intended "sarei"). In production it also removes
     *    every duplicate, since a predictor can only emit words its own trie
     *    holds; the dedup below is the defensive remainder.
     * 2. A foreign candidate may LEAD - i.e. become the tentative the editor
     *    shows and a delimiter commits - only on positive geometric evidence,
     *    which is three conditions and no threshold of its own:
     *      a. the active language produced at least one candidate. An empty
     *         active decode is evidence the gesture was undecodable, not
     *         evidence about language - this is the rule that stops a
     *         hopeless decode committing a foreign word, and it is where
     *         "patéale" used to come from;
     *      b. its own fit still carries geometric information,
     *         `dtwDistance < GEO_SATURATION_KW`. The bound is the score's
     *         own, reused rather than invented: past the cap a d=0.6 and a d=1.5 match are
     *         both "this is not the shape you drew";
     *      c. it fits STRICTLY better than the active language's best fit -
     *         a like-with-like comparison, applied to promotion. An
     *         equal fit is not evidence: "interesante" (es) and
     *         "interessante" (it) decode at exactly d=0.000 on the same path,
     *         because a doubled letter shares its ideal path after
     *         consecutive-duplicate dedup (DtwMatcherTest), so without this
     *         clause the flagship Italian word loses its own gesture to a 0.8%
     *         frequency difference. The same class as "rese"/"reese", across
     *         languages. Measured free: the 110 device rows produce the same
     *         12 promotions with and without it.
     *
     * Geometry is the ONLY evidence that a word belongs to another language,
     * so a foreign candidate that offers none has nothing to promote it.
     *
     * A demoted foreign candidate is not removed, only overtaken - it stays in
     * the list and stays pickable, which is what makes a wrong lead
     * recoverable rather than fatal.
     *
     * Measured on 110 language decisions replayed from device captures: 12
     * top-1 changes, all 12 of them detection misses of the old gate being
     * fixed (ayudarte x5, cuando x2, mujer x3,
     * nosotros), and no other row moves. Rule 2 suppresses exactly the rows
     * where both languages are past the cap and the contest is pure frequency
     * ("juntos" at d=1.7 over "leonard"), plus the two empty-Italian "parlare"
     * decodes that used to commit "patéale".
     *
     * Internal rather than private so the decision stays unit-testable with the
     * captured candidate tuples (LanguagePreferenceTest).
     */
    internal fun merge(
        active: List<WordCandidate>,
        other: List<WordCandidate>,
    ): Merged {
        // Rule 1. Also the fast path: two Romance dictionaries share most of
        // their top candidates, so this usually empties the foreign list.
        val foreign = other.filter { !predictor.isWord(it.word) }
        if (foreign.isEmpty()) {
            return Merged(active, active.firstOrNull(), 0, "no-foreign")
        }
        // Best score wins per word, matching CandidateHeap.offer's own dedup.
        val seen = HashSet<String>(active.size + foreign.size)
        val ranked = (active + foreign)
            .sortedByDescending { it.score }
            .filter { seen.add(it.word) }
            .take(KineticaConstants.TOP_K)
        val head = ranked.firstOrNull()
            ?: return Merged(emptyList(), null, foreign.size, "empty")
        if (head.language == predictor.language) {
            return Merged(ranked, head, foreign.size, "native-lead")
        }
        // Rule 2. Both operands come from the FULL active decode, not from the
        // ranked window: a strong foreign candidate can push every active word
        // past TOP_K, and being crowded out of the bar is not the same as
        // having nothing to say. Reading either off the window made a clean
        // "ciudad" - Spanish at d=0.000, Italian's best at 0.795 - look like an
        // empty active decode and refuse to commit anything at all.
        val activeFit = active.minOfOrNull { it.dtwDistance }
            ?: return Merged(ranked, null, foreign.size, "no-native")
        if (head.dtwDistance < KineticaConstants.GEO_SATURATION_KW &&
            head.dtwDistance < activeFit
        ) {
            return Merged(ranked, head, foreign.size, "foreign-lead")
        }
        // Demote: the list keeps score order behind a native lead, so the bar's
        // first zone is always the word a delimiter would commit. The lead is
        // the highest-SCORING active candidate - a different word from the best
        // FIT above whenever frequency and geometry disagree - reinstated at the front if
        // the window had crowded it out.
        val bestActive = ranked.firstOrNull { it.language == predictor.language }
            ?: active.first()
        val demoted = ArrayList<WordCandidate>(ranked.size + 1)
        demoted.add(bestActive)
        for (c in ranked) if (c !== bestActive) demoted.add(c)
        val why = if (head.dtwDistance >= KineticaConstants.GEO_SATURATION_KW) {
            "demoted-past-cap"
        } else {
            "demoted-no-better-fit"
        }
        return Merged(demoted.take(KineticaConstants.TOP_K), bestActive, foreign.size, why)
    }

    private fun buildLiteral(list: List<InputToken>): String {
        if (list.isEmpty() || list.any { it !is TapToken }) return ""
        val sorted = list.sortedBy { it.tStart }
        val sb = StringBuilder(sorted.size)
        for (t in sorted) sb.append(Alphabet.charOf((t as TapToken).code))
        return sb.toString()
    }
}
