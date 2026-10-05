package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.TapToken
import com.kinetica.keyboard.engine.models.WordCandidate
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
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
    /**
     * Where the second language decodes, beside the first, or null to decode it after the first
     * on the decode thread. Single-threaded, so the alternate predictor has one owner as the
     * active one does.
     */
    private val alternateExecutor: ExecutorService? = null,
    /** Where a third language decodes, with no primary language: its own thread, as above. */
    private val extraExecutor: ExecutorService? = null,
) {
    interface Callbacks {
        /**
         * Fresh candidates for the current word, ranked across every enabled
         * language and each tagged with the dictionary it came from. [literal]
         * is the exact tap string when every token is a tap, else empty.
         *
         * [tentative] is the one candidate that may auto-commit: [candidates]
         * first when non-null, and null when nothing has earned the editor (an
         * undecodable gesture, or a word only a non-active language explains,
         * see [merge]). A null tentative is the stale-tentative path,
         * not an empty bar: the candidates are still shown
         * and pickable, so the languages are ranked together, not swapped.
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
     * At most one decode worker is queued at a time. Fast taps could otherwise
     * queue a full dictionary decode per letter faster than the decode thread
     * consumes them, each stale before it starts and each delaying the one
     * result the user sees.
     *
     * The pending slot is latest-wins: a running worker finishes its current
     * predictor call (WordPredictor is thread-confined and not interruptible),
     * then jumps to the newest snapshot, so obsolete work is bounded to one
     * in-flight active-language decode. The generation checks also skip the
     * second-language decode once the first has gone stale.
     */
    private data class DecodeRequest(
        val tokens: List<InputToken>,
        val context: List<String>,
        val generation: Int,
        val literal: String,
        val alternate: WordPredictor?,
        val extra: WordPredictor? = null,
        val weights: Map<String, Float>? = null,
    )

    private val decodeLock = Any()
    private var pendingDecode: DecodeRequest? = null
    private var decodeWorkerScheduled = false
    private val decodeWorker = Runnable {
        try {
            drainDecodes()
        } finally {
            // A predictor or main-executor failure must not leave the composer
            // "scheduled" forever. If input arrived while the failed worker ran,
            // hand that snapshot to a fresh executor task; otherwise reopen
            // scheduling for the next token.
            val reschedule = synchronized(decodeLock) {
                decodeWorkerScheduled = false
                if (pendingDecode != null) {
                    decodeWorkerScheduled = true
                    true
                } else {
                    false
                }
            }
            if (reschedule) submitDecodeWorker()
        }
    }

    /**
     * Second enabled language: when set, every word also decodes against it and
     * both lists are ranked into one (see [merge]). Main thread writes, decode
     * thread reads.
     */
    @Volatile
    var alternatePredictor: WordPredictor? = null

    /** A third resident language, used only with no primary language. */
    @Volatile
    var extraPredictor: WordPredictor? = null

    /**
     * Language weights (LanguageMomentum), set only with no primary language (the opt-in).
     * Non-null switches a swipe to [equalFootingMerge]: every resident language decodes with the
     * beam and one ranking weighs each word by its language. Main thread writes a fresh map, the
     * decode thread reads it.
     */
    @Volatile
    var languageWeights: Map<String, Float>? = null

    val hasPendingWord: Boolean get() = tokens.isNotEmpty()
    val tokenCount: Int get() = tokens.size

    fun hasSwipeToken(): Boolean = tokens.any { it !is TapToken }

    fun onToken(token: InputToken) {
        tokens.add(token)
        requestDecode()
    }

    /**
     * Seeds the buffer from already-committed text: when backspace edits into
     * a committed word, its remaining characters return as exact tap anchors,
     * so later tokens continue that word instead of starting a fragment and
     * the eventual commit carries the whole word into personal weighting.
     */
    fun seed(seedTokens: List<InputToken>) {
        tokens.clear()
        tokens.addAll(seedTokens)
        requestDecode()
    }

    fun literal(): String = buildLiteral(tokens)

    /** Word committed to the editor: becomes bigram context, buffer resets. */
    fun commitWord(word: String) {
        if (DecodeTrace.enabled) traceCommitMiss(word)
        previousBuffer = if (tokens.isEmpty()) previousBuffer else ArrayList(tokens)
        context.addLast(word)
        while (context.size > 2) context.removeFirst()
        tokens.clear()
        generation.incrementAndGet()
    }

    /**
     * The buffer before the one being committed. A failed attempt followed by a retype is
     * the pairing the labelled corpus is built from, so the word eventually committed labels
     * the buffer that failed in [commitMissLine]. Main thread only, like [tokens].
     */
    private var previousBuffer: List<InputToken> = emptyList()

    /**
     * Emits the commit-time miss line for the committing buffer and the one before it, off
     * the decode thread. The committing buffer is the control, the previous one a failure
     * with a label. The gap between them is printed and only loosely bounded by
     * MAX_RETYPE_GAP_MS: a retype after a wrong word took 1 456 and 1 651 ms against a
     * 170 ms median typing gap, so the reader tells a real pairing from an unrelated one.
     */
    private fun traceCommitMiss(word: String) {
        val committed = ArrayList(tokens)
        val previous = previousBuffer
        decodeExecutor.execute {
            val g = predictor.geometry ?: return@execute
            emitCommitMiss(word, committed, g, "commit", -1L)
            val gap = gapBetween(previous, committed)
            if (gap in 0..KineticaConstants.MAX_RETYPE_GAP_MS) emitCommitMiss(word, previous, g, "previous", gap)
        }
    }

    private fun emitCommitMiss(
        word: String,
        buffer: List<InputToken>,
        g: KeyboardGeometry,
        src: String,
        gapMs: Long,
    ) {
        if (buffer.isEmpty()) return
        val sorted = buffer.sortedBy { it.tStart }
        val pattern = Matcher.buildPattern(sorted, g) ?: return
        val line = commitMissLine(word, sorted, pattern, g, src, gapMs) ?: return
        DecodeTrace.log { line }
    }

    /** Milliseconds between the end of [before] and the start of [after], or -1. */
    private fun gapBetween(before: List<InputToken>, after: List<InputToken>): Long {
        val end = before.maxOfOrNull { it.tEnd } ?: return -1L
        val start = after.minOfOrNull { it.tStart } ?: return -1L
        return start - end
    }

    /**
     * Makes [prev] the word the next commit follows, when the editor says so and the
     * composer's memory does not: a prediction can be picked after a cursor move.
     */
    fun anchorContext(prev: String) {
        val p = prev.lowercase()
        if (context.lastOrNull() == p) return
        context.clear()
        context.addLast(p)
    }

    /** The correction strip swapped the last committed word. */
    fun replaceLastCommit(word: String) {
        if (context.isNotEmpty()) context.removeLast()
        context.addLast(word)
    }

    /**
     * The recent-words bar swapped the commit [back] words before the last (0 is the last),
     * where the context still holds [old].
     */
    fun replaceCommit(back: Int, old: String, word: String) {
        val i = context.size - 1 - back
        if (i in context.indices && context[i] == old) context[i] = word
    }

    /** Abandon the pending word (cursor moved, field changed, backspace). */
    fun clear() {
        // Kept for the commit-time miss line: this is the buffer a retype is about to
        // replace, and the retyped word is its label.
        if (tokens.isNotEmpty()) previousBuffer = ArrayList(tokens)
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
        val request = DecodeRequest(
            tokens = snapshot,
            context = context.toList(),
            generation = generation.incrementAndGet(),
            literal = buildLiteral(snapshot),
            alternate = alternatePredictor,
            extra = extraPredictor,
            weights = languageWeights,
        )
        val scheduleWorker = synchronized(decodeLock) {
            pendingDecode = request
            if (decodeWorkerScheduled) {
                false
            } else {
                decodeWorkerScheduled = true
                true
            }
        }
        if (scheduleWorker) submitDecodeWorker()
    }

    private fun submitDecodeWorker() {
        try {
            decodeExecutor.execute(decodeWorker)
        } catch (e: RuntimeException) {
            synchronized(decodeLock) { decodeWorkerScheduled = false }
            throw e
        }
    }

    private fun drainDecodes() {
        while (true) {
            val request = synchronized(decodeLock) {
                pendingDecode?.also { pendingDecode = null }
            } ?: return
            // A clear/commit or newer token can supersede a request before its
            // queued worker starts. Do not spend any dictionary work on it.
            if (request.generation != generation.get()) continue

            val alternate = request.alternate
            val weights = request.weights
            if (weights != null && alternate != null && request.literal.isEmpty()) {
                val merged = equalFootingDecode(request, alternate, weights) ?: continue
                deliver(request, rescueHeld(merged))
                continue
            }
            // The second language decodes beside the first, so a bilingual buffer waits for the
            // slower decode, not both in turn: in sequence a buffer took 95 ms at p99 against 58
            // per decode.
            val beside = if (alternate != null && alternateExecutor != null) {
                alternateExecutor.submit(
                    Callable {
                        if (request.generation != generation.get()) {
                            null
                        } else {
                            DecodeTrace.holding { alternate.decode(request.tokens, request.context, beam = false) }
                        }
                    },
                )
            } else {
                null
            }
            val active = predictor.decode(request.tokens, request.context)
            // If input advanced during the active-language pass, drop the second-language pass
            // and immediately drain the newest snapshot instead.
            if (request.generation != generation.get()) {
                beside?.cancel(false)
                continue
            }

            // Tapped words merge too: skipped, a tapped English word with
            // Polish active was never found and was learned into Polish.
            // tapLeadAllowed and WordPredictor.tapAutocorrect keep autocorrect sound.
            val merged = if (alternate != null) {
                val other = if (beside != null) {
                    val done = awaitBeside(beside) ?: continue
                    DecodeTrace.write(done.second)
                    done.first
                } else {
                    alternate.decode(request.tokens, request.context, beam = false)
                }
                if (request.generation != generation.get()) continue
                merge(active, other, tapOnly = request.literal.isNotEmpty()).also { m ->
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
            }.let { rescueHeld(it) }
            deliver(request, merged)
        }
    }

    private fun deliver(request: DecodeRequest, merged: Merged) {
        mainExecutor.execute {
            if (request.generation == generation.get()) {
                callbacks.onCandidates(
                    merged.candidates, merged.tentative,
                    request.literal, request.generation,
                )
            }
        }
    }

    /**
     * No primary language: every resident decodes with the beam, each beside the others, and
     * [equalFootingMerge] ranks them as one. Null when the request went stale on the way.
     */
    private fun equalFootingDecode(request: DecodeRequest, alternate: WordPredictor, weights: Map<String, Float>): Merged? {
        fun besideOn(executor: ExecutorService?, p: WordPredictor?): Future<Pair<List<WordCandidate>, List<String>>?>? {
            if (p == null || executor == null) return null
            return executor.submit(
                Callable {
                    if (request.generation != generation.get()) null
                    else DecodeTrace.holding { p.decode(request.tokens, request.context, beam = true) }
                },
            )
        }
        val second = besideOn(alternateExecutor, alternate)
        val third = besideOn(extraExecutor, request.extra)
        val active = predictor.decode(request.tokens, request.context)
        if (request.generation != generation.get()) {
            second?.cancel(false)
            third?.cancel(false)
            return null
        }
        val lists = ArrayList<List<WordCandidate>>(3)
        lists.add(active)
        if (second != null) {
            val done = awaitBeside(second) ?: return null
            DecodeTrace.write(done.second)
            lists.add(done.first)
        } else {
            lists.add(alternate.decode(request.tokens, request.context, beam = true))
        }
        if (third != null) {
            val done = awaitBeside(third) ?: return null
            DecodeTrace.write(done.second)
            lists.add(done.first)
        }
        if (request.generation != generation.get()) return null
        return equalFootingMerge(lists, weights).also { m ->
            DecodeTrace.log {
                "merge equal w=${weights.entries.joinToString(",") { "${it.key}:${"%.2f".format(it.value)}" }} -> " +
                    "lead=${m.tentative?.word ?: "<none>"}[${m.tentative?.language ?: "-"}] ${m.reason}"
            }
        }
    }

    /**
     * The second language's decode and its held trace lines, or null when it was skipped as
     * stale or interrupted. A decode that threw rethrows here, on the decode thread, as it
     * did when both ran there.
     */
    private fun <T> awaitBeside(f: Future<T?>): T? = try {
        f.get()
    } catch (e: CancellationException) {
        null
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } catch (e: ExecutionException) {
        throw (e.cause as? RuntimeException) ?: IllegalStateException(e.cause)
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
     * Ranks the active and other language's candidates into one list.
     *
     * A whole-list swap asked which language a word is in and could not answer: on device
     * geometry the same-language and foreign confidence ratios overlap in [1.000, 1.095], and a
     * wrong answer discarded the right word. Ranking together needs no such answer. Scores are
     * comparable across the bundled dictionaries (Trie.freqByteFor normalises each asset to its
     * own maximum; see the cross-language note in [KineticaConstants]), so no cross-dictionary
     * normalisation is applied.
     *
     * Two provenance rules, neither a tuned threshold:
     *
     * 1. A candidate of the other language whose exact spelling the active dictionary holds is
     *    dropped. A shared word carries no cross-language information, and keeping it would
     *    re-rank an active word by foreign frequency (`sergei` is in both lists and used to beat
     *    `sarei`). The dedup below is the defensive remainder.
     * 2. A foreign candidate may lead, becoming the tentative a delimiter commits, only on
     *    positive geometric evidence:
     *      a. the active language produced a candidate: an empty active decode says the gesture
     *         was undecodable, not which language it was in, and `patéale` came from there. With
     *         none, a swiped fit under [KineticaConstants.NO_NATIVE_LEAD_KW] still leads;
     *      b. its own fit carries information, `dtwDistance < GEO_SATURATION_KW`, the score's
     *         own bound: past it a d=0.6 and a d=1.5 match both say "not the shape you drew";
     *      c. it fits strictly better than the active language's best fit. An equal fit is not
     *         evidence: `interesante` (es) and `interessante` (it) both decode at d=0.000, since
     *         a doubled letter shares its ideal path (DtwMatcherTest), and the Italian word
     *         would lose its own gesture to a 0.8% frequency difference. Free on 110 device rows;
     *      d. for a tapped word ([tapOnly]), it is at least as frequent as the active lead; see
     *         [tapLeadAllowed].
     *
     * A demoted foreign candidate stays in the list and stays pickable, so a wrong lead is
     * recoverable. On 110 language decisions replayed from device captures 12 top-1 change, all
     * foreign words the swap missed (`ayudarte` x5, `mujer` x3, `cuando` x2, `nosotros`), and no
     * other row moves.
     *
     * Internal so the decision stays unit-testable with captured candidate tuples
     * (LanguagePreferenceTest).
     */
    internal fun merge(
        active: List<WordCandidate>,
        other: List<WordCandidate>,
        tapOnly: Boolean = false,
    ): Merged {
        // Rule 1, and the fast path: two Romance dictionaries share most of their top candidates,
        // so this usually empties the foreign list. Exact spelling: English holds `e`, not Italian
        // `è`, which a folded lookup dropped as shared. No lead moves by it (0 of 8 872 captured
        // leads), since an equal fit keeps the active word.
        val foreign = other.filter { !predictor.holdsSpelling(it.word) }
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
        // Rule 2. Both operands come from the full active decode, not the ranked window: a
        // strong foreign candidate can push every active word past TOP_K, and a clean `ciudad`
        // (Spanish d=0.000, Italian's best 0.795) then looked like an empty active decode and
        // committed nothing.
        val activeFit = active.minOfOrNull { it.dtwDistance }
        if (activeFit == null) {
            // Nothing from the active language to compare against. A tight fit from the other
            // is still the word drawn (`understanding` under Italian); a loose one is the
            // `patéale` case, and a tapped word keeps its letters.
            return if (!tapOnly && head.dtwDistance < KineticaConstants.NO_NATIVE_LEAD_KW) {
                Merged(ranked, head, foreign.size, "no-native-lead")
            } else {
                Merged(ranked, null, foreign.size, "no-native")
            }
        }
        if (head.dtwDistance < KineticaConstants.GEO_SATURATION_KW &&
            head.dtwDistance < activeFit &&
            (!tapOnly || tapLeadAllowed(head, active.first()))
        ) {
            return Merged(ranked, head, foreign.size, "foreign-lead")
        }
        // Demote: the list keeps score order behind a native lead, so the bar's first zone is
        // the word a delimiter would commit. The lead is the highest-scoring active candidate,
        // which differs from the best fit above when frequency and geometry disagree, put back
        // at the front if the window crowded it out.
        val bestActive = ranked.firstOrNull { it.language == predictor.language }
            ?: active.first()
        val demoted = ArrayList<WordCandidate>(ranked.size + 1)
        demoted.add(bestActive)
        for (c in ranked) if (c !== bestActive) demoted.add(c)
        val why = when {
            head.dtwDistance >= KineticaConstants.GEO_SATURATION_KW -> "demoted-past-cap"
            head.dtwDistance < activeFit -> "demoted-tap-rarer"
            else -> "demoted-no-better-fit"
        }
        return Merged(demoted.take(KineticaConstants.TOP_K), bestActive, foreign.size, why)
    }

    /**
     * No primary language (the opt-in): one ranking over every resident language's list, each
     * word's score times its language's flow weight, and the head leads whatever its language.
     * Labelled foreign rows go 83 -> 210 of 487, native 214 -> 210 and shared 263 -> 250, but
     * 286 of 4 527 captured leads change, so it is not the default.
     */
    internal fun equalFootingMerge(lists: List<List<WordCandidate>>, weights: Map<String, Float>): Merged {
        val seen = HashSet<String>()
        val ranked = lists.flatten()
            .sortedByDescending { it.score * (weights[it.language] ?: 1f) }
            .filter { seen.add(it.word) }
            .take(KineticaConstants.TOP_K)
        return Merged(ranked, ranked.firstOrNull(), ranked.count { it.language != predictor.language }, "equal")
    }

    /**
     * Whether another language's [head] may lead a tapped word over the active language's
     * [activeLead]: only when it is at least as frequent, on top of rule c's better fit.
     *
     * Frequency weight, not score: score charges the active reading its tap penalty and the
     * frequency floor lifts a rank-40 000 word within reach, so English `maa` beat Italian
     * `ama`. Over 2 510 captured tapped buffers rule c alone changed 20 commits, none meant in
     * the other language (`comun` to `comin`); with this, none. English `add` (rank 1 767)
     * still leads Polish `asd` (33 860).
     */
    private fun tapLeadAllowed(head: WordCandidate, activeLead: WordCandidate): Boolean =
        head.frequencyWeight >= activeLead.frequencyWeight

    private fun buildLiteral(list: List<InputToken>): String {
        if (list.isEmpty() || list.any { it !is TapToken }) return ""
        val sorted = list.sortedBy { it.tStart }
        val sb = StringBuilder(sorted.size)
        for (t in sorted) sb.append(predictor.alphabet.charOf((t as TapToken).code))
        return sb.toString()
    }
}

/**
 * A list the rescue pass alone produced goes to the bar and not the editor. Of 46
 * labelled buffers it rescued from nothing, 10 led with the word, so a tap is cheaper than a
 * retype on the other 36.
 */
internal fun rescueHeld(m: WordComposer.Merged): WordComposer.Merged {
    if (m.tentative == null || m.candidates.any { it.source != WordCandidate.Source.RESCUE }) return m
    DecodeTrace.log { "rescue pick-only n=${m.candidates.size} first=${m.candidates.first().word}" }
    return m.copy(tentative = null, reason = "rescue-only")
}
