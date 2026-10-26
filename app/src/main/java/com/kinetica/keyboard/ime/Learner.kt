package com.kinetica.keyboard.ime

import android.content.Context
import android.os.Handler
import android.util.Log
import com.kinetica.keyboard.data.KineticaDb
import com.kinetica.keyboard.engine.DecodeTrace
import com.kinetica.keyboard.engine.DictionaryLoader
import com.kinetica.keyboard.engine.KineticaConstants
import com.kinetica.keyboard.engine.WordPredictor
import com.kinetica.keyboard.settings.KeyboardConfig
import com.kinetica.keyboard.ui.EmojiRecents
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * What the keyboard learns from use: each word's personal count, its learned capital, the pairs
 * it follows, and the emoji picks. The live maps change at once, Room on [dbExecutor] behind them.
 *
 * Nothing is learned where the field [EditorState.teachesNothing]. [languageOf] is the service's
 * provenance for a word on offer; [wordPending] and [reload] belong to the dictionary reload,
 * which never swaps a predictor mid-word.
 */
internal class Learner(
    private val service: Context,
    private val mainHandler: Handler,
    private val dbExecutor: Executor,
    private val residents: ResidentDictionaries,
    private val commitMarks: CommitMarks,
    private val config: () -> KeyboardConfig,
    private val editorState: () -> EditorState,
    private val languageOf: (String) -> String,
    private val wordPending: () -> Boolean,
    private val reload: () -> Unit,
) {

    /** Resident emoji pick counts, so the picker fills without touching Room. */
    val emojiUses = ConcurrentHashMap<String, EmojiRecents.Use>()

    // The pair learnPair last recorded, so a retype can take that one back. Cleared once
    // used, so a second retype cannot un-learn a pair twice.
    private var lastLearnedPair: Pair<String, String>? = null
    private var lastLearnedPairLang: String? = null

    // The word the last commit learned and the list it went to. A shared word can be filed away
    // from its provenance, so a take-back must not re-derive the language.
    var lastLearnedWord: String? = null
    var lastLearnedWordLang: String? = null

    // A word learned this session is not in the trie until a dictionary load merges it,
    // and nothing else triggers one mid-session.
    private var userDictReloadPending = false
    private val userDictReloadRunnable = Runnable {
        userDictReloadPending = false
        // Never swap the predictor mid-word: the candidates under a buffer already being
        // composed would change beneath it. Unlike the language-change path this must not
        // abandonWord either, so it waits instead.
        if (wordPending()) {
            scheduleUserDictReload()
        } else {
            DecodeTrace.log { "  userdict reload fire" }
            reload()
        }
    }

    fun scheduleUserDictReload() {
        cancelUserDictReload()
        userDictReloadPending = true
        mainHandler.postDelayed(userDictReloadRunnable, USER_DICT_RELOAD_DELAY_MS)
    }

    fun cancelUserDictReload() {
        if (userDictReloadPending) {
            mainHandler.removeCallbacks(userDictReloadRunnable)
            userDictReloadPending = false
        }
    }

    /**
     * Adaptive weighting: every final commit adds [amount] (default 1) to the
     * word's personal count, in the live map (ranking reacts immediately) and
     * in Room (survives restarts, merges into the trie at the next load).
     * [amount] may be negative (slide-to-de-reinforce); both the live map here
     * and the DAO (`MAX(0, ...)`) clamp at zero so a downgrade can never drive
     * a count negative. False when the word was refused, so a take-back has no count to remove.
     */
    fun learnWord(word: String, amount: Int = 1, lang: String = languageOf(word)): Boolean {
        if (editorState().teachesNothing) return false
        val w = word.lowercase()
        if (w.length > KineticaConstants.MAX_WORD_LEN || !WORD_RE.matches(w)) return false
        // A blocked word is not learned back: its row would count up again behind the block.
        if (amount > 0 && w in residents.blockedSpellings) {
            DecodeTrace.log { "  learn refused word=$w blocked" }
            return false
        }
        // Traced, or the dictionary a word is filed under shows only in the learned-words
        // list.
        DecodeTrace.log { "  learn word=$w lang=$lang amount=$amount" }
        var before = 0
        val after = countsFor(lang).compute(w) { _, v ->
            before = v ?: 0
            (before + amount).coerceAtLeast(0)
        } ?: 0
        val now = System.currentTimeMillis()
        dbExecutor.execute {
            try {
                KineticaDb.get(service).userWords().upsertAdd(w, lang, amount, now)
            } catch (e: RuntimeException) {
                Log.w(KineticaIME.TAG, "learn failed for $w", e)
            }
            // Queued behind the write above: the reload reads userWords itself, and out of
            // order it would miss the count this call is adding and drop the word again.
            mainHandler.post {
                askUserDictReload(w, lang, before, after)
                // A demoted learned word keeps its merged trie frequency (count x 1000) until the
                // next load, so a slide down would barely move it: reload behind the write.
                if (userDictDemoted(before, after)) {
                    DecodeTrace.log { "  userdict stale word=$w count=$after lang=$lang src=demote" }
                    scheduleUserDictReload()
                }
            }
        }
        return true
    }

    /**
     * One vote on how [word] is capitalized in [lang] (`caseVote`). Decided against the live
     * mirror and written whole, behind the count the same commit queued, so the row exists; a
     * capital that now leads, or no longer does, needs a load to show.
     */
    /** Applies [case] to [word]'s learned capital; true when it changed. */
    fun voteCase(word: String, lang: String, case: CaseVote): Boolean {
        if (case.vote == 0 || editorState().teachesNothing) return false
        val w = word.lowercase()
        val map = casesFor(lang)
        val old = map[w]
        val next = nextCase(old, case) ?: return false
        map[w] = next
        val reload = leadChanged(old, next)
        DecodeTrace.log { "  learn capital word=$w display=${next.display} votes=${next.votes} lang=$lang" }
        dbExecutor.execute {
            try {
                KineticaDb.get(service).userWords().setCase(w, lang, next.display, next.votes)
            } catch (e: RuntimeException) {
                Log.w(KineticaIME.TAG, "case vote failed for $w", e)
                return@execute
            }
            if (reload) mainHandler.post { scheduleUserDictReload() }
        }
        return true
    }

    /** Puts [w]'s learned capital back to [before], as it stood before a vote now taken back. */
    private fun restoreCase(w: String, lang: String, before: DictionaryLoader.LearnedCase?) {
        val map = casesFor(lang)
        val now = map[w]
        if (before == null) map.remove(w) else map[w] = before
        val reload = leadChanged(now, before)
        dbExecutor.execute {
            try {
                KineticaDb.get(service).userWords().setCase(w, lang, before?.display, before?.votes ?: 0)
            } catch (e: RuntimeException) {
                Log.w(KineticaIME.TAG, "case restore failed for $w", e)
                return@execute
            }
            if (reload) mainHandler.post { scheduleUserDictReload() }
        }
    }

    /**
     * Takes back what [lesson]'s commit taught: its count, its case vote and its pair. That
     * commit came from an automatic space the next letter took back, so it was not the word:
     * `Fake`, spaced, then typed on into `FakeCompanyName`, must not settle `Fake` as `fake`'s
     * capital.
     */
    fun takeBack(lesson: CommitLesson) {
        DecodeTrace.log {
            "  learn takeback word=${lesson.word} lang=${lesson.lang} case=${lesson.voted} pair=${lesson.pair != null}"
        }
        commitMarks.forget(lesson)
        if (lesson.counted) unlearnWord(lesson.word, lesson.lang)
        if (lesson.voted) restoreCase(lesson.word, lesson.lang, lesson.caseBefore)
        lesson.pair?.let { pair ->
            if (lastLearnedPair == pair) {
                lastLearnedPair = null
                lastLearnedPairLang = null
            }
            unlearnPair(pair, lesson.lang)
        }
    }

    /**
     * Arms the reload that makes a word just learned searchable.
     *
     * [unlearnWord] has no counterpart: a word pushed back below the merge floor keeps its
     * trie entry until the next load, but its ranking multiplier drops with the count.
     */
    private fun askUserDictReload(word: String, lang: String, before: Int, after: Int) {
        val p = predictorFor(lang)
        // No predictor yet means the first load has not finished; it will merge this word.
        if (!userDictNeedsReload(before, after, p?.isWordIgnoringCase(word) ?: true)) return
        DecodeTrace.log { "  userdict stale word=$word count=$after lang=$lang" }
        scheduleUserDictReload()
    }

    /**
     * One more pick recorded for [emoji]. Same shape as [learnWord]: the resident
     * map is updated synchronously so the panel is right the next time it opens,
     * and Room is written on [dbExecutor].
     */
    fun recordEmojiUse(emoji: String) {
        // A field that must not teach the dictionary must not populate a visible list
        // of what was typed in it either.
        if (editorState().teachesNothing || emoji.isEmpty()) return
        val now = System.currentTimeMillis()
        emojiUses.compute(emoji) { _, v -> EmojiRecents.Use(emoji, (v?.count ?: 0) + 1, now) }
        dbExecutor.execute {
            try {
                KineticaDb.get(service).emojiUses().upsertAdd(emoji, 1, now)
            } catch (e: RuntimeException) {
                Log.w(KineticaIME.TAG, "emoji use not recorded", e)
            }
        }
    }

    /** Seeds [emojiUses] once, so opening the picker never reads Room on the main thread. */
    fun reloadEmojiUses() {
        dbExecutor.execute {
            val rows = try {
                KineticaDb.get(service).emojiUses().topN(EmojiRecents.MAX)
            } catch (e: RuntimeException) {
                Log.w(KineticaIME.TAG, "emoji use table unavailable", e)
                emptyList()
            }
            for (row in rows) {
                emojiUses[row.emoji] = EmojiRecents.Use(row.emoji, row.count, row.updatedAt)
            }
        }
    }

    /** Live pair map backing [lang]'s predictor; the active one by default. */
    private fun pairsFor(lang: String): ConcurrentHashMap<String, Int> = residents.pairsFor(lang, config().language)

    /** Key for the pair store; the separator cannot occur in a word. */
    private fun pairKey(prev: String, next: String): String = WordPredictor.pairKey(prev, next)

    /**
     * Records that [word] followed its predecessor, for this user, in this language.
     *
     * Off unless the phrase setting is on. The pair comes from the composer's context deque,
     * not the editor, so it never spans two fields: `onStartInput` resets the composer.
     *
     * A commit is not proof: the most repeated pairs in one capture were `world -> word`,
     * `held -> glee` and `keys -> myers`, all decodes the user then retyped. [retypeCurrentWord]
     * takes the last pair back so the store does not learn the errors it exists to fix.
     */
    /** Learns the pair ending in [word]; returns it, or null when nothing was learned. */
    fun learnPair(context: List<String>?, word: String, lang: String): Pair<String, String>? {
        if (!config().learnPhrases || editorState().teachesNothing) return null
        val prev = context?.getOrNull((context.size) - 2) ?: return null
        val w = word.lowercase()
        val p = prev.lowercase()
        if (!WORD_RE.matches(w) || !WORD_RE.matches(p)) return null
        if (w.length > KineticaConstants.MAX_WORD_LEN || p.length > KineticaConstants.MAX_WORD_LEN) return null
        lastLearnedPair = p to w
        lastLearnedPairLang = lang
        pairsFor(lang).compute(pairKey(p, w)) { _, v -> (v ?: 0) + 1 }
        val now = System.currentTimeMillis()
        dbExecutor.execute {
            try {
                KineticaDb.get(service).userBigrams().upsertAdd(p, w, lang, 1, now)
            } catch (e: RuntimeException) {
                Log.w(KineticaIME.TAG, "phrase learn failed", e)
            }
        }
        return p to w
    }

    /**
     * One learned pair up or down by [delta], under [learnPair]'s guards. A pair is only taken
     * down where it exists, so the store never holds a negative row.
     */
    fun adjustPair(prev: String, word: String, lang: String, delta: Int) {
        if (!config().learnPhrases || editorState().teachesNothing || delta == 0) return
        val w = word.lowercase()
        val p = prev.lowercase()
        if (!WORD_RE.matches(w) || !WORD_RE.matches(p)) return
        if (w.length > KineticaConstants.MAX_WORD_LEN || p.length > KineticaConstants.MAX_WORD_LEN) return
        val key = pairKey(p, w)
        if (delta < 0) {
            if ((pairsFor(lang)[key] ?: 0) <= 0) return
            pairsFor(lang).computeIfPresent(key) { _, v -> (v + delta).coerceAtLeast(0) }
        } else {
            pairsFor(lang).compute(key) { _, v -> (v ?: 0) + delta }
        }
        val now = System.currentTimeMillis()
        dbExecutor.execute {
            try {
                val dao = KineticaDb.get(service).userBigrams()
                if (delta > 0) {
                    dao.upsertAdd(p, w, lang, delta, now)
                } else {
                    // The count above is every spelling of the pair added up under its folded
                    // key, so the decrement goes to the rows that hold it, not to this spelling,
                    // which may not exist and would come back at the next load.
                    val stored = dao.topN(lang, Int.MAX_VALUE).map { StoredPair(it.prev, it.next, it.count) }
                    for ((row, take) in pickPairRows(stored, key, -delta)) {
                        dao.upsertAdd(row.prev, row.next, lang, -take, now)
                    }
                }
            } catch (e: RuntimeException) {
                Log.w(KineticaIME.TAG, "phrase adjust failed", e)
            }
        }
    }

    /** Takes back the pair [learnPair] last recorded: the retype says that commit was wrong. */
    fun unlearnLastPair() {
        val pair = lastLearnedPair ?: return
        val lang = lastLearnedPairLang ?: return
        lastLearnedPair = null
        lastLearnedPairLang = null
        unlearnPair(pair, lang)
    }

    private fun unlearnPair(pair: Pair<String, String>, lang: String) {
        val key = pairKey(pair.first, pair.second)
        pairsFor(lang).computeIfPresent(key) { _, v -> (v - 1).coerceAtLeast(0) }
        val now = System.currentTimeMillis()
        dbExecutor.execute {
            try {
                KineticaDb.get(service).userBigrams().upsertAdd(pair.first, pair.second, lang, -1, now)
            } catch (e: RuntimeException) {
                Log.w(KineticaIME.TAG, "phrase unlearn failed", e)
            }
        }
    }

    /** Inverse of [learnWord] for commits the user explicitly took back. */
    fun unlearnWord(word: String, filed: String? = null) {
        if (editorState().teachesNothing) return
        val w = word.lowercase()
        if (w.length > KineticaConstants.MAX_WORD_LEN || !WORD_RE.matches(w)) return
        val lang = filed ?: lastLearnedWordLang?.takeIf { w == lastLearnedWord } ?: languageOf(word)
        countsFor(lang).computeIfPresent(w) { _, v -> (v - 1).coerceAtLeast(0) }
        val now = System.currentTimeMillis()
        dbExecutor.execute {
            try {
                KineticaDb.get(service).userWords().upsertAdd(w, lang, -1, now)
            } catch (e: RuntimeException) {
                Log.w(KineticaIME.TAG, "unlearn failed for $w", e)
            }
        }
    }

    /** The predictor serving [lang]: the active one, the second or the third. */
    private fun predictorFor(lang: String): WordPredictor? = residents.predictorFor(lang, config().language)

    private fun countsFor(lang: String): ConcurrentHashMap<String, Int> = residents.countsFor(lang, config().language)

    private fun casesFor(lang: String): ConcurrentHashMap<String, DictionaryLoader.LearnedCase> = residents.casesFor(lang)

    private companion object {
        // A learned word only becomes searchable at a dictionary load, and a load parses
        // 49k words plus 100k bigrams. 1500 ms lets a burst of new words in one sentence
        // cost one parse, and still has the word usable while the user is typing the
        // thing they added it for.
        const val USER_DICT_RELOAD_DELAY_MS = 1500L

        // Any-letter (accented Italian included) with internal apostrophes.
        val WORD_RE = Regex("^\\p{L}+(?:'\\p{L}+)*$")
    }
}
