package com.kinetica.keyboard.ime

import android.content.Context
import android.os.Handler
import android.util.Log
import com.kinetica.keyboard.data.DictionaryStore
import com.kinetica.keyboard.data.KineticaDb
import com.kinetica.keyboard.data.UserBigram
import com.kinetica.keyboard.data.UserWord
import com.kinetica.keyboard.engine.Alphabet
import com.kinetica.keyboard.engine.DecodeTrace
import com.kinetica.keyboard.engine.DictionaryLoader
import com.kinetica.keyboard.engine.KeyboardGeometry
import com.kinetica.keyboard.engine.KineticaConstants
import com.kinetica.keyboard.engine.WordPredictor
import com.kinetica.keyboard.settings.KeyboardConfig
import com.kinetica.keyboard.settings.LanguageMode
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException

/**
 * The dictionaries resident for decoding and the personal data each one ranks with: the active
 * language, the auto-detect second, and a third with no primary language.
 *
 * Loaded off the main thread and installed on it. The decode threads read the predictors, and the
 * maps through them, so the maps are concurrent and the main thread is their only writer.
 */
internal class ResidentDictionaries(
    private val context: Context,
    private val mainHandler: Handler,
    private val dbExecutor: Executor,
) {

    // Populated asynchronously once the dictionary is parsed.
    @Volatile
    var predictor: WordPredictor? = null
        private set

    // Other-language predictor for the experimental per-word auto-detect;
    // null unless the setting is on and a second language is enabled.
    var secondaryPredictor: WordPredictor? = null
        private set

    // Personal commit counts, mirrored from user_words. Main thread writes, the decode thread
    // reads through the predictor: concurrent map. One map per resident predictor, because
    // user_words and the boost are keyed by language.
    private var personalCounts = ConcurrentHashMap<String, Int>()
    // Learned word pairs, keyed "prev\u0000next", same concurrent contract as the counts
    // above. Empty and never written unless the phrase setting is on.
    private var personalPairs = ConcurrentHashMap<String, Int>()
    private var secondaryPairs = ConcurrentHashMap<String, Int>()
    private var secondaryCounts = ConcurrentHashMap<String, Int>()
    var secondaryLanguage: String? = null
        private set

    // The third resident, only with no primary language (LanguageMode.FOLLOW). Same contract as the
    // secondary fields above.
    var extraPredictor: WordPredictor? = null
        private set
    private var extraCounts = ConcurrentHashMap<String, Int>()
    private var extraPairs = ConcurrentHashMap<String, Int>()
    var extraLanguage: String? = null
        private set

    /**
     * Every blocked spelling, any language, lowercased. The trie never holds one, but the typed
     * letters, the correction strip, the recent columns and the mid-word history are kept
     * strings, and a blocked word could come back through them (`anb` for `and`).
     */
    @Volatile var blockedSpellings: Set<String> = emptySet()

    // Each language's learned capitals, lowercase word -> capital and votes, mirrored from
    // user_words at load and kept by learnWord, so a commit knows a capital it has learned.
    private val learnedCases = ConcurrentHashMap<String, ConcurrentHashMap<String, DictionaryLoader.LearnedCase>>()

    // A parsed load waiting for the word in progress to end. Main thread only.
    private val heldInstall = HeldInstall()
    val holdsInstall: Boolean get() = heldInstall.isHeld

    // Count changes since each load's rows were read. Main thread only.
    private val learnJournal = LearnJournal()

    /** A count change a load in flight has not read; its install replays it. Main thread. */
    fun noteLearn(lang: String, word: String, amount: Int) = learnJournal.note(lang, word, amount)

    /** The journal's token for a learn now; see [correctLearn]. Main thread. */
    fun learnMark(): Int = learnJournal.mark()

    /** A learn's live count read [gap] short of its row; true when the live map needs it. Main thread. */
    fun correctLearn(mark: Int, lang: String, word: String, gap: Int): Boolean =
        learnJournal.correct(mark, lang, word, gap)

    /**
     * Loads the active language and the residents beside it, and installs them on the main
     * thread; [onReady] then gets the active predictor. [config] is read where the load reads
     * it: the languages now, the phrase and spelling settings on the load thread, the language
     * again on install, since the user may have switched it mid-parse. While [wordPending] the
     * install waits for [releaseInstall]. [onStale] asks another load: a word learned mid-load
     * crossed the merge floor after its rows were read.
     */
    fun load(
        config: () -> KeyboardConfig,
        geometry: () -> KeyboardGeometry?,
        wordPending: () -> Boolean,
        onStale: () -> Unit,
        onReady: (WordPredictor) -> Unit,
    ) {
        val lang = config().language
        // Auto-detect is pairwise: one resident secondaryPredictor, so with 3+ enabled
        // languages only the first non-active one participates (ADDING_A_LANGUAGE.md).
        // Lifting it costs a resident predictor per language and an N-way vote in WordComposer.
        // Same script only: another script's words cannot be drawn on this board, and its
        // codes would read this board's keys as its own letters.
        val sameScript = config().enabledLanguages.filter { it != lang && Alphabet.forLanguage(it) == Alphabet.forLanguage(lang) }
        val mode = config().languageMode
        val detectLang = if (mode != LanguageMode.ONE) sameScript.firstOrNull() else null
        // With no primary language, a third resident: the next enabled one in the same script.
        val extraLang = if (mode == LanguageMode.FOLLOW && KineticaConstants.MAX_RESIDENT_LANGUAGES > 2) sameScript.getOrNull(1) else null
        // The rows are read on the database thread, behind every learn already queued there; a
        // learn from now on goes into the journal, which the install replays over these rows.
        val journal = learnJournal.open()
        val phrases = config().learnPhrases
        val rowsRead = FutureTask {
            listOfNotNull(lang, detectLang, extraLang).associateWith { readUserRows(it, phrases) }
        }
        try {
            dbExecutor.execute(rowsRead)
        } catch (e: RejectedExecutionException) {
            // The service is shutting down: nothing will install.
            learnJournal.take(journal)
            return
        }
        Thread({
            try {
                val read = rowsRead.get()
                // Learned words merge into the trie at load, gated and scaled by
                // PERSONAL_MERGE_MIN_COUNT and USER_FREQ_SCALE (rationale in KineticaConstants).
                val userRows = read.getValue(lang).words
                val userWords =
                    DictionaryLoader.userWordsForMerge(userRows.map { it.word to it.frequency })
                val pairRows = read.getValue(lang).pairs
                // Words the user has blocked never reach the trie, so they
                // cannot be decoded, completed or suggested.
                val blocked = blockedWords(lang)
                val allBlocked = allBlockedWords()
                val swaps = spellingSwaps(lang, config())
                val dict = openWordlist(lang).bufferedReader().use {
                    DictionaryLoader.load(it, userWords, blocked, swaps, Alphabet.forLanguage(lang), casesOf(userRows))
                }
                val bigrams = context.assets.open(bigramsAsset(lang)).bufferedReader().use {
                    DictionaryLoader.loadBigrams(it, dict.trie)
                }
                // The other enabled language stays resident so swipe decodes consult both
                // dictionaries, with its own personal counts: both lists rank together, and
                // one-sided weighting favoured the active language by up to 1.83x on device.
                var altRows: List<UserWord> = emptyList()
                var altPairRows: List<UserBigram> = emptyList()
                val alt = detectLang?.let { other ->
                    try {
                        altRows = read.getValue(other).words
                        altPairRows = read.getValue(other).pairs
                        val d = openWordlist(other).bufferedReader().use {
                            DictionaryLoader.load(
                                it,
                                DictionaryLoader.userWordsForMerge(
                                    altRows.map { r -> r.word to r.frequency },
                                ),
                                blockedWords(other),
                                spellingSwaps(other, config()),
                                Alphabet.forLanguage(other),
                                casesOf(altRows),
                            )
                        }
                        val b = context.assets.open(bigramsAsset(other)).bufferedReader().use {
                            DictionaryLoader.loadBigrams(it, d.trie)
                        }
                        d to b
                    } catch (e: IOException) {
                        Log.w(KineticaIME.TAG, "secondary dictionary load failed for $other", e)
                        null
                    }
                }
                var extraRows: List<UserWord> = emptyList()
                var extraPairRows: List<UserBigram> = emptyList()
                val extra = extraLang?.let { other ->
                    try {
                        extraRows = read.getValue(other).words
                        extraPairRows = read.getValue(other).pairs
                        val d = openWordlist(other).bufferedReader().use {
                            DictionaryLoader.load(
                                it,
                                DictionaryLoader.userWordsForMerge(extraRows.map { r -> r.word to r.frequency }),
                                blockedWords(other),
                                spellingSwaps(other, config()),
                                Alphabet.forLanguage(other),
                                casesOf(extraRows),
                            )
                        }
                        val b = context.assets.open(bigramsAsset(other)).bufferedReader().use {
                            DictionaryLoader.loadBigrams(it, d.trie)
                        }
                        d to b
                    } catch (e: IOException) {
                        Log.w(KineticaIME.TAG, "third dictionary load failed for $other", e)
                        null
                    }
                }
                mainHandler.post {
                    // Two parses race to the main thread: an older one landing after a newer one
                    // would put back older rows, an older trie and an older blocked set.
                    if (!learnJournal.supersede(journal)) {
                        DecodeTrace.log { "  userdict install dropped lang=$lang superseded" }
                        return@post
                    }
                    blockedSpellings = allBlocked
                    val held = heldInstall.offer(wordPending()) install@{
                        val learns = learnJournal.take(journal) ?: return@install
                        // The user may have toggled languages again mid-parse, or while it was held.
                        if (lang != config().language) return@install
                        val stale = ArrayList<String>()
                        val counts = ConcurrentHashMap<String, Int>(userRows.size * 2)
                        for (row in userRows) counts[row.word] = row.frequency
                        personalCounts = counts
                        learnedCases[lang] = ConcurrentHashMap(casesOf(userRows))
                        detectLang?.let { learnedCases[it] = ConcurrentHashMap(casesOf(altRows)) }
                        extraLang?.let { learnedCases[it] = ConcurrentHashMap(casesOf(extraRows)) }
                        val pairs = pairMap(pairRows)
                        personalPairs = pairs
                        val p = WordPredictor(
                            dict.trie, bigrams, geometry(), dict.forms, counts, pairs, lang, dict.learned,
                        )
                        // Before any decode reads them: no composer holds these predictors yet. By
                        // spelling, as askUserDictReload: a new accented form of a held key is not merged.
                        stale += replayLearns(counts, lang, learns, p::holdsSpelling)
                        predictor = p
                        val altCounts = ConcurrentHashMap<String, Int>(altRows.size * 2)
                        for (row in altRows) altCounts[row.word] = row.frequency
                        secondaryCounts = altCounts
                        val altPairs = pairMap(altPairRows)
                        secondaryPairs = altPairs
                        secondaryLanguage = detectLang
                        // WordComposer.merge tells the two lists apart by the language stamp.
                        secondaryPredictor = alt?.let { (d, b) ->
                            WordPredictor(
                                d.trie, b, geometry(), d.forms, altCounts, altPairs,
                                language = detectLang ?: "", learnedOnly = d.learned,
                            ).also { s -> stale += replayLearns(altCounts, s.language, learns, s::holdsSpelling) }
                        }
                        val xCounts = ConcurrentHashMap<String, Int>(extraRows.size * 2)
                        for (row in extraRows) xCounts[row.word] = row.frequency
                        extraCounts = xCounts
                        val xPairs = pairMap(extraPairRows)
                        extraPairs = xPairs
                        extraLanguage = if (extra != null) extraLang else null
                        extraPredictor = extra?.let { (d, b) ->
                            WordPredictor(d.trie, b, geometry(), d.forms, xCounts, xPairs, language = extraLang ?: "", learnedOnly = d.learned)
                                .also { x -> stale += replayLearns(xCounts, x.language, learns, x::holdsSpelling) }
                        }
                        learnJournal.installed(journal)
                        onReady(p)
                        for ((l, r) in read) DecodeTrace.log { "  userdict rows lang=$l ${r.tally}" }
                        if (learns.isNotEmpty()) DecodeTrace.log { "  userdict install replay learns=${learns.size}" }
                        if (stale.isNotEmpty()) {
                            DecodeTrace.log { "  userdict stale words=${stale.joinToString(",")} src=install" }
                            onStale()
                        }
                        Log.i(
                            KineticaIME.TAG,
                            "dictionary ready [$lang]: ${dict.trie.wordCount} words, " +
                                "${bigrams.size} bigrams, ${dict.forms.size} display forms" +
                                (detectLang?.let { d -> ", auto-detect vs $d" } ?: "") +
                                (extraLanguage?.let { x -> " and $x" } ?: "") +
                                (if (config().languageMode == LanguageMode.FOLLOW) ", no primary" else ""),
                        )
                    }
                    if (held) DecodeTrace.log { "  userdict install held lang=$lang" }
                }
            } catch (e: IOException) {
                Log.e(KineticaIME.TAG, "dictionary load failed for $lang", e)
            } catch (e: ExecutionException) {
                Log.e(KineticaIME.TAG, "dictionary load failed for $lang", e)
            } catch (e: InterruptedException) {
                Log.e(KineticaIME.TAG, "dictionary load interrupted for $lang", e)
            } catch (e: RuntimeException) {
                // On its own thread an exception kills the keyboard; the dictionary already
                // loaded keeps serving instead.
                Log.e(KineticaIME.TAG, "dictionary load failed for $lang", e)
            }
        }, "kinetica-dict-load").start()
    }

    /** Runs the held install once no word is [wordPending]. */
    fun releaseInstall(wordPending: Boolean) {
        if (heldInstall.release(wordPending)) DecodeTrace.log { "  userdict install run" }
    }

    /** Every resident predictor follows the board on screen. */
    fun setGeometry(geometry: KeyboardGeometry) {
        predictor?.geometry = geometry
        secondaryPredictor?.geometry = geometry
        extraPredictor?.geometry = geometry
    }

    /** The resident languages in load order: [active], second, third. */
    fun languages(active: String): List<String> =
        listOfNotNull(active, secondaryLanguage?.takeIf { secondaryPredictor != null }, extraLanguage)

    /** The predictor serving [lang]: the active one, the second or the third. */
    fun predictorFor(lang: String, active: String): WordPredictor? = when {
        lang == secondaryLanguage && lang != active -> secondaryPredictor
        lang == extraLanguage && lang != active -> extraPredictor
        else -> predictor
    }

    /** Live count map backing [lang]'s predictor; the active one by default. */
    fun countsFor(lang: String, active: String): ConcurrentHashMap<String, Int> = when {
        lang == secondaryLanguage && lang != active -> secondaryCounts
        lang == extraLanguage && lang != active -> extraCounts
        else -> personalCounts
    }

    /** Live pair map backing [lang]'s predictor; the active one by default. */
    fun pairsFor(lang: String, active: String): ConcurrentHashMap<String, Int> = when {
        lang == secondaryLanguage && lang != active -> secondaryPairs
        lang == extraLanguage && lang != active -> extraPairs
        else -> personalPairs
    }

    fun casesFor(lang: String): ConcurrentHashMap<String, DictionaryLoader.LearnedCase> =
        learnedCases.getOrPut(lang) { ConcurrentHashMap() }

    /**
     * The resident languages whose lexicon holds [word], each with its frequency byte there.
     * Empty with one language resident, which leaves every filing to provenance. [listsOnly]
     * leaves out a spelling only the user's learning put in a trie.
     */
    fun heldBy(word: String, active: String, listsOnly: Boolean = false): Map<String, Int> {
        val second = secondaryPredictor ?: return emptyMap()
        val secondLang = secondaryLanguage ?: return emptyMap()
        val out = HashMap<String, Int>(3)
        // Held means this exact spelling: `è` is Italian's, not English's through its `e`.
        fun held(p: WordPredictor, lang: String) {
            val holds = if (listsOnly) p.holdsListSpelling(word) else p.holdsSpelling(word)
            if (holds) p.frequencyByte(word).takeIf { it >= 0 }?.let { out[lang] = it }
        }
        predictor?.let { held(it, active) }
        held(second, secondLang)
        val third = extraPredictor
        val thirdLang = extraLanguage
        if (third != null && thirdLang != null) held(third, thirdLang)
        return out
    }

    /**
     * Spelling pairs to exchange for [lang], empty unless the user asked for British spelling
     * and this is English. Both spellings are already in the trie, so only the two counts
     * trade places.
     */
    private fun spellingSwaps(lang: String, config: KeyboardConfig): Map<String, String> {
        if (lang != "en" || !config.britishSpelling) return emptyMap()
        return try {
            context.assets.open("dictionaries/en_gb_variants.txt").bufferedReader().use {
                DictionaryLoader.loadSpellingSwaps(it)
            }
        } catch (e: java.io.IOException) {
            Log.w(KineticaIME.TAG, "British spelling variants asset unavailable", e)
            emptyMap()
        }
    }

    private fun allBlockedWords(): Set<String> = try {
        KineticaDb.get(context).blockedWords().allWords().mapTo(HashSet()) { it.lowercase() }
    } catch (e: RuntimeException) {
        Log.w(KineticaIME.TAG, "blocked words unavailable", e)
        emptySet()
    }

    /**
     * Blocked spellings for [lang], lower-cased to match the loader's test. An unavailable
     * table is an empty block list, not a failed load: the keyboard has to come up either way.
     */
    private fun blockedWords(lang: String): Set<String> = try {
        KineticaDb.get(context).blockedWords().wordsForLanguage(lang)
            .mapTo(HashSet()) { it.lowercase() }
    } catch (e: RuntimeException) {
        Log.w(KineticaIME.TAG, "blocked words unavailable for $lang", e)
        emptySet()
    }

    /** Imported (AOSP-merged) wordlist overrides the bundled asset. */
    private fun openWordlist(lang: String): java.io.InputStream {
        val override = DictionaryStore.wordlistOverride(context, lang)
        return if (override.exists()) override.inputStream() else context.assets.open(wordlistAsset(lang))
    }

    private fun wordlistAsset(lang: String) = "dictionaries/${lang}_wordlist.txt"
    private fun bigramsAsset(lang: String) = "dictionaries/${lang}_bigrams.txt"

    /** Learned pairs for [lang], or none when the store is unavailable. */
    private fun userBigramRows(lang: String): List<UserBigram> = try {
        KineticaDb.get(context).userBigrams().topN(lang, USER_PAIR_LIMIT)
    } catch (e: RuntimeException) {
        Log.w(KineticaIME.TAG, "phrase store unavailable for $lang", e)
        emptyList()
    }

    private fun pairMap(rows: List<UserBigram>): ConcurrentHashMap<String, Int> {
        val out = ConcurrentHashMap<String, Int>(rows.size * 2 + 1)
        // Folded keys, so two spellings of one pair add up instead of overwriting.
        for (r in rows) out.merge(WordPredictor.pairKey(r.prev, r.next), r.count, Int::plus)
        return out
    }

    private fun userRowsFor(lang: String): UserRows {
        val dao = KineticaDb.get(context).userWords()
        val top = dao.topN(lang, USER_DICT_LIMIT)
        // Under the limit the top rows are the whole list.
        if (top.size < USER_DICT_LIMIT) return UserRows(top, emptyList(), "loaded=${top.size}")
        val capitals = dao.withCapitals(lang, USER_DICT_LIMIT)
        val mergeable = dao.mergeable(lang, KineticaConstants.PERSONAL_MERGE_MIN_COUNT, USER_MERGE_LIMIT)
        val words = loadedUserRows(top, capitals, mergeable)
        return UserRows(
            words, emptyList(),
            "loaded=${words.size} top=${top.size} mergeable=${mergeable.size} capitals=${capitals.size}",
        )
    }

    /** One list's rows, and [tally], how many each read returned, for the trace. */
    private class UserRows(val words: List<UserWord>, val pairs: List<UserBigram>, val tally: String)

    /** [lang]'s learned words and, with [phrases], pairs. An unavailable store is no rows. */
    private fun readUserRows(lang: String, phrases: Boolean): UserRows {
        val read = try {
            userRowsFor(lang)
        } catch (e: RuntimeException) {
            Log.w(KineticaIME.TAG, "user dictionary unavailable for $lang", e)
            UserRows(emptyList(), emptyList(), "unavailable")
        }
        return UserRows(read.words, if (phrases) userBigramRows(lang) else emptyList(), read.tally)
    }

    private fun casesOf(rows: List<UserWord>): Map<String, DictionaryLoader.LearnedCase> =
        DictionaryLoader.learnedCases(rows.map { Triple(it.word, it.display, it.caseVotes) })

    private companion object {
        /** The most used rows a load reads, for their counts; the reads below add what it drops. */
        const val USER_DICT_LIMIT = 5000
        const val USER_PAIR_LIMIT = 5000

        /**
         * The rows over the merge floor a load reads past [USER_DICT_LIMIT]. 20 000 new words parse
         * in the time of none on the JVM, 0.9 MB more trie; 50 000 take 60% longer. Bundled words
         * are skipped at the merge, so only a word the lists lack grows the trie.
         */
        const val USER_MERGE_LIMIT = 20_000
    }
}

/**
 * The user rows a load reads: the [top] counts, then every row with a learned capital, then every
 * row over the merge floor ([mergeable]) they miss.
 *
 * The install rebuilds the capital mirror and the live counts from these rows. Past the limit, a
 * word learned this week sits behind thousands of older ones at its count: its settled capital was
 * dropped, and a word over the floor never reached the trie, its count read as none.
 */
internal fun loadedUserRows(
    top: List<UserWord>,
    capitals: List<UserWord>,
    mergeable: List<UserWord> = emptyList(),
): List<UserWord> {
    if (capitals.isEmpty() && mergeable.isEmpty()) return top
    val have = top.mapTo(HashSet()) { it.word }
    val out = ArrayList<UserWord>(top.size + capitals.size + mergeable.size)
    out.addAll(top)
    for (r in capitals) if (have.add(r.word)) out.add(r)
    for (r in mergeable) if (have.add(r.word)) out.add(r)
    return out
}
