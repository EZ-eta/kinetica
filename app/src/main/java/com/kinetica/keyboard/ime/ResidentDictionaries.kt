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
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The dictionaries resident for decoding and the personal data each one ranks with: the active
 * language, the auto-detect second, and a third with no primary language.
 *
 * Loaded off the main thread and installed on it. The decode threads read the predictors, and the
 * maps through them, so the maps are concurrent and the main thread is their only writer.
 */
internal class ResidentDictionaries(private val context: Context, private val mainHandler: Handler) {

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

    // The third resident, only with no primary language (Prefs.NO_PRIMARY). Same contract as the
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

    /**
     * Loads the active language and the residents beside it, and installs them on the main
     * thread; [onReady] then gets the active predictor. [config] is read where the load reads
     * it: the languages now, the phrase and spelling settings on the load thread, the language
     * again on install, since the user may have switched it mid-parse. While [wordPending] the
     * install waits for [releaseInstall].
     */
    fun load(
        config: () -> KeyboardConfig,
        geometry: () -> KeyboardGeometry?,
        wordPending: () -> Boolean,
        onReady: (WordPredictor) -> Unit,
    ) {
        val lang = config().language
        // Auto-detect is pairwise: one resident secondaryPredictor, so with 3+ enabled
        // languages only the first non-active one participates (ADDING_A_LANGUAGE.md).
        // Lifting it costs a resident predictor per language and an N-way vote in WordComposer.
        // Same script only: another script's words cannot be drawn on this board, and its
        // codes would read this board's keys as its own letters.
        val sameScript = config().enabledLanguages.filter { it != lang && Alphabet.forLanguage(it) == Alphabet.forLanguage(lang) }
        val detectLang = if (config().autoDetectLanguage || config().noPrimary) sameScript.firstOrNull() else null
        // With no primary language, a third resident: the next enabled one in the same script.
        val extraLang = if (config().noPrimary && KineticaConstants.MAX_RESIDENT_LANGUAGES > 2) sameScript.getOrNull(1) else null
        Thread({
            try {
                // Learned words merge into the trie at load, gated and scaled by
                // PERSONAL_MERGE_MIN_COUNT and USER_FREQ_SCALE (rationale in KineticaConstants).
                val userRows = try {
                    KineticaDb.get(context).userWords().topN(lang, USER_DICT_LIMIT)
                } catch (e: RuntimeException) {
                    Log.w(KineticaIME.TAG, "user dictionary unavailable", e)
                    emptyList()
                }
                val userWords =
                    DictionaryLoader.userWordsForMerge(userRows.map { it.word to it.frequency })
                val pairRows = if (config().learnPhrases) userBigramRows(lang) else emptyList()
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
                        altRows = try {
                            KineticaDb.get(context).userWords().topN(other, USER_DICT_LIMIT)
                        } catch (e: RuntimeException) {
                            Log.w(KineticaIME.TAG, "user dictionary unavailable for $other", e)
                            emptyList()
                        }
                        altPairRows = if (config().learnPhrases) userBigramRows(other) else emptyList()
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
                        extraRows = try {
                            KineticaDb.get(context).userWords().topN(other, USER_DICT_LIMIT)
                        } catch (e: RuntimeException) {
                            Log.w(KineticaIME.TAG, "user dictionary unavailable for $other", e)
                            emptyList()
                        }
                        extraPairRows = if (config().learnPhrases) userBigramRows(other) else emptyList()
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
                    blockedSpellings = allBlocked
                    val held = heldInstall.offer(wordPending()) install@{
                        // The user may have toggled languages again mid-parse, or while it was held.
                        if (lang != config().language) return@install
                        val counts = ConcurrentHashMap<String, Int>(userRows.size * 2)
                        for (row in userRows) counts[row.word] = row.frequency
                        personalCounts = counts
                        learnedCases[lang] = ConcurrentHashMap(casesOf(userRows))
                        detectLang?.let { learnedCases[it] = ConcurrentHashMap(casesOf(altRows)) }
                        extraLang?.let { learnedCases[it] = ConcurrentHashMap(casesOf(extraRows)) }
                        val pairs = pairMap(pairRows)
                        personalPairs = pairs
                        val p = WordPredictor(
                            dict.trie, bigrams, geometry(), dict.forms, counts, pairs, lang,
                        )
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
                                language = detectLang ?: "",
                            )
                        }
                        val xCounts = ConcurrentHashMap<String, Int>(extraRows.size * 2)
                        for (row in extraRows) xCounts[row.word] = row.frequency
                        extraCounts = xCounts
                        val xPairs = pairMap(extraPairRows)
                        extraPairs = xPairs
                        extraLanguage = if (extra != null) extraLang else null
                        extraPredictor = extra?.let { (d, b) ->
                            WordPredictor(d.trie, b, geometry(), d.forms, xCounts, xPairs, language = extraLang ?: "")
                        }
                        onReady(p)
                        Log.i(
                            KineticaIME.TAG,
                            "dictionary ready [$lang]: ${dict.trie.wordCount} words, " +
                                "${bigrams.size} bigrams, ${dict.forms.size} display forms" +
                                (detectLang?.let { d -> ", auto-detect vs $d" } ?: "") +
                                (extraLanguage?.let { x -> " and $x" } ?: "") +
                                (if (config().noPrimary) ", no primary" else ""),
                        )
                    }
                    if (held) DecodeTrace.log { "  userdict install held lang=$lang" }
                }
            } catch (e: IOException) {
                Log.e(KineticaIME.TAG, "dictionary load failed for $lang", e)
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
     * Empty with one language resident, which leaves every filing to provenance.
     */
    fun heldBy(word: String, active: String): Map<String, Int> {
        val second = secondaryPredictor ?: return emptyMap()
        val secondLang = secondaryLanguage ?: return emptyMap()
        val out = HashMap<String, Int>(3)
        // Held means this exact spelling: `è` is Italian's, not English's through its `e`.
        fun held(p: WordPredictor, lang: String) {
            if (p.holdsSpelling(word)) p.frequencyByte(word).takeIf { it >= 0 }?.let { out[lang] = it }
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

    private fun casesOf(rows: List<UserWord>): Map<String, DictionaryLoader.LearnedCase> =
        DictionaryLoader.learnedCases(rows.map { Triple(it.word, it.display, it.caseVotes) })

    private companion object {
        const val USER_DICT_LIMIT = 5000
        const val USER_PAIR_LIMIT = 5000
    }
}
