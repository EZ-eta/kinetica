package com.kinetica.keyboard.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import androidx.core.view.WindowInsetsControllerCompat
import androidx.preference.PreferenceManager
import com.kinetica.keyboard.R
import com.kinetica.keyboard.data.DictionaryStore
import com.kinetica.keyboard.data.KineticaDb
import com.kinetica.keyboard.data.UserBigram
import com.kinetica.keyboard.data.UserWord
import com.kinetica.keyboard.engine.AccentFolder
import com.kinetica.keyboard.engine.DecodeTrace
import com.kinetica.keyboard.engine.Alphabet
import com.kinetica.keyboard.engine.DictionaryLoader
import com.kinetica.keyboard.engine.GestureEngine
import com.kinetica.keyboard.engine.KeyboardGeometry
import com.kinetica.keyboard.engine.KineticaConstants
import com.kinetica.keyboard.engine.LanguageMomentum
import com.kinetica.keyboard.engine.SharedWordFiling
import com.kinetica.keyboard.engine.WordComposer
import com.kinetica.keyboard.engine.WordPredictor
import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.TapToken
import com.kinetica.keyboard.engine.models.WordCandidate
import com.kinetica.keyboard.keys.ActionRow
import com.kinetica.keyboard.keys.AutoCapitalization
import com.kinetica.keyboard.keys.ChordKey
import com.kinetica.keyboard.keys.ChordTrigger
import com.kinetica.keyboard.keys.DeleteSpan
import com.kinetica.keyboard.keys.EditorAction
import com.kinetica.keyboard.keys.EdgeSwipeBindings
import com.kinetica.keyboard.keys.KeyCombo
import com.kinetica.keyboard.keys.ShiftState
import com.kinetica.keyboard.keys.SpecialKeys
import com.kinetica.keyboard.keys.StandaloneLetters
import com.kinetica.keyboard.keys.WordCase
import com.kinetica.keyboard.layout.Key
import com.kinetica.keyboard.layout.KeyType
import com.kinetica.keyboard.layout.KeyboardLayout
import com.kinetica.keyboard.layout.LayoutLoader
import com.kinetica.keyboard.layout.LayoutMutations
import com.kinetica.keyboard.settings.ActionLabels
import com.kinetica.keyboard.settings.ChordDefaults
import com.kinetica.keyboard.settings.ExpansionRows
import com.kinetica.keyboard.settings.KeyboardConfig
import com.kinetica.keyboard.settings.KeyboardHeights
import com.kinetica.keyboard.settings.Prefs
import com.kinetica.keyboard.settings.SettingsActivity
import com.kinetica.keyboard.settings.SettingsSynonyms
import com.kinetica.keyboard.ui.EmojiPickerView
import com.kinetica.keyboard.ui.EmojiRecents
import com.kinetica.keyboard.ui.InputContainerView
import com.kinetica.keyboard.ui.Hsv
import com.kinetica.keyboard.ui.KeyboardTheme
import com.kinetica.keyboard.ui.KeyboardView
import com.kinetica.keyboard.ui.SuggestionBarView
import java.io.IOException
import kotlin.math.roundToInt
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Kinetica input method service.
 *
 * Text model: commit-only, no composing region. Tap letters commit
 * immediately; the word in progress is tracked as [tentativeLength] committed
 * chars, and swipe decodes replace that span in a single batch edit. This
 * avoids the composing-region state machine and its OEM quirks entirely.
 */
class KineticaIME : InputMethodService(), GestureEngine.Listener, WordComposer.Callbacks {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** The spacebar's words per minute (TypingSpeed), and the touch span of the word in hand. */
    private val typingSpeed = TypingSpeed()
    private var wordStartMs = -1L
    private var wordEndMs = -1L
    private val speedHideRunnable = Runnable { keyboardView?.speedLabel = null }
    private val inputMethodManager by lazy { getSystemService(InputMethodManager::class.java) }
    /**
     * This IME's own entry in the system list, or null if the system does not report it.
     *
     * Nullable, not `first { }`: it is read from `onStartInput`, where a throw kills the
     * keyboard instead of one feature. Language synchronisation becomes a no-op.
     */
    private val inputMethodInfo by lazy {
        inputMethodManager.inputMethodList.firstOrNull { it.packageName == packageName }
    }
    private val mainExecutor = Executor { mainHandler.post(it) }
    private val decodeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "kinetica-decode").apply { priority = Thread.NORM_PRIORITY + 1 }
    }

    // The second language's decode runs here, beside the first.
    private val alternateDecodeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "kinetica-decode-alt").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    // A third language's decode, with no primary language: its own thread, its own predictor.
    private val extraDecodeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "kinetica-decode-extra").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    private val dbExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "kinetica-db")
    }

    /** Resident emoji pick counts, so the picker fills without touching Room. */
    private val emojiUses = ConcurrentHashMap<String, EmojiRecents.Use>()

    private val ich = InputConnectionHelper { currentInputConnection }
    private val engine = GestureEngine(this)
    private val shift = ShiftState()

    // Populated asynchronously once the dictionary is parsed.
    @Volatile
    private var predictor: WordPredictor? = null
    private var composer: WordComposer? = null

    // Other-language predictor for the experimental per-word auto-detect;
    // null unless the setting is on and a second language is enabled.
    private var secondaryPredictor: WordPredictor? = null

    // Personal commit counts, mirrored from user_words. Main thread writes, the decode thread
    // reads through the predictor: concurrent map. One map per resident predictor, because
    // user_words and the boost are keyed by language.
    private var personalCounts = ConcurrentHashMap<String, Int>()
    // Learned word pairs, keyed "prev\u0000next", same concurrent contract as the counts
    // above. Empty and never written unless the phrase setting is on.
    private var personalPairs = ConcurrentHashMap<String, Int>()
    private var secondaryPairs = ConcurrentHashMap<String, Int>()
    private var secondaryCounts = ConcurrentHashMap<String, Int>()
    private var secondaryLanguage: String? = null

    // The third resident, only with no primary language (Prefs.NO_PRIMARY). Same contract as the
    // secondary fields above.
    private var extraPredictor: WordPredictor? = null
    private var extraCounts = ConcurrentHashMap<String, Int>()
    private var extraPairs = ConcurrentHashMap<String, Int>()
    private var extraLanguage: String? = null

    /** Which resident language is being typed, with no primary language. Main thread. */
    private val momentum = LanguageMomentum()

    // Language of each candidate currently on offer, keyed by its display form
    // (lowercased). The bar and the commit path work in strings, so this is how
    // a picked or committed word finds the dictionary it came from.
    private var candidateLanguages: Map<String, String> = emptyMap()

    // Same map, snapshotted at commit time: the correction strip outlives
    // lastCandidates, and a correction pick is a real commit that must learn
    // into the right language too.
    private var correctionLanguages: Map<String, String> = emptyMap()

    // (trigger, key character) -> expansion, mirrored from chord_shortcuts. Read on the UI
    // thread at pointer-down; refreshed on every input start so edits made in
    // settings apply as soon as the keyboard regains focus.
    @Volatile
    private var chordMap: Map<ChordKey, String> = emptyMap()

    // trigger -> targets, mirrored from `expansions`. Read on the main thread when the
    // expandify action fires. Unlike chordMap it is not re-read at every input start: the
    // table may hold hundreds of rows, so it reloads on Prefs.EXPANSION_GENERATION.
    @Volatile
    private var expansionMap: Map<String, List<String>> = emptyMap()

    /** Several targets for one trigger, offered on the bar until one is picked (#19 phase 2). */
    private class ExpansionChoice(val trigger: String, val targets: List<String>, val shown: List<String>)

    private var expansionChoice: ExpansionChoice? = null

    // The shortcut rows, resolved once per config change, so the index a surface reports and
    // the row it drew cannot disagree.
    private var barActions: List<EditorAction> = emptyList()
    private var menuActions: List<EditorAction> = emptyList()

    private var keyboardView: KeyboardView? = null
    private var suggestionBar: SuggestionBarView? = null
    private var containerView: InputContainerView? = null
    private var emojiPicker: EmojiPickerView? = null
    private var currentGeometry: KeyboardGeometry? = null

    /** The letters of the board on screen: a tap's code is a code of this alphabet. */
    private val boardAlphabet: Alphabet get() = currentGeometry?.alphabet ?: Alphabet.LATIN
    private val layouts = HashMap<String, KeyboardLayout>()
    private var vibrator: Vibrator? = null
    private var prefListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private var editorState = EditorState.DEFAULT

    private lateinit var config: KeyboardConfig

    // Word-in-progress bookkeeping (main thread only).
    private var tentativeLength = 0
    private var tentativeWord = ""
    private var wordShift = ShiftState.State.NONE
    private var lastCandidates: List<WordCandidate> = emptyList()
    // The candidate the merge cleared to lead, the only one that may autocorrect a tapped
    // word. Not the list head: when the active language decoded nothing the head is a word
    // the merge refused.
    private var lastTentative: WordCandidate? = null
    private var lastLiteral = ""
    private val selectionLedger = SelectionLedger()
    /** This field's commits and their bars' words, for an offer inside one of them. */
    private val commitHistory = CommitHistory()
    /**
     * Every blocked spelling, any language, lowercased. The trie never holds one, but the typed
     * letters, the correction strip, the recent columns and the mid-word history are kept
     * strings, and a blocked word could come back through them (`anb` for `and`).
     */
    @Volatile private var blockedSpellings: Set<String> = emptySet()

    /** The last autocorrect, until anything else is typed: a backspace puts the letters back. */
    private class AutocorrectUndo(val typed: String, val corrected: String, val end: Int)
    private var autocorrectUndo: AutocorrectUndo? = null
    private var midWordHistory: CommitHistory.Record? = null
    // The word reloadWordUnderCursor seeded back from the editor, if any. Read
    // once at commit to keep a re-commit of unchanged text from being learned
    // twice; see learnsOnCommit.
    private var reloadedWord: String? = null

    // The one-shot Ctrl: set by CTRL_NEXT, spent by the next key.
    private var ctrlPending = false

    // The word the cursor was parked inside when the bar was filled for it. It is not a word
    // in progress, so a letter typed there is an ordinary insertion. A pick re-reads both
    // halves before it rewrites anything.
    private var midWordOffer: WordAround? = null

    // The editor's selection, normalized so start <= end; equal means a plain cursor.
    // Insertions do not need it, since commitText replaces a selection itself. Backspace does:
    // deleteSurroundingText works relative to the selection and leaves it standing, so it
    // would delete a character beside the selection.
    private var selStart = 0
    private var selEnd = 0
    // Set when the latest decode of a swipe-bearing word returned no candidates:
    // the visible tentative is then a stale earlier partial decode that must not
    // be autospaced or learned.
    private var swipeDecodeEmpty = false

    // The pair learnPair last recorded, so a retype can take that one back. Cleared once
    // used, so a second retype cannot un-learn a pair twice.
    private var lastLearnedPair: Pair<String, String>? = null
    private var lastLearnedPairLang: String? = null

    // The word the last commit learned and the list it went to. A shared word can be filed away
    // from its provenance, so a take-back must not re-derive the language.
    private var lastLearnedWord: String? = null
    private var lastLearnedWordLang: String? = null
    private val sharedFiling = SharedWordFiling()
    private val lastCommit = CommitMemory()

    // The last commit while backspace eats into it, for the trace only. Kept apart
    // from lastCommit because the first backspace's abandonWord clears that.
    private var backspaceTarget: String? = null

    // True while the space directly before the cursor is one autospace put there,
    // not one the user typed. Only an automatic space is taken back by punctuation:
    // a deliberate space before a dash is the user's own and stays.
    private var autospaceInserted = false

    // The word the last retype rejected, armed for one decode. Behind its own setting, and
    // it demotes, not drops: the wanted word was among the alternates in only 5 of 12 chains,
    // and a retype aimed at a space must still reach the word it had.
    private var retypeRejected: String? = null

    // A word learned this session is not in the trie until a dictionary load merges it,
    // and nothing else triggers one mid-session.
    private var userDictReloadPending = false
    private val userDictReloadRunnable = Runnable {
        userDictReloadPending = false
        // Never swap the predictor mid-word: the candidates under a buffer already being
        // composed would change beneath it. Unlike the language-change path this must not
        // abandonWord either, so it waits instead.
        if (composer?.hasPendingWord == true) {
            scheduleUserDictReload()
        } else {
            DecodeTrace.log { "  userdict reload fire" }
            loadDictionaryAsync()
        }
    }

    // Clears the spacebar's shortcut notice; the label has no timer of its own. See
    // showSpacebarNotice.
    private val spacebarNoticeRunnable = Runnable { keyboardView?.spacebarNotice = null }

    // A buffer whose decode came back empty is closed after a pause instead of
    // being left open. See staleBufferRunnable.
    private var stalePending = false
    private val staleBufferRunnable = Runnable {
        stalePending = false
        if (lastCandidates.isNotEmpty()) closeBufferKeepBar() else abandonWord()
    }

    // Set while the bar shows candidates of a buffer the stale timeout already closed:
    // the earlier decode still on screen, and what the editor held when the buffer closed, so
    // a pick can prove nothing moved since. Null otherwise.
    private var keptBar: KeptBar? = null

    private class KeptBar(val staleWord: String, val tailAtClose: String)

    // True while the space before the cursor is an autospace that followed a tapped word.
    // Only that kind is provisional: a swipe's space follows a finished gesture, so a letter
    // after it starts a new word.
    private var autospaceFromTaps = false
    private var autospaceAt = 0L

    // True while the composer holds a word reloaded from the editor that has received no
    // token since. A reload is not a finished word: the user parked the cursor in it or a
    // delete laid it bare. WordComposer.seed decodes, and without this flag that decode
    // armed the autospace timer like a thumb's and put a second space in.
    private var seededWithoutTokens = false

    private var autospacePending = false
    private val autospaceRunnable = Runnable {
        autospacePending = false
        val comp = composer
        val hasSwipe = comp?.hasSwipeToken() == true
        // The text preceding the word being written, i.e. the word's own letters dropped
        // off the end of what the editor holds. Read once: both questions below are about
        // its last character.
        val wokeJoiner =
            ich.textBeforeCursor(tentativeLength + 1)?.dropLast(tentativeLength) ?: ""
        val taps = autospacesTappedWord(
            enabled = config.autospaceTappedWords,
            hasSwipeToken = hasSwipe,
            literal = lastLiteral,
            literalIsWord = predictor?.isWord(lastLiteral) == true,
            literalIsStandaloneLetter = standaloneLetter(lastLiteral),
            addressField = editorState.addressField,
            // Re-read when the timer fires, not at scheduling: this site inserts the space,
            // and the text can have moved since.
            joinedToWhatPrecedes = joinsPrecedingToken(wokeJoiner),
            joinedTokenIsWord = joinedTokenIsWord(),
            joinedByApostrophe = joinedByApostrophe(wokeJoiner),
            carriesNoToken = seededWithoutTokens,
        )
        val swipes = autospacesSwipedWord(
            hasSwipeToken = hasSwipe,
            addressField = editorState.addressField,
            carriesNoToken = seededWithoutTokens,
        )
        DecodeTrace.log {
            "  autospace wake lit=$lastLiteral pending=${comp?.hasPendingWord} " +
                "taps=$taps swipes=$swipes"
        }
        if (comp?.hasPendingWord == true && (swipes || taps)) {
            // Read before the word is finalized, which can rewrite it but never what follows.
            val after = if (config.tidySpaces) ich.textAfterCursor(2) else null
            if (!autospaceWanted(config.tidySpaces, after)) {
                // The word still ends; the editor already has what should follow it.
                if (finalizePendingWord()) {
                    DecodeTrace.log { "  autospace held before=${after?.firstOrNull()?.code}" }
                    updateAutoShift()
                    refreshPredictions()
                }
                return@Runnable
            }
            if (finalizePendingWord()) {
                commitTracked(" ")
                autospaceInserted = true
                // Recorded after commitTracked, which clears the flag it sets.
                autospaceFromTaps = taps
                autospaceAt = SystemClock.uptimeMillis()
                DecodeTrace.log { "  autospace fire" }
                updateAutoShift()
                refreshPredictions()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Decode tracing, developer build only. Two-thumb gestures cannot be reproduced on
        // the JVM, so a capture of the real token buffer is the evidence for them. The
        // release source set's TraceRecorder is a stub that installs nothing.
        TraceRecorder.install(this)
        engine.maxPointers = 2
        @Suppress("DEPRECATION")
        vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        config = KeyboardConfig.from(prefs)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, _ ->
            val previous = config
            config = KeyboardConfig.from(p)
            // What decides the automatic space, whenever any of it changes and from wherever.
            if (config.autospace != previous.autospace || config.tidySpaces != previous.tidySpaces ||
                config.wordEndsOnSpace != previous.wordEndsOnSpace ||
                config.doubleSpacePeriod != previous.doubleSpacePeriod
            ) {
                DecodeTrace.log {
                    "config autospace=${config.autospace} tidy=${config.tidySpaces} " +
                        "wordEnds=${config.wordEndsOnSpace} doubleSpace=${config.doubleSpacePeriod}"
                }
            }
            applyViewConfig()
            if (config.enabledLanguages != previous.enabledLanguages) syncEnabledSubtypes()
            // Its own branch: an expansion edit touches no dictionary or layout, and folding
            // it into the chain below would rebuild one.
            if (config.expansionGeneration != previous.expansionGeneration) {
                reloadExpansions()
            }
            if (config.peckMode != previous.peckMode) {
                // Entering or leaving literal mode mid-word would leave a
                // half-tracked tentative; settle it as plain text first.
                abandonWord()
            }
            if (config.language != previous.language) {
                // Before the layout is built, so the board comes out of the arrangement
                // the language asked for, not the one it replaces.
                applyArrangementForLanguage(p, config.language)
                // Swap layout immediately; predictions swap when the new
                // dictionary finishes parsing (the old one keeps serving).
                abandonWord()
                keyboardView?.setKeyboardLayout(alphaLayout())
                loadDictionaryAsync()
                requestLanguageSubtype(config.language)
            } else if (config.dictionaryGeneration != previous.dictionaryGeneration ||
                config.autoDetectLanguage != previous.autoDetectLanguage ||
                config.noPrimary != previous.noPrimary ||
                config.enabledLanguages != previous.enabledLanguages ||
                config.learnPhrases != previous.learnPhrases ||
                // British spelling is applied while the trie is built, so it
                // needs the same reload a stored-data change needs.
                config.britishSpelling != previous.britishSpelling
            ) {
                // Stored dictionary data changed, or the secondary-language
                // predictor must be loaded or dropped: reload in place.
                loadDictionaryAsync()
            } else if (config.keyArrangement != previous.keyArrangement ||
                config.homeRowSpreadPct != previous.homeRowSpreadPct ||
                // The apostrophe key reshapes the home row too.
                config.apostropheKey != previous.apostropheKey ||
                config.letterAlternates != previous.letterAlternates ||
                config.emojiKey != previous.emojiKey ||
                config.numberPriority != previous.numberPriority ||
                config.numberRow != previous.numberRow ||
                config.plainLetterAlternates != previous.plainLetterAlternates ||
                config.commaMode != previous.commaMode ||
                config.commaCustom != previous.commaCustom ||
                config.periodMode != previous.periodMode ||
                config.periodCustom != previous.periodCustom ||
                config.periodAlternates != previous.periodAlternates ||
                config.commaAlternates != previous.commaAlternates
            ) {
                closeEmojiPicker()
                keyboardView?.setKeyboardLayout(alphaLayout())
            }
        }
        prefListener = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
        loadDictionaryAsync()
        reloadExpansions()
    }

    override fun onDestroy() {
        prefListener?.let {
            PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(it)
        }
        cancelUserDictReload()
        mainHandler.removeCallbacks(spacebarNoticeRunnable)
        decodeExecutor.shutdown()
        alternateDecodeExecutor.shutdown()
        extraDecodeExecutor.shutdown()
        dbExecutor.shutdown()
        super.onDestroy()
    }

    private fun loadDictionaryAsync() {
        val lang = config.language
        // Auto-detect is pairwise: one resident secondaryPredictor, so with 3+ enabled
        // languages only the first non-active one participates (ADDING_A_LANGUAGE.md).
        // Lifting it costs a resident predictor per language and an N-way vote in WordComposer.
        // Same script only: another script's words cannot be drawn on this board, and its
        // codes would read this board's keys as its own letters.
        val sameScript = config.enabledLanguages.filter { it != lang && Alphabet.forLanguage(it) == Alphabet.forLanguage(lang) }
        val detectLang = if (config.autoDetectLanguage || config.noPrimary) sameScript.firstOrNull() else null
        // With no primary language, a third resident: the next enabled one in the same script.
        val extraLang = if (config.noPrimary && KineticaConstants.MAX_RESIDENT_LANGUAGES > 2) sameScript.getOrNull(1) else null
        Thread({
            try {
                // Learned words merge into the trie at load, gated and scaled by
                // PERSONAL_MERGE_MIN_COUNT and USER_FREQ_SCALE (rationale in KineticaConstants).
                val userRows = try {
                    KineticaDb.get(this).userWords().topN(lang, USER_DICT_LIMIT)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "user dictionary unavailable", e)
                    emptyList()
                }
                val userWords =
                    DictionaryLoader.userWordsForMerge(userRows.map { it.word to it.frequency })
                val pairRows = if (config.learnPhrases) userBigramRows(lang) else emptyList()
                // Words the user has blocked never reach the trie, so they
                // cannot be decoded, completed or suggested.
                val blocked = blockedWords(lang)
                val allBlocked = allBlockedWords()
                val swaps = spellingSwaps(lang)
                val dict = openWordlist(lang).bufferedReader().use {
                    DictionaryLoader.load(it, userWords, blocked, swaps, Alphabet.forLanguage(lang))
                }
                val bigrams = assets.open(bigramsAsset(lang)).bufferedReader().use {
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
                            KineticaDb.get(this).userWords().topN(other, USER_DICT_LIMIT)
                        } catch (e: RuntimeException) {
                            Log.w(TAG, "user dictionary unavailable for $other", e)
                            emptyList()
                        }
                        altPairRows = if (config.learnPhrases) userBigramRows(other) else emptyList()
                        val d = openWordlist(other).bufferedReader().use {
                            DictionaryLoader.load(
                                it,
                                DictionaryLoader.userWordsForMerge(
                                    altRows.map { r -> r.word to r.frequency },
                                ),
                                blockedWords(other),
                                spellingSwaps(other),
                                Alphabet.forLanguage(other),
                            )
                        }
                        val b = assets.open(bigramsAsset(other)).bufferedReader().use {
                            DictionaryLoader.loadBigrams(it, d.trie)
                        }
                        d to b
                    } catch (e: IOException) {
                        Log.w(TAG, "secondary dictionary load failed for $other", e)
                        null
                    }
                }
                var extraRows: List<UserWord> = emptyList()
                var extraPairRows: List<UserBigram> = emptyList()
                val extra = extraLang?.let { other ->
                    try {
                        extraRows = try {
                            KineticaDb.get(this).userWords().topN(other, USER_DICT_LIMIT)
                        } catch (e: RuntimeException) {
                            Log.w(TAG, "user dictionary unavailable for $other", e)
                            emptyList()
                        }
                        extraPairRows = if (config.learnPhrases) userBigramRows(other) else emptyList()
                        val d = openWordlist(other).bufferedReader().use {
                            DictionaryLoader.load(
                                it,
                                DictionaryLoader.userWordsForMerge(extraRows.map { r -> r.word to r.frequency }),
                                blockedWords(other),
                                spellingSwaps(other),
                                Alphabet.forLanguage(other),
                            )
                        }
                        val b = assets.open(bigramsAsset(other)).bufferedReader().use {
                            DictionaryLoader.loadBigrams(it, d.trie)
                        }
                        d to b
                    } catch (e: IOException) {
                        Log.w(TAG, "third dictionary load failed for $other", e)
                        null
                    }
                }
                mainHandler.post {
                    blockedSpellings = allBlocked
                    // The user may have toggled languages again mid-parse.
                    if (lang != config.language) return@post
                    val counts = ConcurrentHashMap<String, Int>(userRows.size * 2)
                    for (row in userRows) counts[row.word] = row.frequency
                    personalCounts = counts
                    val pairs = pairMap(pairRows)
                    personalPairs = pairs
                    val p = WordPredictor(
                        dict.trie, bigrams, currentGeometry, dict.forms, counts, pairs, lang,
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
                            d.trie, b, currentGeometry, d.forms, altCounts, altPairs,
                            language = detectLang ?: "",
                        )
                    }
                    val xCounts = ConcurrentHashMap<String, Int>(extraRows.size * 2)
                    for (row in extraRows) xCounts[row.word] = row.frequency
                    extraCounts = xCounts
                    val xPairs = pairMap(extraPairRows)
                    extraPairs = xPairs
                    extraLanguage = if (extra != null) extraLang else null
                    // A language that left (a switch from Polish) must not keep its standing.
                    momentum.retain(residentLanguages())
                    extraPredictor = extra?.let { (d, b) ->
                        WordPredictor(d.trie, b, currentGeometry, d.forms, xCounts, xPairs, language = extraLang ?: "")
                    }
                    composer = WordComposer(
                        p, decodeExecutor, mainExecutor, this, alternateDecodeExecutor, extraDecodeExecutor,
                    ).also {
                        it.alternatePredictor = secondaryPredictor
                        it.extraPredictor = extraPredictor
                        it.languageWeights = equalWeights()
                    }
                    Log.i(
                        TAG,
                        "dictionary ready [$lang]: ${dict.trie.wordCount} words, " +
                            "${bigrams.size} bigrams, ${dict.forms.size} display forms" +
                            (detectLang?.let { d -> ", auto-detect vs $d" } ?: "") +
                            (extraLanguage?.let { x -> " and $x" } ?: "") +
                            (if (config.noPrimary) ", no primary" else ""),
                    )
                }
            } catch (e: IOException) {
                Log.e(TAG, "dictionary load failed for $lang", e)
            }
        }, "kinetica-dict-load").start()
    }

    /**
     * Spelling pairs to exchange for [lang], empty unless the user asked for British spelling
     * and this is English. Both spellings are already in the trie, so only the two counts
     * trade places.
     */
    private fun spellingSwaps(lang: String): Map<String, String> {
        if (lang != "en" || !config.britishSpelling) return emptyMap()
        return try {
            assets.open("dictionaries/en_gb_variants.txt").bufferedReader().use {
                DictionaryLoader.loadSpellingSwaps(it)
            }
        } catch (e: java.io.IOException) {
            Log.w(TAG, "British spelling variants asset unavailable", e)
            emptyMap()
        }
    }

    private fun allBlockedWords(): Set<String> = try {
        KineticaDb.get(this).blockedWords().allWords().mapTo(HashSet()) { it.lowercase() }
    } catch (e: RuntimeException) {
        Log.w(TAG, "blocked words unavailable", e)
        emptySet()
    }

    /**
     * Blocked spellings for [lang], lower-cased to match the loader's test. An unavailable
     * table is an empty block list, not a failed load: the keyboard has to come up either way.
     */
    private fun blockedWords(lang: String): Set<String> = try {
        KineticaDb.get(this).blockedWords().wordsForLanguage(lang)
            .mapTo(HashSet()) { it.lowercase() }
    } catch (e: RuntimeException) {
        Log.w(TAG, "blocked words unavailable for $lang", e)
        emptySet()
    }

    /** Imported (AOSP-merged) wordlist overrides the bundled asset. */
    private fun openWordlist(lang: String): java.io.InputStream {
        val override = DictionaryStore.wordlistOverride(this, lang)
        return if (override.exists()) override.inputStream() else assets.open(wordlistAsset(lang))
    }

    // Bundled layout names, listed once. The alpha layer falls back to plain qwerty when no
    // qwerty_<lang>.json is bundled, so a new language never inherits another language's
    // accent alternates.
    private val bundledLayouts: Set<String> by lazy {
        (assets.list("layouts") ?: emptyArray())
            .map { it.removeSuffix(".json") }
            .toSet()
    }

    /** Alpha layout with settings-driven mutations applied; the chain is [AlphaLayouts]. */
    private fun alphaLayout(): KeyboardLayout =
        AlphaLayouts.build(layoutFor(AlphaLayouts.name(config, bundledLayouts)), config)

    private fun wordlistAsset(lang: String) = "dictionaries/${lang}_wordlist.txt"
    private fun bigramsAsset(lang: String) = "dictionaries/${lang}_bigrams.txt"

    // ------------------------------------------------------------------ views

    override fun onCreateInputView(): View {
        val bar = SuggestionBarView(this)
        bar.listener = suggestionListener
        bar.flickEnabled = true

        val kv = KeyboardView(this)
        kv.engine = engine
        kv.listener = keyboardListener
        kv.setKeyboardLayout(alphaLayout())
        kv.setShiftUppercase(shift.isShifted)

        suggestionBar = bar
        keyboardView = kv
        emojiPicker = null

        val container = InputContainerView(
            this, bar, kv,
            barHeightPx = dpToPx(barHeightDp()),
            keyboardHeightPx = keyboardHeightPx(),
            minKeyboardPx = minKeyboardPx(),
            maxKeyboardPx = maxKeyboardPx(),
            onHeightCommitted = { px -> persistHeightPct(px) },
            handleHeightPx = dpToPx(config.dragHandleDp.toFloat()),
            bottomGapPx = dpToPx(config.bottomPadDp.toFloat()),
        )
        containerView = container
        applyViewConfig()
        return container
    }

    private fun openEmojiPicker() {
        val container = containerView ?: return
        cancelAutospace()
        finalizePendingWord()
        // Built lazily and guarded: a throw from the picker's construction on this key path
        // kills the service. An optional panel that does not open is the right failure.
        val picker = emojiPicker ?: try {
            EmojiPickerView(
                this,
                onEmoji = { recordEmojiUse(it); commitTracked(it) },
                onBackspace = { onBackspace() },
                onClose = { closeEmojiPicker() },
            ).also { emojiPicker = it }
        } catch (e: RuntimeException) {
            Log.w(TAG, "emoji picker unavailable", e)
            return
        }
        // Built lazily, so it misses the applyViewConfig that ran at startup.
        picker.theme = KeyboardTheme.resolve(
            this, config.themeMode, config.themeColor, config.themeBrightness,
        )
        // On open, not on every pick: the panel stays up while the user taps, and
        // re-ordering the first tab under a moving finger would shift the next cell.
        picker.recents = EmojiRecents.ordered(emojiUses.values)
        container.showEmojiPicker(picker)
    }

    private fun closeEmojiPicker() {
        containerView?.hideEmojiPicker(emojiPicker)
    }

    private fun layoutFor(name: String): KeyboardLayout =
        layouts.getOrPut(name) { LayoutLoader.load(assets, "layouts/$name.json") }

    // Bounds arithmetic lives in KeyboardHeights, which is pure and testable: an inverted
    // min..max range once made coerceIn throw and crashed the process.
    private fun minKeyboardPx(): Int = numberRowPx(
        KeyboardHeights.minPx(resources.displayMetrics.heightPixels, resources.displayMetrics.density),
    )

    private fun maxKeyboardPx(): Int =
        numberRowPx(KeyboardHeights.maxPx(resources.displayMetrics.heightPixels))

    /**
     * The board's height for a letter area of [letterPx]: a row taller with the numbers row, so
     * the height setting and its bounds keep meaning the letters.
     */
    private fun numberRowPx(letterPx: Int): Int = KeyboardHeights.boardPx(letterPx, config.numberRow)

    private fun persistHeightPct(px: Int) {
        val letterPx = KeyboardHeights.letterPx(px, config.numberRow)
        val pct = KeyboardHeights.pctFor(letterPx, resources.displayMetrics.heightPixels)
        val key = if (isLandscape()) Prefs.KEYBOARD_HEIGHT_PCT_LANDSCAPE else Prefs.KEYBOARD_HEIGHT_PCT
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit().putInt(key, pct).apply()
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** Pushes the current [config] and editor-derived flags into the views. */
    private fun applyViewConfig() {
        val kv = keyboardView ?: return
        kv.zenMode = config.zenMode
        // A swipe trail visually leaks what was typed, so a password field never draws
        // one. A no-learning field does: the trail shows the user their own thumb and is
        // gone in 250 ms. In peck mode swipes do nothing, so a trail would advertise a
        // gesture that has no effect.
        kv.trailsEnabled = !editorState.privateMode && !config.peckMode
        // Per field: the key names the action enter will run, and its hold offers a newline
        // whenever enter itself will not write one.
        kv.enterNewlineCell =
            EnterBehavior.resolve(editorState, config.enterAction) is EnterBehavior.Result.Action
        kv.enterLabel = EnterBehavior.labelKind(editorState, config.enterAction)?.let { enterLabelText(it) }
        if (!config.typingSpeed || editorState.privateMode) kv.speedLabel = null
        val theme = KeyboardTheme.resolve(
            this, config.themeMode, config.themeColor, config.themeBrightness,
        )
        kv.theme = theme
        suggestionBar?.theme = theme
        containerView?.applyTheme(theme)
        emojiPicker?.theme = theme
        applyNavigationBarColor(theme)
        kv.trailBaseHue =
            if (config.trailColorMode == "theme") theme.accentHue else config.trailBaseHue
        kv.longPressMs = config.longPressMs
        kv.chordArmMs = config.chordArmMs
        kv.spaceChordArmMs = config.spaceChordArmMs
        kv.layoutMode = config.layoutMode
        kv.landscapeSplitGapPct = config.landscapeSplitGapPct
        kv.landscapeArrangement = if (isLandscape()) config.landscapeArrangement else null
        kv.popupColumns = config.popupColumns
        kv.autospaceDot = config.autospace
        kv.backspaceCharSlide = config.backspaceCharSlide
        kv.spacebarStepDp = config.spacebarStepDp
        kv.spacebarWordSlide = config.spacebarWordSlide
        kv.spacelessSpace = config.spacelessSpace
        kv.doubleSpacePeriod = config.doubleSpacePeriod
        kv.editFromMenu = config.editFromMenu
        kv.languageLabel = spacebarLabel()
        // When enabled, layer the layout-derived implicit alternate
        // swipes under the user/built-in bindings (explicit shadows implicit).
        // Synthesized from the active-language alpha layout, so qwerty_es "n"
        // yields "!" and each language's digits/symbols follow its own keys.
        kv.edgeSwipeBindings = if (config.alternateSwipes) {
            EdgeSwipeBindings.withImplicitAlternates(alphaLayout(), config.edgeSwipes)
        } else {
            config.edgeSwipes
        }
        suggestionBar?.reinforceIncrement = config.reinforceIncrement
        suggestionBar?.reinforceStepDp = config.reinforceStepDp
        suggestionBar?.badgesWhileAdjusting = config.badgesWhileAdjusting
        suggestionBar?.retypeButton = config.retypeButton
        suggestionBar?.retypeButtonDp = config.retypeButtonDp
        // Resolved here, not in the views, so the language cell drops out below two enabled
        // languages on both surfaces alike.
        barActions = ActionRow.resolve(
            config.barActions, config.enabledLanguages.size, ActionRow.ALL.size, config.barActionOrder,
        )
        menuActions = ActionRow.resolve(
            config.menuActions, config.enabledLanguages.size, ActionRow.ALL.size, config.menuActionOrder,
        )
        suggestionBar?.actions = barActions.map { ActionRow.glyph(it) }
        kv.modeMenuCells = menuActions.map { ActionRow.glyph(it) }
        keyboardView?.sidePadDp = config.sidePadDp
        containerView?.setBottomGap(dpToPx(config.bottomPadDp.toFloat()))
        containerView?.setBarHeight(dpToPx(barHeightDp()))
        containerView?.setHeightBounds(minKeyboardPx(), maxKeyboardPx())
        suggestionBar?.tallFactor = if (config.recentWords) KeyboardHeights.RECENT_BAR_TALL else 1f
        containerView?.setHandleHeight(dpToPx(config.dragHandleDp.toFloat()))
        val targetH = keyboardHeightPx()
        val lp = kv.layoutParams
        if (lp != null && lp.height != targetH) {
            lp.height = targetH
            kv.requestLayout()
        }
    }

    /**
     * Paints the system navigation bar to match the keyboard; left alone it is a black band
     * under a themed keyboard.
     *
     * Icon contrast comes from the background's luminance, so it holds for a custom hue too.
     * navigationBarColor is deprecated once targetSdk 35 enforces edge-to-edge; at 34 it still
     * applies, and raising the target is a reproducible-build change of its own.
     */
    private fun applyNavigationBarColor(theme: KeyboardTheme) {
        val w = window?.window ?: return
        @Suppress("DEPRECATION")
        w.navigationBarColor = theme.background
        val lightBackground = Hsv.luminance(theme.background) > 0.5
        WindowInsetsControllerCompat(w, w.decorView)
            .isAppearanceLightNavigationBars = lightBackground
    }

    /**
     * Spacebar status text. The language code shows only with several languages enabled,
     * since alone it carries no information. Peck mode appends a TAP marker so the disabled
     * gesture engine is visible at a glance.
     */
    private fun spacebarLabel(): String? {
        val parts = ArrayList<String>(2)
        if (config.enabledLanguages.size > 1) parts.add(config.language.uppercase())
        if (config.peckMode) parts.add("TAP")
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (keyboardView != null) {
            setInputView(onCreateInputView())
        }
    }

    private fun keyboardHeightPx(): Int = numberRowPx(
        KeyboardHeights.targetPx(
            resources.displayMetrics.heightPixels,
            resources.displayMetrics.density,
            if (isLandscape()) config.heightPctLandscape else config.heightPct,
        ),
    )

    private fun dpToPx(dp: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics,
    ).toInt()

    // ------------------------------------------------------------- lifecycle

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // Ctrl held for the next key belongs to the field it was armed in.
        if (!restarting) ctrlPending = false
        // An app's restartInput lands here too and clears the bar with it.
        DecodeTrace.log {
            "  start restarting=$restarting pending=${tentativeLength > 0 || composer?.hasPendingWord == true}"
        }
        synchronizeLanguageOnStart()
        editorState = EditorState.from(attribute)
        backspaceTarget = null
        abandonWord()
        composer?.reset()
        recentWords.clear()
        typingSpeed.clear()
        mainHandler.removeCallbacks(speedHideRunnable)
        keyboardView?.speedLabel = null
        selectionLedger.clear()
        commitHistory.clear()
        autocorrectUndo = null
        // A field can open with text already selected, so seed from the editor. Either offset
        // is -1 when the editor did not report one, which means no selection.
        val s = attribute?.initialSelStart ?: -1
        val e = attribute?.initialSelEnd ?: -1
        setSelectionCache(s, e)
        updateAutoShift()
        reloadChords()
        reloadEmojiUses()
    }

    /** Mirrors the expansion table into memory, on the database thread. */
    private fun reloadExpansions() {
        dbExecutor.execute {
            val rows = try {
                KineticaDb.get(this).expansions().all()
            } catch (e: RuntimeException) {
                Log.w(TAG, "expansion table unavailable", e)
                emptyList()
            }
            // Every target, in position order: a trigger with several offers them on the bar.
            expansionMap = rows.filter { it.trigger.isNotEmpty() }
                .groupBy { it.trigger }
                .mapValues { (_, r) -> r.sortedBy { it.position }.map { it.target } }
        }
    }

    private fun reloadChords() {
        dbExecutor.execute {
            val rows = try {
                // First, so the old reserved keys are rows before the map is built.
                ChordDefaults.applyTo(this)
                KineticaDb.get(this).chordShortcuts().all()
            } catch (e: RuntimeException) {
                Log.w(TAG, "chord table unavailable", e)
                emptyList()
            }
            val map = HashMap<ChordKey, String>(rows.size * 2)
            for (row in rows) {
                val key = ChordKey.decode(row.chord) ?: continue
                if (row.expansion.isNotEmpty()) map[key] = row.expansion
            }
            chordMap = map
        }
    }

    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        suggestionBar?.clearSuggestions()
        updateBarWordPending()
        keyboardView?.setShiftUppercase(shift.isShifted)
        closeEmojiPicker()
        applyViewConfig()
    }

    override fun onFinishInput() {
        abandonWord()
        composer?.reset()
        // A notice belongs to the field it was shown in, not to the next one.
        clearSpacebarNotice()
        super.onFinishInput()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
        // Recorded on every update, including the ones this service caused: after
        // its own edit the cursor is where the editor says it is, and the delete
        // paths read these offsets.
        setSelectionCache(newSelStart, newSelEnd)
        // A report is the keyboard's own only when it lands where one of its edits put the
        // cursor; counting reports drifted and swallowed the user's moves.
        val verdict = selectionLedger.judge(newSelStart, newSelEnd, SystemClock.uptimeMillis())
        if (verdict == SelectionLedger.Verdict.USER) autocorrectUndo = null
        if (verdict != SelectionLedger.Verdict.USER) {
            DecodeTrace.log {
                val kind = if (verdict == SelectionLedger.Verdict.OWN) "own" else "mismatch"
                "  selection $kind old=$oldSelStart,$oldSelEnd new=$newSelStart,$newSelEnd left=${selectionLedger.pending}"
            }
            return
        }
        // The user moved the cursor themselves: the pending word is no longer under
        // the cursor, abandon it.
        if (tentativeLength > 0 || composer?.hasPendingWord == true) {
            // Printed because it clears a live bar: an editor that reports a move the user
            // never made shows up here.
            DecodeTrace.log {
                "  selection user old=$oldSelStart,$oldSelEnd new=$newSelStart,$newSelEnd abandon len=$tentativeLength"
            }
            abandonWord()
        }
        // ...and if they parked it at the end of a word, reopen that one so it can be extended
        // and the bar offers alternatives for it. reloadWordUnderCursor refuses when a letter
        // follows the cursor, and re-seeds the word as tap anchors: WordComposer.seed decodes,
        // so the alternatives arrive through the ordinary candidates path.
        // The word comes back as letters, not as its gesture, so the alternatives are spelling
        // neighbours of what is written, not the original decode's list.
        if (reopensWordUnderCursor(selectionLength(), newSelStart, newSelEnd)) {
            // Inside a word, the whole word is offered; only at its end is it reopened. The
            // reload alone would have reopened `don` out of `don|'t`, whose guard asks for a
            // letter after the cursor and an apostrophe is none.
            if (!offerMidWord()) reloadWordUnderCursor()
        }
        // The correction strip names the text before the cursor, which a selection is not. A
        // pick would delete text beside the selection and replace the selection too. Only the
        // selection case: a plain cursor move already clears the strip through abandonWord,
        // and widening this would lose the strip in any editor the ledger misjudges.
        if (selectionLength() > 0) clearCorrection()
        refreshPredictions()
    }

    /** Normalized selection; -1 from the editor means "unknown", i.e. none. */
    private fun setSelectionCache(start: Int, end: Int) {
        if (start < 0 || end < 0) {
            selStart = 0
            selEnd = 0
            return
        }
        selStart = minOf(start, end)
        selEnd = maxOf(start, end)
    }

    private fun selectionLength(): Int = selEnd - selStart

    // -------------------------------------------------- engine listener (UI)

    override fun onTokenFinalized(token: InputToken) {
        autocorrectUndo = null
        cancelAutospace()
        clearCorrection()
        // A tapped letter under the one-shot Ctrl is a key combination, not a letter; a swipe
        // is a word and lets Ctrl go.
        if (ctrlPending) {
            if (token is TapToken && consumeCtrl(boardAlphabet.charOf(token.code).toString())) return
            if (token is SwipeToken) {
                ctrlPending = false
                DecodeTrace.log { "  ctrl dropped src=swipe" }
            }
        }
        // A new gesture starts a new word; a kept bar belongs to the one before it.
        keptBar = null
        if (wordStartMs < 0) wordStartMs = token.tStart
        wordEndMs = token.tEnd
        // Peck-type mode: pure literal insertion. Taps commit their letter
        // (shift-aware) and never feed the composer, so there is no pending
        // word, no suggestions, no autocorrect, and no learning; swipes are
        // ignored outright. For out-of-dictionary text the engine mangles.
        if (config.peckMode) {
            if (token is TapToken) {
                val ch = shift.apply(boardAlphabet.charOf(token.code))
                commitTracked(ch.toString())
                if (shift.state == ShiftState.State.SHIFT) {
                    shift.onLetterCommitted()
                    keyboardView?.setShiftUppercase(shift.isShifted)
                }
            }
            return
        }
        val comp = composer
        when (token) {
            is TapToken -> {
                // Long presses never reach here: the view's hold timer cancels the engine
                // pointer and opens the alternates popup.
                //
                // Before anything is committed, because commitTracked clears the flag this
                // reads: a letter straight after a tapped word's autospace says the word was
                // not over, so the space goes and the word comes back. An early space costs
                // no backspace.
                val fusedBase = wordBeforeAutospace(
                    ich.textBeforeCursor(KineticaConstants.MAX_WORD_LEN + 2) ?: "",
                )
                if (retractsAutospace(
                        fromTappedWord = autospaceFromTaps && autospaceInserted,
                        tentativeLength = tentativeLength,
                        elapsedMs = SystemClock.uptimeMillis() - autospaceAt,
                        windowMs = config.autospaceRetractMs,
                        // The word this letter would rejoin, asked of the dictionary, not the
                        // clock. Read from the editor, not lastLiteral, so it agrees with what
                        // reloadWordUnderCursor puts back.
                        fusedIsPrefix = fuseIsLivePrefix(fusedBase, boardAlphabet.charOf(token.code)),
                    )
                ) {
                    // The letter that caused this: the reopened word must land before it.
                    retractAutospace(token.tStart)
                }
                if (tentativeLength == 0) wordShift = shift.state
                val ch = shift.apply(boardAlphabet.charOf(token.code))
                commitTracked(ch.toString())
                tentativeLength += 1
                tentativeWord += ch
                if (shift.state == ShiftState.State.SHIFT) {
                    shift.onLetterCommitted()
                    keyboardView?.setShiftUppercase(shift.isShifted)
                }
                // Cleared here, not at the top of this method: retractAutospace above reloads
                // the word inside this call, so an earlier clear would be undone by the
                // reload and the token's decode would stay suppressed.
                seededWithoutTokens = false
                comp?.onToken(token)
            }
            is SwipeToken -> {
                if (tentativeLength == 0) wordShift = shift.state
                if (comp == null) {
                    Log.w(TAG, "swipe before dictionary ready, dropped")
                    return
                }
                seededWithoutTokens = false
                comp.onToken(token)
                if (shift.state == ShiftState.State.SHIFT) {
                    shift.onLetterCommitted()
                    keyboardView?.setShiftUppercase(shift.isShifted)
                }
            }
        }
    }

    override fun onKeyTransition(streamId: StreamId, code: Int) {
        keyboardView?.onEngineKeyTransition(streamId, code)
    }

    override fun onAllPointersUp() = Unit

    // -------------------------------------------------- composer callbacks

    override fun onCandidates(
        candidates: List<WordCandidate>,
        tentative: WordCandidate?,
        literal: String,
        generation: Int,
    ) {
        val rejected = retypeRejected
        val ranked = demoteRejected(candidates, rejected)
        // Spent on the first decode that has anything to say, so an empty one in between
        // does not waste it.
        if (candidates.isNotEmpty()) retypeRejected = null
        if (ranked !== candidates) DecodeTrace.log { "  retype demote word=$rejected" }
        // The tentative is what reaches the editor, so demoting the list alone would leave
        // a swiped word still committing the rejected one.
        val lead = if (
            rejected != null && tentative != null && tentative.word.equals(rejected, true)
        ) {
            ranked.firstOrNull()
        } else {
            tentative
        }
        onCandidatesRanked(ranked, lead, literal)
    }

    private fun onCandidatesRanked(
        candidates: List<WordCandidate>,
        tentative: WordCandidate?,
        literal: String,
    ) {
        lastCandidates = candidates
        lastTentative = tentative
        lastLiteral = literal
        candidateLanguages = provenanceOf(candidates)
        if (!editorState.privateMode) {
            pushSuggestions()
        }
        val comp = composer ?: return
        // Swipe-bearing words are tentative: the editor shows the candidate the
        // merge cleared for auto-commit. All-tap words keep the literal text.
        if (!comp.hasSwipeToken()) {
            // Every tapped letter looks like the middle of a longer word, so an all-tap word
            // gets a timer only behind its own setting and only when its letters spell a
            // dictionary word. See autospacesTappedWord for the cost and why the space is
            // retractable.
            val joiner =
                ich.textBeforeCursor(tentativeLength + 1)?.dropLast(tentativeLength) ?: ""
            if (autospacesTappedWord(
                    enabled = config.autospaceTappedWords,
                    hasSwipeToken = false,
                    literal = literal,
                    literalIsWord = predictor?.isWord(literal) == true,
                    literalIsStandaloneLetter = standaloneLetter(literal),
                    addressField = editorState.addressField,
                    // The word's own letters are already committed, so the character
                    // before it sits one further back than the word itself.
                    joinedToWhatPrecedes = joinsPrecedingToken(joiner),
                    joinedTokenIsWord = joinedTokenIsWord(),
                    joinedByApostrophe = joinedByApostrophe(joiner),
                    carriesNoToken = seededWithoutTokens,
                ) && !engine.hasActivePointers()
            ) {
                scheduleAutospace()
            }
            return
        }
        if (comp.hasSwipeToken()) {
            if (tentative != null) {
                swipeDecodeEmpty = false
                replaceTentative(displayWord(tentative.word))
                // Autospace arms only once both thumbs are up; any new touch
                // cancels it via onKeyPressFeedback. What else arms it is
                // autospacesSwipedWord's decision, read here and again at the wake.
                if (!engine.hasActivePointers() &&
                    autospacesSwipedWord(
                        hasSwipeToken = true,
                        addressField = editorState.addressField,
                        carriesNoToken = seededWithoutTokens,
                    )
                ) {
                    scheduleAutospace()
                }
            } else {
                // Nothing has earned the editor: the full buffer has no decode (the visible
                // tentative is a stale partial one), or only a non-active language explains
                // it, which WordComposer.merge reads as undecodable. Flag the word so a
                // delimiter neither autospaces nor learns it. The bar keeps whatever
                // candidates exist, still pickable.
                swipeDecodeEmpty = true
                cancelAutospace()
                if (!engine.hasActivePointers()) scheduleStaleTimeout()
            }
        }
    }

    /**
     * Ends a buffer that decodes to nothing, once the user has paused.
     *
     * An empty decode cancels the autospace and cannot re-arm it, so without this the next
     * gesture appends to a dead buffer and fails too: 41% of captured empty decodes contain a
     * pause over 600 ms, against 2% of working ones.
     *
     * Nothing is committed or learned; this clears the composer so the next gesture starts a
     * word. A bar that still has candidates keeps them: see [closeBufferKeepBar].
     *
     * The pause ends it, not the emptiness: a long word typed in pieces decodes to nothing in
     * between. Working decodes have a median inter-token gap of 0 ms and a p90 of 415 ms, so
     * twice the autospace delay (600 ms by default) sits above continuations and below retries.
     */
    private fun scheduleStaleTimeout() {
        if (editorState.privateMode || config.wordEndsOnSpace) return
        cancelStaleTimeout()
        stalePending = true
        // Follows the swipe delay: the buffer this ends is a swipe buffer.
        mainHandler.postDelayed(staleBufferRunnable, staleTimeoutMs(config.autospaceDelayMs))
    }

    private fun cancelStaleTimeout() {
        if (stalePending) {
            mainHandler.removeCallbacks(staleBufferRunnable)
            stalePending = false
        }
    }

    private fun scheduleUserDictReload() {
        cancelUserDictReload()
        userDictReloadPending = true
        mainHandler.postDelayed(userDictReloadRunnable, USER_DICT_RELOAD_DELAY_MS)
    }

    private fun cancelUserDictReload() {
        if (userDictReloadPending) {
            mainHandler.removeCallbacks(userDictReloadRunnable)
            userDictReloadPending = false
        }
    }

    /**
     * Whether [base] plus [next] could still become a word, asked of the whole run and then
     * of the tail after its last apostrophe.
     *
     * The tail makes an elision retractable: `dell'ann` earns a space because `ann` is an
     * entry, and the `o` that follows must take it back, but the wordlists hold no elided
     * forms, so only `anno` answers yes. The whole run is asked first and wins, so `don't`
     * and `it's` keep their retraction.
     */
    private fun fuseIsLivePrefix(base: String, next: Char): Boolean {
        val p = predictor ?: return false
        if (base.isEmpty()) return false
        if (p.isLivePrefix(base + next)) return true
        val tail = tailAfterLastApostrophe(base)
        return tail.isNotEmpty() && p.isLivePrefix(tail + next)
    }

    /** Whether [literal] is one letter that is a word by itself in the active language. */
    private fun standaloneLetter(literal: String): Boolean =
        literal.length == 1 && StandaloneLetters.isWord(literal[0], config.language)

    /**
     * Whether the whole editor token this word belongs to is a word, which lets `don't`
     * autospace although the composer only saw `t`. `isWord` folds accents and checks
     * spellings, so `perche` does not pass as `perché`.
     */
    private fun joinedTokenIsWord(): Boolean {
        val p = predictor ?: return false
        val before = ich.textBeforeCursor(2 * KineticaConstants.MAX_WORD_LEN) ?: return false
        val token = joinedTokenForAutospace(before)
        return token.isNotEmpty() && p.isWord(token)
    }

    /**
     * Arms the automatic space, on the delay belonging to the kind of word in hand.
     *
     * Swipe and tap delays are separate because their mid-word silences differ: the gap
     * between tokens runs to p99 878 ms while swiping against 569 ms while tapping. Both
     * default to 300 ms, since the middles barely differ; the sliders let a thumb find the rest.
     */
    private fun scheduleAutospace() {
        if (!config.autospace || editorState.privateMode || config.wordEndsOnSpace) {
            // Traced so a capture can say why no space came.
            DecodeTrace.log {
                "  autospace off autospace=${config.autospace} wordEnds=${config.wordEndsOnSpace} " +
                    "private=${editorState.privateMode}"
            }
            return
        }
        cancelAutospace()
        autospacePending = true
        val swipe = composer?.hasSwipeToken() == true
        val letter = !swipe && lastLiteral.length == 1
        val delay = when {
            swipe -> config.autospaceDelayMs
            letter -> config.autospaceSingleLetterDelayMs
            else -> config.autospaceTapDelayMs
        }
        val kind = if (swipe) "swipe" else if (letter) "letter" else "tap"
        DecodeTrace.log { "  autospace arm kind=$kind delay=$delay lit=$lastLiteral" }
        mainHandler.postDelayed(autospaceRunnable, delay)
    }

    private fun cancelAutospace() {
        cancelStaleTimeout()
        if (autospacePending) {
            mainHandler.removeCallbacks(autospaceRunnable)
            autospacePending = false
        }
    }

    // -------------------------------------------------------- key handling

    private val keyboardListener = object : KeyboardView.Listener {
        override fun onKeyTap(key: Key) {
            when (key.type) {
                KeyType.CHAR -> onPunctuation(key.output)
                KeyType.SPACE -> onSpace()
                KeyType.BACKSPACE -> onBackspace()
                KeyType.ENTER -> onEnter()
                KeyType.SHIFT -> {
                    shift.onShiftKey()
                    keyboardView?.setShiftUppercase(shift.isShifted)
                }
                // ?123 always lands on page 1, so leaving and re-entering the
                // symbols layer resets any page-2 state.
                KeyType.MODE_SYMBOLS -> keyboardView?.setKeyboardLayout(layoutFor("symbols"))
                KeyType.MODE_SYMBOLS2 -> keyboardView?.setKeyboardLayout(layoutFor("symbols2"))
                KeyType.MODE_ALPHA -> keyboardView?.setKeyboardLayout(alphaLayout())
                KeyType.MODE_NUMPAD -> keyboardView?.setKeyboardLayout(layoutFor("numpad"))
                KeyType.EMOJI -> openEmojiPicker()
            }
        }

        override fun onGeometryChanged(geometry: KeyboardGeometry) {
            currentGeometry = geometry
            predictor?.geometry = geometry
            secondaryPredictor?.geometry = geometry
            extraPredictor?.geometry = geometry
        }

        override fun onCursorMove(direction: Int, byWord: Boolean) {
            // The ledger does not expect the resulting selection change, so onUpdateSelection
            // abandons the pending word.
            if (byWord) {
                // The letter step below moves visually; the word step moves in text order, so
                // in right-to-left text it has to be turned round to go the same way.
                val dir = wordStepDirection(
                    direction,
                    ich.textBeforeCursor(WORD_STEP_LOOK_CHARS) ?: "",
                    ich.textAfterCursor(WORD_STEP_LOOK_CHARS) ?: "",
                )
                if (moveCursorByWord(dir)) return
            }
            sendDownUpKeyEvents(
                if (direction > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT,
            )
        }

        override fun onDeleteChar() = onBackspace()

        override fun onStageDelete(units: Int, chars: Boolean) = stageDeletion(units, chars)

        override fun onCommitStagedDelete() = commitStagedDeletion()

        override fun onEdgeSwipe(output: String) {
            cancelAutospace()
            if (output == "emoji") {
                // Always available: the secondary trigger independent of the
                // comma long-press opt-in.
                openEmojiPicker()
                return
            }
            // The binding editor is a free text field, so a reserved output can be typed into
            // it. It runs here as it does on the chord path, instead of being inserted.
            if (performIfAction(output)) return
            // A shortcut placed first in a letter's list is also its up-swipe.
            ActionRow.actionForGlyph(output)?.let {
                performShortcut(it)
                return
            }
            finalizeThenCommitText(output)
            updateAutoShift()
        }

        override fun onKeyAlternate(key: Key, text: String) {
            cancelAutospace()
            if (text.isEmpty()) return
            // Shift's cells are labels, not text: the key is the command and the cell its
            // argument. So they need no reserved prefix, and this branch must come before
            // every commit path below.
            if (key.type == KeyType.SHIFT) {
                this@KineticaIME.recaseWordInHand(text)
                return
            }
            if (text == LayoutMutations.EMOJI_ALTERNATE) {
                openEmojiPicker()
                return
            }
            // Enter's hold, where enter runs the app's action: the one way left to a newline.
            if (key.type == KeyType.ENTER && text == LayoutMutations.NEWLINE_ALTERNATE) {
                finalizePendingWord()
                commitTracked("\n")
                updateAutoShift()
                return
            }
            // A shortcut's symbol in a letter's list runs the shortcut.
            if (key.isLetter) {
                ActionRow.actionForGlyph(text)?.let {
                    performShortcut(it)
                    return
                }
            }
            // A held letter's accent reaches the composer folded, so without this line a capture
            // cannot tell a long-press from a tap.
            DecodeTrace.log { "  alternate key=${key.output} out=$text" }
            if (!composeAccentedLetter(text)) finalizeThenCommitText(text)
            if (shift.state == ShiftState.State.SHIFT) {
                shift.onLetterCommitted()
                keyboardView?.setShiftUppercase(shift.isShifted)
            }
            updateAutoShift()
        }

        override fun onModeSlide(target: KeyType) {
            when (target) {
                KeyType.MODE_NUMPAD -> keyboardView?.setKeyboardLayout(layoutFor("numpad"))
                KeyType.MODE_ALPHA -> keyboardView?.setKeyboardLayout(alphaLayout())
                else -> Unit
            }
        }

        override fun onSettingsRequested() = openSettings()

        override fun onEditAlternates(letter: Char) {
            vibrateForKeyPress()
            openSettings(SettingsSynonyms.LONGPRESS_GROUP, Prefs.letterAlternatesKey(letter))
        }

        override fun onMenuAction(index: Int) = this@KineticaIME.performMenuAction(index)

        override fun hasChord(trigger: ChordTrigger, key: Char): Boolean =
            chordMap.containsKey(ChordKey(trigger, key))

        override fun onChordTriggered(trigger: ChordTrigger, key: Char, heldMs: Long) {
            val expansion = chordMap[ChordKey(trigger, key)] ?: return
            DecodeTrace.log { "  chord fired trigger=${trigger.name.lowercase()} key=$key held=${heldMs}ms" }
            cancelAutospace()
            // A command goes the shortcut row's way, so it says what it did on the spacebar;
            // anything else inserts as before. A misspelt `action:` is swallowed, never typed.
            val action = EditorAction.of(expansion)
            when {
                action != null -> performShortcut(action)
                !performIfAction(expansion) -> finalizeThenCommitText(expansion)
            }
            updateAutoShift()
        }

        override fun onChordMissed(trigger: ChordTrigger, key: Char, reason: String) {
            // The rollover measurement: a chord candidate that typed instead, and why.
            DecodeTrace.log { "  chord missed trigger=${trigger.name.lowercase()} key=$key reason=$reason" }
        }

        override fun onKeyPressFeedback() {
            cancelAutospace()
            // Any key ends the offer before it acts: the word around the cursor was never in
            // progress, and a letter, a delete or a space at the cursor is not a pick.
            if (midWordOffer != null) {
                DecodeTrace.log { "  midword drop src=key" }
                abandonWord()
            }
            vibrateForKeyPress()
        }

        override fun onDoubleSpace() {
            this@KineticaIME.onDoubleSpace()
        }

        override fun onSpacelessSpace() {
            this@KineticaIME.onSpacelessSpace()
        }
    }

    private val suggestionListener = object : SuggestionBarView.Listener {
        override fun onSuggestionPicked(word: String) =
            this@KineticaIME.onSuggestionPicked(word)

        override fun onCorrectionPicked(replacement: String) =
            this@KineticaIME.onCorrectionPicked(replacement)

        override fun onSuggestionReinforced(word: String, delta: Int) =
            this@KineticaIME.onSuggestionReinforced(word, delta)

        override fun onReinforceStep() {
            if (!expansionsOnBar()) vibrateForKeyPress()
        }

        override fun onSuggestionBlocked(word: String) =
            this@KineticaIME.onSuggestionBlocked(word)

        override fun onRetype() {
            vibrateForKeyPress()
            // Through the shared action: the bar button and a ?123 chord are two triggers for
            // one implementation.
            performIfAction(EditorAction.RETYPE.output)
        }

        override fun onBarAction(index: Int) = this@KineticaIME.performBarAction(index)

        override fun onRecentPicked(back: Int, word: String) {
            vibrateForKeyPress()
            this@KineticaIME.onRecentPicked(back, word)
        }
    }

    /**
     * Tells the bar whether a word is in progress, which suppresses the shortcut row. Called
     * from the paths that move word state, not inferred from which setter ran last: a state
     * machine built from setter side effects breaks when the setters reorder.
     */
    private fun updateBarWordPending() {
        suggestionBar?.wordPending =
            tentativeLength > 0 || composer?.hasPendingWord == true
        refreshPredictions()
    }

    /** What the idle bar is offering as next-word predictions; empty when it offers none. */
    private var barPredictions: List<WordPredictor.NextWord> = emptyList()

    /** The bar's height: one row, or taller while recent words share it. */
    private fun barHeightDp(): Float = KeyboardHeights.barDp(config.suggestionBarDp, config.recentWords)

    /** The last few commits and what each beat, for the recent-words bar. */
    private val recentWords = RecentWords(RECENT_WORDS_KEPT)

    /**
     * Puts the recent words the editor still holds on the bar, oldest first, or takes them
     * down. The correction strip already shows the newest commit, so that one is left out
     * while it is up.
     */
    private fun pushRecent() {
        val bar = suggestionBar ?: return
        val entries = recentWords.entries()
        // With a selection up, the text before the cursor is not what the columns name, and a
        // rewrite would replace the selection too (the reason the strip clears there).
        if (!config.recentWords || editorState.privateMode || entries.isEmpty() || selectionLength() != 0) {
            bar.recent = emptyList()
            return
        }
        val before = ich.textBeforeCursor(RECENT_TAIL_CHARS) ?: ""
        val aligned = alignRecent(before, tentativeLength, entries.map { it.word })
        val skip = if (lastCommit.stripWord != null) 1 else 0
        val cols = ArrayList<SuggestionBarView.RecentColumn>(RECENT_BAR_COLUMNS)
        for (back in skip until minOf(aligned.size, skip + RECENT_BAR_COLUMNS)) {
            val e = entries[entries.size - 1 - back]
            val shown = before.subSequence(before.length - aligned[back], before.length - aligned[back] + e.word.length)
            val alts = notBlocked(sameLanguage(e.alternatives, e.languages, e.word, config.language), blockedSpellings)
            cols.add(
                0,
                SuggestionBarView.RecentColumn(
                    shown.toString(),
                    alts.getOrNull(0)?.let { recentAlternate(shown, it, e.languages) },
                    alts.getOrNull(1)?.let { recentAlternate(shown, it, e.languages) },
                    back,
                    // The counts behind the badges and the slide, each in its own language.
                    listOf(alts.getOrNull(0), e.word, alts.getOrNull(1)).map { w ->
                        if (w == null) 0 else countsFor(e.languages[w.lowercase()] ?: languageOf(w))[w.lowercase()] ?: 0
                    },
                ),
            )
        }
        bar.recent = cols
    }

    /** A recent word's alternative as its column shows it: cased like the word, then the pronoun rule. */
    private fun recentAlternate(written: CharSequence, alt: String, languages: Map<String, String>): String =
        AutoCapitalization.forWord(matchCase(written, alt), config.language, languages[alt.lowercase()] ?: config.language)

    /**
     * Swaps a recent word for one it beat, where the editor still holds it: the text from the
     * word to the cursor is rewritten with the word replaced, as a correction pick rewrites the
     * last one. The word in progress, if any, is part of that tail and comes back unchanged.
     */
    private fun onRecentPicked(back: Int, word: String) {
        if (selectionLength() != 0) {
            pushRecent()
            return
        }
        val entries = recentWords.entries()
        val before = ich.textBeforeCursor(RECENT_TAIL_CHARS) ?: ""
        val aligned = alignRecent(before, tentativeLength, entries.map { it.word })
        if (back !in aligned.indices) {
            DecodeTrace.log { "  recent refused back=$back" }
            pushRecent()
            return
        }
        val entry = entries[entries.size - 1 - back]
        val span = aligned[back]
        val start = before.length - span
        val replacement = matchCase(before.subSequence(start, start + entry.word.length), word)
        val rewritten = recentRewrite(before, span, entry.word.length, replacement)
        commitHistory.replaced(cursorExpected() - span, entry.word, replacement)
        ich.replaceBeforeCursor(span, rewritten)
        expectAfter(span, rewritten.length, rewritesWord = true)
        recentWords.onReplaced(back, replacement)
        if (back == 0) lastCommit.onReplaced(replacement)
        composer?.replaceCommit(back, entry.word.lowercase(), replacement.lowercase())
        DecodeTrace.log { "  recent pick back=$back old=${entry.word} new=$replacement" }
        // The swap is the user's correction, so the weight moves as a correction pick's does,
        // and the pairs with its neighbours move with it.
        val lang = sharedFiling.languageFor(heldBy(replacement), entry.languages[word.lowercase()] ?: languageOf(replacement))
        learnWord(replacement, lang = lang)
        if (!entry.word.equals(replacement, ignoreCase = true)) unlearnWord(entry.word)
        // A neighbour counts only across whitespace: `end. Next` is no pair, as learnPair agrees.
        val prev = if (back + 1 < aligned.size) {
            val p = entries[entries.size - 2 - back].word
            val gap = before.subSequence(before.length - aligned[back + 1] + p.length, start)
            p.takeIf { gap.isNotEmpty() && gap.all { it == ' ' } }
        } else {
            null
        }
        val next = if (back >= 1) {
            val nextStart = before.length - aligned[back - 1]
            val gap = before.subSequence(start + entry.word.length, nextStart)
            entries[entries.size - back].word.takeIf { gap.isNotEmpty() && gap.all { it == ' ' } }
        } else {
            null
        }
        // The pairs taken back were learned with the old word, in its language.
        val oldLang = entry.languages[entry.word.lowercase()] ?: languageOf(entry.word)
        for (m in recentPairMoves(prev, entry.word, replacement, next)) {
            adjustPair(m.prev, m.word, pairMoveLanguage(m, oldLang, lang), m.delta)
        }
        pushRecent()
    }

    /**
     * Fills the idle bar with what usually follows the word before the cursor, or takes the
     * predictions down once the bar is not idle. A word in progress, its candidates, the
     * correction strip and a kept bar all come first, in that order.
     */
    private fun refreshPredictions() {
        // An offer of expansion targets lasts while the bar shows it, not until a pick.
        expansionChoice?.let { if (suggestionBar?.showsWords(it.shown) != true) expansionChoice = null }
        pushRecent()
        val bar = suggestionBar ?: return
        val quiet = lastCandidates.isEmpty() && lastCommit.stripWord == null && keptBar == null &&
            expansionChoice == null
        val idle = quiet && config.nextWord && !editorState.privateMode && tentativeLength == 0 &&
            composer?.hasPendingWord != true
        if (!idle) {
            if (barPredictions.isNotEmpty()) {
                barPredictions = emptyList()
                if (quiet) bar.clearSuggestions()
            }
            return
        }
        val prev = previousWordForPrediction(ich.textBeforeCursor(PREDICT_TAIL_CHARS))
        val found = if (prev == null) emptyList() else predictNext(prev)
        // The pronoun's own capital, which a prediction never went through displayWord for.
        val shown = found.map { AutoCapitalization.forWord(it.word, config.language, it.language.ifEmpty { config.language }) }
        // Against what the bar shows now: it can be cleared behind these predictions' back.
        if (found.isNotEmpty() && bar.showsWords(shown)) {
            barPredictions = found
            return
        }
        barPredictions = found
        if (found.isEmpty()) {
            bar.clearSuggestions()
            return
        }
        candidateLanguages = found.filter { it.language.isNotEmpty() }
            .associate { it.word.lowercase() to it.language }
        bar.setSuggestions(shown.map { barSuggestion(it) })
        DecodeTrace.log { "  predict next prev=$prev n=${found.size} first=${found.first().word}" }
    }

    /**
     * Both resident languages, strongest first. A learned pair only counts where the field
     * allows learning at all, so an app that asks not to be learned from never sees one.
     */
    private fun predictNext(prev: String): List<WordPredictor.NextWord> {
        val personal = !editorState.teachesNothing
        val two = mergeNextWords(
            predictor?.nextWords(prev, KineticaConstants.TOP_K, personal).orEmpty(),
            secondaryPredictor?.nextWords(prev, KineticaConstants.TOP_K, personal).orEmpty(),
            KineticaConstants.TOP_K,
        )
        val third = extraPredictor ?: return two
        return mergeNextWords(two, third.nextWords(prev, KineticaConstants.TOP_K, personal), KineticaConstants.TOP_K)
    }

    /** Current candidates -> suggestion bar, with personal-weight badges. */
    private fun pushSuggestions() {
        // The all-tap literal rides along as a last zone, the one-tap way to commit an
        // out-of-dictionary word verbatim (as commitWordInternal does in correction mode). A
        // pick goes through onSuggestionPicked, which bypasses finalizePendingWord, so
        // autocorrect never touches it.
        // The literal is rebuilt from folded key codes, so it would offer "matador" for a
        // typed "matadór". When the two agree up to folding, what the user typed wins.
        val typed = when {
            composer?.hasSwipeToken() == true -> ""
            tentativeLength > 0 &&
                AccentFolder.fold(tentativeWord.lowercase()) == lastLiteral -> tentativeWord
            else -> displayWord(lastLiteral)
        }
        val literal = literalZone(
            typed,
            autocorrects = config.autocorrectConfidence != null,
            leavesAccentsOff = predictor?.leavesAccentsOff(typed) == true,
            blocked = typed.lowercase() in blockedSpellings,
        )
        val decoded = lastCandidates.map { displayWord(it.word) }
        suggestionBar?.setSuggestions(
            suggestionZoneWords(notBlocked(midWordWords(decoded, midWordHistory, midWordOffer), blockedSpellings), literal)
                .map { barSuggestion(it) },
        )
        updateBarWordPending()
    }

    private fun barSuggestion(display: String): SuggestionBarView.Suggestion {
        val count = countsFor(languageOf(display))[display.lowercase()] ?: 0
        return SuggestionBarView.Suggestion(
            display,
            tier = KineticaConstants.personalTier(count),
            count = count,
        )
    }

    /**
     * True while the bar shows expansion targets: they are not dictionary words, so the weight
     * slide and the block have nothing to act on there.
     */
    private fun expansionsOnBar(): Boolean =
        expansionChoice?.let { suggestionBar?.showsWords(it.shown) == true } == true

    /**
     * Puts back the letters an autocorrect replaced, when the backspace comes right after it: the
     * corrected word and at most the space or mark that followed. The corrected word's +1 is
     * taken back, as a retype does.
     */
    private fun undoAutocorrect(): Boolean {
        val undo = autocorrectUndo ?: return false
        autocorrectUndo = null
        if (selectionLength() != 0) return false
        val after = cursorExpected() - undo.end
        val before = ich.textBeforeCursor(undo.corrected.length + 1) ?: return false
        val span = autocorrectUndoSpan(before, undo.corrected, after)
        if (span < 0) return false
        abandonWord()
        ich.replaceBeforeCursor(span, undo.typed)
        expectAfter(span, undo.typed.length)
        autospaceInserted = false
        unlearnWord(undo.corrected)
        DecodeTrace.log { "  autocorrect undo word=${undo.corrected} typed=${undo.typed}" }
        updateAutoShift()
        return true
    }

    /**
     * The weight slide travelled past the bottom of its scale: block [word].
     *
     * - One row per enabled language: a word held by two dictionaries otherwise came back
     *   from the other. The table stays per language so blocking a
     *   junk English name keeps a real Italian word spelled the same.
     * - The user_words row goes too, or it would restore the weight on an unblock.
     * - Gated on [EditorState.teachesNothing] like every learning path: a word written to the
     *   database from a password field is a disclosure.
     */
    private fun onSuggestionBlocked(word: String) {
        if (editorState.teachesNothing || word.isEmpty()) return
        if (expansionsOnBar()) {
            DecodeTrace.log { "  weight refused word=$word src=expansion" }
            return
        }
        val lower = word.lowercase()
        val langs = (config.enabledLanguages + config.language).distinct()
        val now = System.currentTimeMillis()
        // Drop it from the live count maps now, so the badge and any personal boost stop
        // before the trie is rebuilt. Only resident languages have a live map.
        for (lang in langs) countsFor(lang).remove(lower)
        blockedSpellings = blockedSpellings + lower
        lastCandidates = lastCandidates.filterNot { it.word.lowercase() == lower }
        DecodeTrace.log { "  block word=$lower langs=${langs.joinToString(",")}" }
        vibrateForKeyPress()
        dbExecutor.execute {
            try {
                val db = KineticaDb.get(this)
                for (lang in langs) {
                    db.blockedWords().block(lower, lang, now)
                    db.userWords().delete(lower, lang)
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "could not block $lower", e)
                return@execute
            }
            // Queued behind the write on the same executor: a reload that overtakes
            // its write rebuilds the trie from stale rows and the word comes back.
            mainHandler.post { loadDictionaryAsync() }
        }
        pushSuggestions()
    }

    /** Long-press weight adjustment from the bar: signed, scaled by config. */
    private fun onSuggestionReinforced(word: String, delta: Int) {
        if (editorState.teachesNothing || delta == 0) return
        if (expansionsOnBar()) {
            DecodeTrace.log { "  weight refused word=$word src=expansion" }
            return
        }
        // A recent word learns in the language it was decoded in, as its swap does.
        val lang = recentWords.languageOf(word) ?: languageOf(word)
        DecodeTrace.log { "  weight slide word=$word delta=$delta lang=$lang" }
        learnWord(word, delta, lang)
        vibrateForKeyPress()
        // Redraw so the adjusted badge appears under the finger, on the columns too.
        pushSuggestions()
        pushRecent()
    }

    /**
     * Runs [text] as an editor command when it names one, and reports whether it did. Every
     * surface that can hold a command goes through here, so none inserts "action:paste" as text.
     *
     * The pending word is settled first so the command applies to finished content. A string
     * with the reserved prefix but no command is swallowed: it is a typo in an expansion, and
     * typing it is the worse answer. RETYPE runs before the settling, since the pending word
     * is what it aims at.
     */
    private fun performIfAction(text: String): Boolean {
        // A key combination is a target too, wherever an action name is.
        KeyCombo.parse(text)?.let {
            sendKeyCombo(it)
            return true
        }
        val action = EditorAction.of(text)
        if (action == null) return EditorAction.isUnknownAction(text)
        // Undo and redo are context-menu commands, so the app does the work and the keyboard
        // keeps no history. performContextMenuAction reports delivery, not effect, so an editor
        // without an undo stack is a silent no-op, the same contract as paste.
        val menuId = when (action) {
            EditorAction.PASTE -> android.R.id.paste
            EditorAction.COPY -> android.R.id.copy
            EditorAction.CUT -> android.R.id.cut
            EditorAction.SELECT_ALL -> android.R.id.selectAll
            EditorAction.UNDO -> android.R.id.undo
            EditorAction.REDO -> android.R.id.redo
            EditorAction.RETYPE, EditorAction.EXPANDIFY, EditorAction.SETTINGS,
            EditorAction.NEXT_LANGUAGE, EditorAction.TOGGLE_AUTOSPACE,
            EditorAction.ONE_HANDED, EditorAction.DATE, EditorAction.TIME,
            EditorAction.ENTER, EditorAction.COPY_LINE, EditorAction.TOGGLE_NEXT_WORD,
            EditorAction.TOGGLE_RECENT_WORDS, EditorAction.TOGGLE_NUMBER_ROW,
            EditorAction.TOGGLE_TIDY_SPACES, EditorAction.TOGGLE_TYPING_SPEED,
            EditorAction.TOGGLE_PECK_MODE,
            EditorAction.TAB, EditorAction.ESCAPE, EditorAction.FORWARD_DELETE, EditorAction.HOME,
            EditorAction.END, EditorAction.ARROW_UP, EditorAction.ARROW_DOWN, EditorAction.ARROW_LEFT,
            EditorAction.ARROW_RIGHT, EditorAction.PAGE_UP, EditorAction.PAGE_DOWN,
            EditorAction.BACKSPACE, EditorAction.CTRL_NEXT,
            -> null
        }
        if (menuId == null) {
            performLocalAction(action)
            return true
        }
        finalizePendingWord()
        ich.performContextMenuAction(menuId)
        selectionLedger.expectAny(SystemClock.uptimeMillis())
        return true
    }

    /**
     * The actions the keyboard carries out itself instead of asking the app.
     *
     * RETYPE owns the pending word, so it runs before the word is settled; EXPANDIFY reads
     * what the editor holds, so it settles first. The toggles change the keyboard, not the
     * text: each is a preference write, and the listener rebuilds the config.
     */
    private fun performLocalAction(action: EditorAction) {
        when (action) {
            EditorAction.RETYPE -> retypeCurrentWord()
            EditorAction.EXPANDIFY -> expandifyAtCursor()
            EditorAction.SETTINGS -> openSettings()
            EditorAction.NEXT_LANGUAGE -> cycleLanguage()
            EditorAction.TOGGLE_AUTOSPACE -> toggleAutospace()
            EditorAction.ONE_HANDED -> toggleOneHanded()
            EditorAction.DATE -> insertNow(date = true)
            EditorAction.TIME -> insertNow(date = false)
            EditorAction.ENTER -> onEnter()
            EditorAction.COPY_LINE -> copyLine()
            EditorAction.TOGGLE_NEXT_WORD -> togglePref(Prefs.NEXT_WORD, !config.nextWord)
            EditorAction.TOGGLE_RECENT_WORDS -> togglePref(Prefs.RECENT_WORDS, !config.recentWords)
            EditorAction.TOGGLE_NUMBER_ROW -> togglePref(Prefs.NUMBER_ROW, !config.numberRow)
            EditorAction.TOGGLE_TIDY_SPACES -> togglePref(Prefs.TIDY_SPACES, !config.tidySpaces)
            EditorAction.TOGGLE_TYPING_SPEED -> togglePref(Prefs.TYPING_SPEED, !config.typingSpeed)
            EditorAction.TOGGLE_PECK_MODE -> togglePeckMode()
            EditorAction.TAB, EditorAction.ESCAPE, EditorAction.FORWARD_DELETE, EditorAction.HOME,
            EditorAction.END, EditorAction.ARROW_UP, EditorAction.ARROW_DOWN, EditorAction.ARROW_LEFT,
            EditorAction.ARROW_RIGHT, EditorAction.PAGE_UP, EditorAction.PAGE_DOWN,
            -> sendSpecialKey(action)
            EditorAction.BACKSPACE -> onBackspace()
            EditorAction.CTRL_NEXT -> {
                ctrlPending = !ctrlPending
                DecodeTrace.log { "  ctrl pending=$ctrlPending" }
            }
            // Every context-menu action is handled by the caller.
            else -> Unit
        }
    }

    /** A key with modifiers, as a hardware keyboard sends it, once the word is settled. */
    private fun sendKeyCombo(combo: KeyCombo) {
        cancelAutospace()
        finalizePendingWord()
        DecodeTrace.log { "  combo sent keys=${combo.encode()}" }
        ich.sendKey(combo.keyCode(), combo.metaState())
        updateAutoShift()
    }

    /**
     * The one-shot Ctrl: when it is held, [key] goes out as Ctrl+key instead of being typed.
     * True when it went, false when Ctrl was not held or the key has no code to send.
     */
    private fun consumeCtrl(key: String): Boolean {
        if (!ctrlPending) return false
        ctrlPending = false
        val combo = KeyCombo.ctrlOf(key)
        if (combo == null) {
            DecodeTrace.log { "  ctrl dropped key=$key" }
            return false
        }
        sendKeyCombo(combo)
        return true
    }

    /**
     * A key the keyboard has no key for, as a key event, after the word is settled so the event
     * lands behind it. A cursor the event moves reaches onUpdateSelection as the user's own move.
     */
    private fun sendSpecialKey(action: EditorAction) {
        SpecialKeys.comboKeyFor(action)?.let { if (consumeCtrl(it)) return }
        val code = SpecialKeys.keyCodeFor(action) ?: return
        cancelAutospace()
        finalizePendingWord()
        DecodeTrace.log { "  special key action=${action.name}" }
        sendDownUpKeyEvents(code)
        updateAutoShift()
    }

    /**
     * Today's date or the time, in the format the phone is set to, so a 24-hour phone
     * gets 24-hour time without a setting of our own.
     */
    private fun insertNow(date: Boolean) {
        cancelAutospace()
        finalizePendingWord()
        val now = java.util.Date()
        val text = if (date) {
            android.text.format.DateFormat.getDateFormat(this).format(now)
        } else {
            android.text.format.DateFormat.getTimeFormat(this).format(now)
        }
        commitTracked(text)
        updateAutoShift()
    }

    /**
     * The line the cursor is on, onto the clipboard (#19). Read out of the editor in this
     * call and handed to the clipboard, so nothing is selected and no remembered offset is
     * trusted. A line longer than the read is refused, not half copied, and a private field
     * is never read for it.
     */
    private fun copyLine() {
        cancelAutospace()
        finalizePendingWord()
        if (editorState.privateMode) return
        val before = ich.textBeforeCursor(COPY_LINE_READ_CHARS) ?: return
        val after = ich.textAfterCursor(COPY_LINE_READ_CHARS) ?: return
        val selected = if (selectionLength() > 0) ich.selectedText() ?: return else ""
        val line = lineAroundCursor(before, selected, after, COPY_LINE_READ_CHARS)
        DecodeTrace.log { "  copy line len=${line?.length ?: "refused"}" }
        if (line.isNullOrBlank()) {
            vibrateForKeyPress()
            return
        }
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.clip_label_line), line))
    }

    /** Opens settings, at [screen] with [reveal] scrolled to and flashed when given. */
    private fun openSettings(screen: String? = null, reveal: String? = null) {
        cancelAutospace()
        finalizePendingWord()
        requestHideSelf(0)
        startActivity(
            Intent(this@KineticaIME, SettingsActivity::class.java).apply {
                // A service context has no activity task to attach to.
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (screen != null) putExtra(SettingsActivity.EXTRA_SCREEN, screen)
                if (reveal != null) putExtra(SettingsActivity.EXTRA_REVEAL, reveal)
            },
        )
    }

    /** A shortcut chosen from the suggestion bar's row, by its index into [barActions]. */
    private fun performBarAction(index: Int) = performShortcut(barActions.getOrNull(index))

    /** A shortcut chosen from the ?123 hold menu, by its index into [menuActions]. */
    private fun performMenuAction(index: Int) = performShortcut(menuActions.getOrNull(index))

    private fun performShortcut(action: EditorAction?) {
        if (action == null) return
        // Decided before the action runs: the toggles write a preference, and the notice
        // says what that write leaves behind.
        val notice = ActionRow.notice(action, shortcutState())
        // No buzz here: the actions that change the keyboard buzz themselves, and the editor
        // commands are silent on every other route, so paste feels the same from the row.
        performIfAction(action.output)
        notice?.let { noticeText(it) }?.let { showSpacebarNotice(it) }
    }

    private fun shortcutState(): ActionRow.KeyboardState {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return ActionRow.KeyboardState(
            autospace = config.autospace,
            languages = config.enabledLanguages,
            language = config.language,
            layoutMode = prefs.getString(Prefs.LAYOUT_MODE, Prefs.DEFAULT_LAYOUT_MODE)
                ?: Prefs.DEFAULT_LAYOUT_MODE,
            rememberedOneHanded = prefs.getString(Prefs.LAYOUT_MODE_ONE_HANDED, null),
            nextWord = config.nextWord,
            recentWords = config.recentWords,
            numberRow = config.numberRow,
            tidySpaces = config.tidySpaces,
            typingSpeed = config.typingSpeed,
            peckMode = config.peckMode,
            ctrlPending = ctrlPending,
        )
    }

    private fun noticeText(notice: ActionRow.Notice): String? = when (notice) {
        is ActionRow.Notice.Sent -> ActionLabels.noticeRes(notice.action)?.let { getString(it) }
        is ActionRow.Notice.Autospace -> getString(
            if (notice.on) R.string.notice_autospace_on else R.string.notice_autospace_off,
        )
        is ActionRow.Notice.Toggle -> ActionLabels.toggleNameRes(notice.action)?.let {
            getString(if (notice.on) R.string.notice_toggle_on else R.string.notice_toggle_off, getString(it))
        }
        is ActionRow.Notice.Language ->
            entryFor(R.array.language_values, R.array.language_entries, notice.code)
        is ActionRow.Notice.Layout ->
            entryFor(R.array.layout_mode_values, R.array.layout_mode_entries, notice.mode)
    }

    /** The settings list's own wording for [value], so the spacebar and the chooser agree. */
    private fun entryFor(valuesRes: Int, entriesRes: Int, value: String): String {
        val values = resources.getStringArray(valuesRes)
        val entries = resources.getStringArray(entriesRes)
        return entries.getOrNull(values.indexOf(value)) ?: value
    }

    /**
     * Shows [text] on the spacebar for [SPACEBAR_NOTICE_MS]. A second notice replaces the
     * first and restarts the clock, so two quick taps never leave a stale one behind.
     */
    private fun showSpacebarNotice(text: String) {
        mainHandler.removeCallbacks(spacebarNoticeRunnable)
        keyboardView?.spacebarNotice = text
        mainHandler.postDelayed(spacebarNoticeRunnable, SPACEBAR_NOTICE_MS)
    }

    private fun clearSpacebarNotice() {
        mainHandler.removeCallbacks(spacebarNoticeRunnable)
        keyboardView?.spacebarNotice = null
    }

    /** A boolean setting written from the keyboard; the preference listener does the rest. */
    private fun togglePref(key: String, value: Boolean) {
        vibrateForKeyPress()
        PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean(key, value).apply()
    }

    /** Autospace on or off for good. The spacebar dot follows through applyViewConfig. */
    private fun toggleAutospace() {
        vibrateForKeyPress()
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit().putBoolean(Prefs.AUTOSPACE, !config.autospace).apply()
    }

    /**
     * One-handed on or off, remembering which side.
     *
     * Toggling to a fixed mode would take a left-hander back to the right-hand default every
     * time, so the mode being left is stored and handed back. The hand is part of LayoutMode's
     * value, so remembering it takes a second preference.
     */
    private fun toggleOneHanded() {
        vibrateForKeyPress()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val current = prefs.getString(Prefs.LAYOUT_MODE, Prefs.DEFAULT_LAYOUT_MODE)
            ?: Prefs.DEFAULT_LAYOUT_MODE
        val remembered = prefs.getString(Prefs.LAYOUT_MODE_ONE_HANDED, null)
        val next = ActionRow.oneHandedToggle(current, remembered)
        val edit = prefs.edit().putString(Prefs.LAYOUT_MODE, next.mode)
        if (next.remember != null) edit.putString(Prefs.LAYOUT_MODE_ONE_HANDED, next.remember)
        edit.apply()
    }

    /**
     * Replaces the trigger at the cursor with its stored expansion (expandify).
     *
     * The word in progress is settled first and the trigger is read from the editor, so the
     * whole span comes from one read. A target may itself be a trigger, so firing
     * again continues a chain, and a chain back to its start is a loop; neither needs code here.
     */
    private fun expandifyAtCursor() {
        cancelAutospace()
        finalizePendingWord()
        val before = ich.textBeforeCursor(MAX_TRIGGER_CHARS + 2) ?: ""
        val found = triggerAtCursor(before, MAX_TRIGGER_CHARS)
        val targets = expansionMap[found.trigger]
        if (found.trigger.isEmpty() || targets.isNullOrEmpty()) {
            // Nothing is typed for a miss: an unset trigger is the commonest case while the
            // user learns the feature, and inserting something is the worse answer.
            DecodeTrace.log { "  expandify miss trigger=${found.trigger}" }
            vibrateForKeyPress()
            return
        }
        if (targets.size > 1) {
            offerExpansions(found.trigger, targets)
            return
        }
        applyExpansion(found, before, targets[0])
    }

    /**
     * The targets of one trigger, on the bar, for a tap to choose (#19). The
     * trigger stays written until then, so a choice left unpicked costs nothing.
     */
    private fun offerExpansions(trigger: String, targets: List<String>) {
        val shown = ExpansionRows.distinctLabels(
            targets.map { t -> ExpansionRows.shown(t, EXPANSION_PICK_CHARS) { getString(ActionLabels.labelRes(it)) } },
        )
        expansionChoice = ExpansionChoice(trigger, targets, shown)
        suggestionBar?.setSuggestions(shown.map { SuggestionBarView.Suggestion(it, 0) })
        DecodeTrace.log { "  expandify offer trigger=$trigger n=${targets.size}" }
    }

    /** A pick from [offerExpansions]: applied only while the editor still ends in the trigger. */
    private fun pickExpansion(choice: ExpansionChoice, index: Int) {
        val before = ich.textBeforeCursor(MAX_TRIGGER_CHARS + 2) ?: ""
        val found = triggerAtCursor(before, MAX_TRIGGER_CHARS)
        suggestionBar?.clearSuggestions()
        if (found.trigger != choice.trigger) {
            DecodeTrace.log { "  expandify pick refused trigger=${choice.trigger}" }
            return
        }
        applyExpansion(found, before, choice.targets[index])
    }

    /** Replaces the trigger [found] in [before] with [target], or runs it when it is an action. */
    private fun applyExpansion(found: TriggerSpan, before: CharSequence, target: String) {
        val text = when (val effect = expansionEffect(target)) {
            is ExpansionEffect.Refused -> {
                // Refused before anything is deleted, so the trigger is still there to fix.
                DecodeTrace.log { "  expandify refused trigger=${found.trigger} why=${effect.why}" }
                vibrateForKeyPress()
                return
            }
            is ExpansionEffect.Action -> {
                // The trigger and its space go, then the action runs where the trigger was.
                ich.deleteBeforeCursor(found.span)
                expectAfter(found.span, 0)
                DecodeTrace.log {
                    "  expandify trigger=${found.trigger} span=${found.span} action=${effect.action.name}"
                }
                abandonWord()
                forgetAutospace()
                performIfAction(effect.action.output)
                updateAutoShift()
                return
            }
            is ExpansionEffect.Combo -> {
                ich.deleteBeforeCursor(found.span)
                expectAfter(found.span, 0)
                DecodeTrace.log {
                    "  expandify trigger=${found.trigger} span=${found.span} combo=${effect.combo.encode()}"
                }
                abandonWord()
                forgetAutospace()
                sendKeyCombo(effect.combo)
                updateAutoShift()
                return
            }
            is ExpansionEffect.Text -> expansionForField(effect.text, editorState.multiline)
        }
        val tail = before.subSequence(
            before.length - found.span + found.trigger.length, before.length,
        )
        ich.replaceBeforeCursor(found.span, text + tail)
        expectAfter(found.span, text.length + tail.length)
        DecodeTrace.log {
            "  expandify trigger=${found.trigger} span=${found.span} len=${text.length}"
        }
        // The expansion is finished text, not a word in progress: nothing may autocorrect
        // it, autospace after it or learn it.
        abandonWord()
        forgetAutospace()
        updateAutoShift()
    }

    /**
     * Deletes the word in progress, or else the word just committed with what the keyboard put
     * after it, or else the letters before the cursor, and leaves the cursor in place so the
     * word can be gestured again.
     *
     * The commit case is the common one: autospace commits fast, so a wrong word is usually
     * finished by the time it is noticed. `retypeSpan` decides which, purely.
     */
    private fun retypeCurrentWord() {
        cancelAutospace()
        // The bar's button, which no key touch precedes: the offer goes first, so the retype
        // sees the cursor as it would have without one.
        if (midWordOffer != null) abandonWord()
        // The button is the user saying the last commit was wrong, the only correctness
        // signal this keyboard gets. A pair learned from that commit comes back out.
        unlearnLastPair()
        // The same guard the reload uses: with a letter after the cursor the user is parked
        // inside a word, and nothing here should guess which half they meant.
        val after = ich.textAfterCursor(1)
        val midWord = after != null && after.isNotEmpty() && after[0].isLetter()
        val run = if (midWord) {
            ""
        } else {
            trailingLetterRun(ich.textBeforeCursor(KineticaConstants.MAX_WORD_LEN + 1) ?: "")
        }
        // Same read as the recase and for the same reason: a remembered length deleted
        // into the word instead of past it.
        val committed = lastCommit.retypeWord
        val offer = if (committed == null) emptyList() else lastCommit.offerAfterRetype(committed, KineticaConstants.TOP_K)
        val offerLanguages = lastCommit.languages
        val committedSpan = if (committed == null) {
            -1
        } else {
            val before = ich.textBeforeCursor(committed.length + COMMIT_TAIL_CHARS) ?: ""
            commitSpan(before, committed, COMMIT_TAIL_CHARS)
        }
        val span = retypeSpan(tentativeLength, committedSpan, run)
        val src = retypeSource(tentativeLength, committedSpan)
        DecodeTrace.log {
            "  retype span=$span src=$src" + (if (midWord) " midword" else "")
        }
        // ...and the same for the word. A rejected commit that keeps its weight unit makes the
        // word the user fights stronger with each attempt: `biologa` climbed pb 1.10 -> 1.24
        // while retyped, against `biologia`, thirteen times more frequent.
        // Only the commit case, 37 of 41 retypes in that capture: the other cases have no
        // committed word, and 85% of retypes reject the word itself.
        if (src == "commit") committed?.let { unlearnWord(it) }
        // Armed only for a commit retype: the tentative and cursor cases have no committed
        // word the user can be said to have rejected.
        retypeRejected = if (config.retypeAvoidsRejected && src == "commit") {
            committed
        } else {
            null
        }
        if (span <= 0) {
            abandonWord()
            return
        }
        ich.deleteBeforeCursor(span)
        expectAfter(span, 0)
        if (src == "commit") recentWords.dropNewest()
        abandonWord()
        // The word is gone, so the space that followed it is not the keyboard's any more
        // and nothing is left for punctuation to eat or a letter to retract.
        forgetAutospace()
        updateAutoShift()
        if (src == "commit" && offer.isNotEmpty() && !editorState.privateMode) {
            showRetypeOffer(offer, offerLanguages)
        }
    }

    /**
     * The rejected gesture's other candidates, left on the bar as a kept bar: a pick
     * inserts at the cursor once the editor is proved unchanged, and is learned into the
     * language it came from. Any gesture or tap replaces it, as a kept bar always has.
     */
    private fun showRetypeOffer(words: List<String>, languages: Map<String, String>) {
        val tail = ich.textBeforeCursor(KEPT_BAR_TAIL_CHARS)?.toString() ?: return
        keptBar = KeptBar("", tail)
        candidateLanguages = languages
        suggestionBar?.setSuggestions(words.map { barSuggestion(it) })
        updateBarWordPending()
        DecodeTrace.log { "  retype offer n=${words.size} first=${words.first()}" }
    }

    /**
     * Traces how often a commit is backspaced away and how far, to price taking a word's weight
     * back on backspace as a retype does: `left=0` is the whole word gone.
     */
    private fun traceBackspaceInto(word: String, src: String) {
        val before = ich.textBeforeCursor(word.length + COMMIT_TAIL_CHARS) ?: ""
        val left = backspaceLeft(before, word, COMMIT_TAIL_CHARS)
        backspaceTarget = if (left == null || left == 0) null else word
        if (left != null && left >= 0) DecodeTrace.log { "  backspace into commit word=$word left=$left src=$src" }
    }

    /**
     * Removes an automatically inserted space when [text] is punctuation that hugs the word
     * before it. No-op for a space the user typed, and once anything else has been committed:
     * every other path through commitTracked clears the flag.
     */
    private fun eatAutospaceBefore(text: String) {
        if (!autospaceInserted || !hugsPreviousWord(text)) return
        if (ich.textBeforeCursor(1)?.toString() != " ") return
        ich.deleteBeforeCursor(1)
        expectAfter(1, 0)
        autospaceInserted = false
        autospaceFromTaps = false
        DecodeTrace.log { "  autospace eat punct=$text" }
    }

    /**
     * Removes an automatic space that followed a tapped word, and reopens that word.
     *
     * The edit [eatAutospaceBefore] makes for punctuation, then [reloadWordUnderCursor]
     * re-seeds the word from the editor, as after a deleted space. So `car`, pause, space,
     * `pet` reaches the composer as one word, as tap anchors like the taps that wrote it.
     */
    private fun retractAutospace(beforeTime: Long) {
        if (ich.textBeforeCursor(1)?.toString() != " ") return
        ich.deleteBeforeCursor(1)
        expectAfter(1, 0)
        autospaceInserted = false
        autospaceFromTaps = false
        DecodeTrace.log { "  autospace retract" }
        reloadWordUnderCursor(beforeTime)
    }

    /**
     * Forgets the automatic space this keyboard put before the cursor, because a backspace
     * or a slide has just deleted it.
     *
     * Only the two flags that describe that space. Nothing records what the user meant by
     * deleting it: such a flag outlives its word and silences correct spaces on later ones.
     */
    private fun forgetAutospace() {
        autospaceInserted = false
        autospaceFromTaps = false
    }

    private fun finalizeThenCommitText(text: String) {
        eatAutospaceBefore(text)
        finalizePendingWord()
        commitTracked(text)
    }

    /**
     * An accented letter chosen from a long-press popup extends the word being written
     * instead of ending it. Returns false when [text] is not one, so the caller falls back
     * to commit-then-insert.
     *
     * Long presses never reach onTokenFinalized (the hold timer cancels the engine pointer),
     * so this is the only way in. A digit, symbol or emoji ends a word; an accent is a letter
     * of it, so tap-typing "matadór" must not commit "matad" at the accent. The apostrophe
     * key still breaks the word, since "nell'immagine" is no dictionary word.
     *
     * The token carries the folded base key the trie is keyed on, while the editor and
     * [tentativeWord] carry the accented glyph, as reloadWordUnderCursor does: the decode
     * keeps composing and the user's accent stays on screen.
     */
    private fun composeAccentedLetter(text: String): Boolean {
        if (config.peckMode || editorState.privateMode) return false
        val comp = composer ?: return false
        val g = currentGeometry ?: return false
        val code = AccentFolder.accentedLetterCode(text, boardAlphabet)
        if (code < 0 || !g.hasKey(code)) return false
        if (comp.tokenCount >= KineticaConstants.MAX_WORD_LEN) return false
        clearCorrection()
        if (tentativeLength == 0) wordShift = shift.state
        // The popup already applied the layout's case to its cells, so the glyph goes in
        // as chosen, not through shift.apply again.
        commitTracked(text)
        tentativeLength += text.length
        tentativeWord += text
        val now = SystemClock.uptimeMillis()
        comp.onToken(
            TapToken(
                StreamId.LEFT, code, g.centerX(code), g.centerY(code),
                longPress = false, tStart = now, tEnd = now + 1,
            ),
        )
        return true
    }

    // Reversible backspace slide: [stagedDeletion] is the span that would be deleted,
    // previewed struck-through above the backspace key. Nothing is deleted until the finger
    // lifts with a non-empty stage; sliding back retracts unit by unit down to a no-op. A
    // unit is a word, or a character when the char-slide preference is on.
    private var stagedDeletion = ""
    private var stagedDeletionLength = 0
    // Cursor offset the staged span is measured back from, captured once when staging
    // starts. selStart/selEnd follow every selection made below, so re-reading them
    // mid-slide would walk this backwards a span at a time. -1 means nothing is staged.
    private var stageAnchor = -1
    // The rest of the snapshot taken when staging starts: the text before the
    // anchor, and any selection the user already had (its length and its text).
    private var stageBefore = ""
    private var stageSelected = 0
    private var stageSelectedText = ""


    private fun stageDeletion(units: Int, chars: Boolean) {
        if (units <= 0) {
            if (stagedDeletionLength > 0) clearStagedDeletion(restoreCursor = true)
            return
        }
        cancelAutospace()
        // One tick per unit: BackspaceController reports only a changed count, so this fires
        // on each threshold crossing and never while the finger sits still. Retractions tick
        // too, or a slide deletes less than intended unnoticed. Honours the vibration setting.
        vibrateForKeyPress()
        if (composer?.hasPendingWord == true || tentativeLength > 0) abandonWord()

        // Snapshot the editor once, when staging starts. From the first highlight on, the
        // live selection is one this method made, and reading it as the user's would grow the
        // span by a unit per crossing, retractions included.
        if (stagedDeletionLength == 0) {
            stageAnchor = selEnd
            // A selection the user had when the slide began is the first staged unit
            // (DeleteSpan.staged); its text is captured for the chip while still readable.
            stageSelected = selectionLength()
            stageBefore = ich.textBeforeCursor(STAGE_FETCH_CHARS)?.toString() ?: ""
            stageSelectedText = if (stageSelected > 0) {
                ich.selectedText()?.toString()?.takeIf { it.length == stageSelected }
                    ?: "\u00b7".repeat(stageSelected)
            } else {
                ""
            }
        }
        val before = stageBefore
        val span = DeleteSpan.staged(stageSelected, before, units, chars)
        stagedDeletionLength = span
        val tail = (span - stageSelected).coerceIn(0, before.length)
        stagedDeletion =
            before.substring(before.length - tail) + stageSelectedText
        // Highlight what would go in the text itself. The chip stays, because the text may
        // have scrolled out of view or be a password field. Presentation only: the span
        // deleted on lift is the same either way.
        if (stageAnchor >= span) {
            ich.setSelection(stageAnchor - span, stageAnchor)
            expectAt(stageAnchor - span, stageAnchor)
        }
        keyboardView?.setDeletePreview(
            when {
                span <= 0 -> null
                // Never echo password characters into the preview chip.
                editorState.privateMode -> "\u2022".repeat(span.coerceAtMost(24))
                else -> stagedDeletion
            },
        )
    }

    /** Drops the staged span, optionally putting the cursor back where it was. */
    private fun clearStagedDeletion(restoreCursor: Boolean) {
        if (restoreCursor && stageAnchor >= 0) {
            ich.setSelection(stageAnchor, stageAnchor)
            expectAt(stageAnchor)
        }
        stagedDeletion = ""
        stagedDeletionLength = 0
        stageAnchor = -1
        stageSelected = 0
        stageBefore = ""
        stageSelectedText = ""
        keyboardView?.setDeletePreview(null)
    }

    private fun commitStagedDeletion() {
        val span = stagedDeletionLength
        val anchor = stageAnchor
        val staged = stagedDeletion
        // No cursor restore here: the span is about to go, and collapsing the selection
        // first would make the delete flicker.
        clearStagedDeletion(restoreCursor = false)
        if (span <= 0) return
        // The span is highlighted by now, so delete back from the anchor, or relative to the
        // cursor when no anchor was captured.
        // Whether the span holds the keyboard's own space is decided before the delete, as
        // onBackspace does: afterwards the evidence is gone.
        val deletedAutospace = autospaceInserted && staged.endsWith(" ")
        val eating = if (DecodeTrace.enabled) lastCommit.retypeWord ?: backspaceTarget else null
        if (anchor >= span) {
            ich.deleteEndingAt(anchor, span)
            expectAt(anchor - span)
        } else {
            ich.deleteBeforeCursor(span)
            expectAfter(span, 0)
        }
        if (eating != null) traceBackspaceInto(eating, "slide")
        abandonWord()
        if (deletedAutospace) {
            forgetAutospace()
            DecodeTrace.log { "  autospace refuse src=slide" }
        }
        updateAutoShift()
    }

    /**
     * Moves the cursor one word in [direction], returning false when the editor's text does
     * not allow it; the caller then falls back to the arrow key.
     *
     * Uses the backspace slide's walk (`DeleteSpan.words` and its forward mirror), so "one
     * word" means the same on both gestures: punctuation and whitespace go with the word, and
     * `word,` is one step. The read is bounded, so a word longer than the window falls back
     * instead of jumping somewhere wrong. A selection collapses to the edge the movement heads for.
     */
    private fun moveCursorByWord(direction: Int): Boolean {
        if (direction > 0) {
            val after = ich.textAfterCursor(CURSOR_WORD_WINDOW) ?: return false
            if (after.isEmpty()) return false
            val n = DeleteSpan.wordsForward(after, 1)
            if (n <= 0) return false
            val to = selEnd + n
            ich.setSelection(to, to)
        } else {
            val before = ich.textBeforeCursor(CURSOR_WORD_WINDOW) ?: return false
            if (before.isEmpty()) return false
            val n = DeleteSpan.words(before, 1)
            if (n <= 0) return false
            val to = (selStart - n).coerceAtLeast(0)
            ich.setSelection(to, to)
        }
        return true
    }

    private fun vibrateForKeyPress() {
        if (!config.vibration) return
        val amplitude = when (config.vibrationIntensity) {
            1 -> 60
            3 -> 255
            else -> 140
        }
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = if (v.hasAmplitudeControl()) {
            VibrationEffect.createOneShot(12, amplitude)
        } else {
            VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        v.vibrate(effect)
    }

    private fun onSpace() {
        if (consumeCtrl("space")) return
        if (config.tidySpaces && composer?.hasPendingWord != true) {
            when (tidySpaceTap(ich.textBeforeCursor(2) ?: "", config.doubleSpacePeriod)) {
                SpaceTap.SENTENCE_END -> {
                    onDoubleSpace()
                    return
                }
                SpaceTap.SWALLOW -> {
                    // Never two spaces in a row: the one there already is the space.
                    // A pending word that finishes now still gets its own.
                    cancelAutospace()
                    if (finalizePendingWord() && ich.textBeforeCursor(1)?.lastOrNull() != ' ') {
                        commitTracked(" ")
                    } else {
                        DecodeTrace.log { "  space swallowed" }
                    }
                    updateAutoShift()
                    refreshPredictions()
                    return
                }
                SpaceTap.WRITE -> Unit
            }
        }
        cancelAutospace()
        finalizePendingWord()
        commitTracked(" ")
        updateAutoShift()
        refreshPredictions()
    }

    /**
     * Second spacebar tap of a double: the space it wrote becomes a sentence end.
     *
     * [doubleSpaceEndsSentence] refuses more than it accepts: at the start of a field, after a
     * run of spaces and after punctuation there is no word for a period to close, and `e.g.. `
     * is worse than nothing. A refusal falls through to an ordinary space. The trailing space
     * is the keyboard's own, like an autospace, so punctuation can still take it back.
     */
    private fun onDoubleSpace() {
        val before = ich.textBeforeCursor(2) ?: ""
        if (!doubleSpaceEndsSentence(before)) {
            onSpace()
            return
        }
        cancelAutospace()
        ich.deleteBeforeCursor(1)
        expectAfter(1, 0)
        commitTracked(". ")
        autospaceInserted = true
        updateAutoShift()
    }

    /**
     * Spacebar tapped in its spaceless zone: end the word, write no space.
     *
     * [onSpace] without its space, except that an autospace already on screen comes back off,
     * before the commit clears the flags it reads. The word is committed, not abandoned, so
     * the next word's autospace is decided by joinedTokenIsWord: `key` + `board` spaces after
     * `keyboard` because that is a word, and an invented compound does not.
     */
    private fun onSpacelessSpace() {
        cancelAutospace()
        eatSpacelessAutospace()
        finalizePendingWord()
        updateAutoShift()
    }

    /**
     * The [eatAutospaceBefore] edit with no punctuation to judge, for the spaceless zone.
     *
     * Separate because the reasons differ: punctuation eats a space it would look wrong
     * beside, and this eats one the user has just asked not to have.
     */
    private fun eatSpacelessAutospace() {
        if (!autospaceInserted) return
        if (ich.textBeforeCursor(1)?.toString() != " ") return
        ich.deleteBeforeCursor(1)
        expectAfter(1, 0)
        autospaceInserted = false
        autospaceFromTaps = false
        DecodeTrace.log { "  autospace eat spaceless" }
    }

    /**
     * Re-cases the word in hand, or the one just finished, from shift's popup.
     *
     * The span is [retypeSpan]'s: the word being written, else the last commit with what the
     * editor holds after it ([commitSpan]), else the letters under the cursor. The last case
     * is common here, since a re-case comes after the word is settled and the stale-buffer
     * timeout has cleared the other two.
     *
     * - No learning: [learnWord] lowercases, and a cosmetic edit must not earn a weight unit.
     * - No re-commit, which would clear the candidates and the correction strip.
     * - No abandon, so a re-cased word in progress stays writable.
     */
    private fun recaseWordInHand(cell: String) {
        val idx = LayoutMutations.SHIFT_CASE_CELLS.indexOf(cell)
        val want = WordCase.entries.getOrNull(idx) ?: return
        if (selectionLength() != 0 && recaseSelection(want)) return
        if (tentativeLength == 0 && recaseAroundCursor(want)) return
        // The guard the retype and the reload share. Inside a word was answered above; a
        // letter after the cursor here starts the next word.
        val after = ich.textAfterCursor(1)
        if (after != null && after.isNotEmpty() && after[0].isLetter()) return

        // The last commit's span is read from the editor, not a remembered length: a stale
        // length ate the word it meant to re-case.
        val committed = lastCommit.retypeWord
        val before = if (committed == null) {
            ""
        } else {
            ich.textBeforeCursor(committed.length + COMMIT_TAIL_CHARS) ?: ""
        }
        val span = if (committed == null) -1 else commitSpan(before, committed, COMMIT_TAIL_CHARS)
        DecodeTrace.log {
            "  recase to=$want src=${retypeSource(tentativeLength, span)} span=$span"
        }
        when {
            tentativeLength > 0 -> {
                val recased = want.applyTo(tentativeWord)
                if (recased == tentativeWord) return
                replaceTentative(recased)
                // Written as well as the text, or the next decode's displayWord re-applies
                // the old wordShift over the top and the re-case silently undoes itself.
                wordShift = when (want) {
                    WordCase.LOWER -> ShiftState.State.NONE
                    WordCase.TITLE -> ShiftState.State.SHIFT
                    WordCase.UPPER -> ShiftState.State.CAPS_LOCK
                }
            }
            committed != null && span >= 0 -> {
                val recased = want.applyTo(committed)
                if (recased == committed) return
                // onCorrectionPicked's edit and none of its tail: whatever the editor holds
                // after the word goes back untouched, so an automatic space or a
                // punctuation mark is neither eaten nor re-cased.
                val tailAt = before.length - span + committed.length
                val tail = before.subSequence(tailAt, before.length)
                ich.replaceBeforeCursor(span, recased + tail)
                expectAfter(span, recased.length + tail.length, rewritesWord = true)
                lastCommit.onReplaced(recased)
            }
            else -> {
                val run = trailingLetterRun(
                    ich.textBeforeCursor(KineticaConstants.MAX_WORD_LEN + 1) ?: "",
                )
                if (run.isEmpty()) return
                val recased = want.applyTo(run)
                if (recased == run) return
                ich.replaceBeforeCursor(run.length, recased)
                expectAfter(run.length, recased.length, rewritesWord = true)
            }
        }
        updateAutoShift()
    }

    /**
     * Re-cases the selected text and leaves it selected. True when a selection was there
     * to act on, changed or not.
     */
    private fun recaseSelection(want: WordCase): Boolean {
        val text = ich.selectedText()?.toString() ?: return false
        if (text.isEmpty()) return false
        if (text.length > SELECTION_RECASE_MAX_CHARS) {
            DecodeTrace.log { "  recase selection refused len=${text.length}" }
            return true
        }
        val recased = want.applyToText(text)
        DecodeTrace.log { "  recase selection to=$want len=${text.length}" }
        if (recased == text) return true
        val at = minOf(selStart, selEnd)
        ich.replaceSelection(at, recased)
        expectAt(at, at + recased.length)
        return true
    }

    /**
     * Re-cases the whole word the cursor is parked inside and leaves the cursor where it was.
     * True when the cursor was inside a word, changed or not, so the caller never
     * falls through and re-cases the half before the cursor.
     */
    private fun recaseAroundCursor(want: WordCase): Boolean {
        val read = KineticaConstants.MAX_WORD_LEN + 1
        val before = ich.textBeforeCursor(read) ?: return false
        val after = ich.textAfterCursor(read) ?: return false
        if (!cursorInsideWord(before, after)) return false
        // A selection is a range the user chose; re-casing that is its own request.
        if (selectionLength() > 0) return true
        val word = wordAroundCursor(before, after, KineticaConstants.MAX_WORD_LEN)
        DecodeTrace.log {
            val span = word?.let { "${it.head.length}+${it.tail.length}" } ?: "refused"
            "  recase to=$want src=inside span=$span"
        }
        if (word == null) return true
        val (head, tail) = want.applyAround(word.head, word.tail)
        if (head == word.head && tail == word.tail) return true
        ich.replaceAroundCursor(word.head.length, word.tail.length, head, tail)
        expectAfter(word.head.length, head.length, rewritesWord = true)
        updateAutoShift()
        return true
    }

    private fun onPunctuation(text: String) {
        if (text.length == 1 && consumeCtrl(text.lowercase())) return
        cancelAutospace()
        // Reserved action outputs never reach the editor as text.
        if (performIfAction(text)) return
        // "Hi" + autospace + "!" should read "Hi!", not "Hi !".
        eatAutospaceBefore(text)
        finalizePendingWord()
        commitTracked(text)
        updateAutoShift()
    }

    private fun onBackspace() {
        if (consumeCtrl("backspace")) return
        cancelAutospace()
        if (undoAutocorrect()) return
        val eating = if (DecodeTrace.enabled) lastCommit.retypeWord ?: backspaceTarget else null
        // Whether this delete is aimed at the keyboard's own space, decided before the
        // deletion because afterwards the evidence is gone. Deleting the space itself makes
        // the flags describing it stale; deleting a letter says nothing about it.
        val deletedAutospace = autospaceInserted && ich.textBeforeCursor(1)?.toString() == " "
        // Selected text is what backspace deletes, and only the whole of it: the
        // standard editing contract, and the one case where deleting a single
        // character would destroy text the user did not point at.
        val selected = selectionLength()
        val span = if (config.tidySpaces && selected == 0) {
            backspaceSpan(ich.textBeforeCursor(SPACE_RUN_READ_CHARS) ?: "")
        } else {
            1
        }
        if (selected > 0) {
            ich.deleteEndingAt(selEnd, selected)
            expectAt(selEnd - selected)
        } else {
            ich.deleteBeforeCursor(span)
            expectAfter(span, 0)
        }
        if (span > 1) DecodeTrace.log { "  backspace collapsed spaces=${span + 1}" }
        if (eating != null) traceBackspaceInto(eating, "key")
        // Editing inside a decoded word invalidates gesture tracking; the
        // remaining text becomes plain committed text, then reloads as exact
        // anchors so continued typing corrects the word instead of starting a
        // disconnected fragment.
        abandonWord()
        if (deletedAutospace && selected == 0) {
            forgetAutospace()
            DecodeTrace.log { "  autospace refuse src=backspace" }
        }
        reloadWordUnderCursor()
    }

    /**
     * If the cursor now sits at the end of a word (letters immediately before
     * it, none after, no trailing delimiter), reload that word into the
     * composer as synthetic tap anchors at the key centers. Letters are
     * accent-folded onto their base keys, so an Italian "perch" continues to
     * "perché"; apostrophes are skipped (the trie search re-inserts
     * dictionary apostrophes for free).
     */
    private fun reloadWordUnderCursor(beforeTime: Long = SystemClock.uptimeMillis()) {
        if (editorState.privateMode) return
        // Peck mode has no prediction to re-seed; reloading would resurrect
        // the suggestion pipeline through the backspace path.
        if (config.peckMode) return
        val comp = composer ?: return
        val g = currentGeometry ?: return
        val after = ich.textAfterCursor(1)
        if (after != null && after.isNotEmpty() && after[0].isLetter()) return
        val before = ich.textBeforeCursor(KineticaConstants.MAX_WORD_LEN + 1) ?: return
        val fragment = trailingLetterRun(before)
        if (fragment.isEmpty() || fragment.length > KineticaConstants.MAX_WORD_LEN) return
        val taps = tapAnchors(fragment, g, beforeTime) ?: return
        wordShift = shiftOf(fragment)
        tentativeLength = fragment.length
        tentativeWord = fragment
        reloadedWord = fragment
        // Whatever the timer was armed for, it is not what the composer holds now.
        cancelAutospace()
        comp.seed(taps)
        // After seed, not before: the decode runs on decodeExecutor and its callback
        // cannot reach onCandidates until this method returns, so the flag is up before
        // either arm site reads it.
        seededWithoutTokens = true
    }

    /**
     * Fills the bar for the word the cursor is parked inside, as the end-of-word reload does,
     * without reopening it. The word is seeded as taps, so the alternatives are its spelling
     * neighbours, through the ordinary candidates path.
     *
     * True when the cursor was inside a word at all, offered or not, so the caller never
     * reloads the half before the cursor.
     */
    private fun offerMidWord(): Boolean {
        val read = KineticaConstants.MAX_WORD_LEN + 1
        val before = ich.textBeforeCursor(read) ?: return false
        val after = ich.textAfterCursor(read) ?: return false
        if (!cursorInsideWord(before, after)) return false
        if (editorState.privateMode || config.peckMode) return true
        val comp = composer ?: return true
        val g = currentGeometry ?: return true
        val word = wordAroundCursor(before, after, KineticaConstants.MAX_WORD_LEN) ?: return true
        val whole = word.head + word.tail
        val taps = tapAnchors(whole, g, SystemClock.uptimeMillis()) ?: return true
        wordShift = shiftOf(whole)
        midWordOffer = word
        // A word this keyboard wrote here offers what its bar had then.
        midWordHistory = commitHistory.at(selStart - word.head.length, whole)
        cancelAutospace()
        DecodeTrace.log {
            "  midword offer head=${word.head} tail=${word.tail} from=${if (midWordHistory != null) "history" else "letters"}"
        }
        // The decode follows the word before this one, not the commits made since, which can
        // include the word itself.
        comp.reset()
        wordBefore(before, word.head)?.let { comp.anchorContext(it) }
        comp.seed(taps)
        seededWithoutTokens = true
        return true
    }

    /**
     * Puts [word] in place of the whole word the offer was made for, and the cursor after it.
     * Both halves are read again first: a pick against text that changed is refused, never
     * guessed.
     */
    private fun pickMidWord(offer: WordAround, word: String) {
        val read = KineticaConstants.MAX_WORD_LEN + 1
        val before = ich.textBeforeCursor(read) ?: ""
        val after = ich.textAfterCursor(read) ?: ""
        val old = offer.head + offer.tail
        val history = midWordHistory
        abandonWord()
        if (!midWordStillThere(before, after, offer)) {
            DecodeTrace.log { "  midword refused word=$word old=$old" }
            return
        }
        DecodeTrace.log { "  midword pick word=$word old=$old" }
        val start = cursorExpected() - offer.head.length
        if (!commitHistory.replaced(start, old, word)) commitHistory.onEdit(start + old.length, old.length, word.length)
        ich.replaceAroundCursor(offer.head.length, offer.tail.length, word, "")
        expectAfter(offer.head.length, word.length, rewritesWord = true)
        // The next word follows the one picked.
        composer?.anchorContext(word)
        // Learned as a pick is, the old word left alone: nothing says this keyboard wrote it.
        if (!word.equals(old, ignoreCase = true)) {
            val from = history?.languages?.get(word.lowercase()) ?: languageOf(word)
            learnWord(word, lang = sharedFiling.languageFor(heldBy(word), from))
        }
        updateAutoShift()
        refreshPredictions()
    }

    private fun onEnter() {
        if (consumeCtrl("enter")) return
        cancelAutospace()
        finalizePendingWord()
        when (val r = EnterBehavior.resolve(editorState, config.enterAction)) {
            EnterBehavior.Result.Newline -> commitTracked("\n")
            is EnterBehavior.Result.Action -> {
                DecodeTrace.log { "  enter action=${r.id}" }
                ich.performEditorAction(r.id)
            }
        }
        updateAutoShift()
    }

    /** The resident languages in load order: active, second, third. */
    private fun residentLanguages(): List<String> =
        listOfNotNull(config.language, secondaryLanguage?.takeIf { secondaryPredictor != null }, extraLanguage)

    /** Weights for the composer with no primary language, or null for the pairwise merge. */
    private fun equalWeights(): Map<String, Float>? =
        if (config.noPrimary && secondaryPredictor != null) momentum.weightsFor(residentLanguages()) else null

    /**
     * Moves each resident language's standing toward the word just committed, and hands the
     * composer the new weights. A word a language does not hold scores zero there.
     */
    private fun noteLanguageMomentum(word: String) {
        if (!config.noPrimary || word.isEmpty() || editorState.teachesNothing) return
        val scores = HashMap<String, Float>(3)
        for ((lang, p) in listOf(config.language to predictor, secondaryLanguage to secondaryPredictor, extraLanguage to extraPredictor)) {
            if (lang == null || p == null) continue
            scores[lang] = if (p.isWord(word)) p.frequencyByte(word).coerceAtLeast(0) / 255f else 0f
        }
        if (scores.size < 2) return
        momentum.observe(scores)
        val weights = momentum.weightsFor(residentLanguages())
        composer?.languageWeights = weights
        DecodeTrace.log { "  momentum word=$word front=${momentum.front(residentLanguages())} w=${weights.entries.joinToString(",") { "${it.key}:${"%.2f".format(it.value)}" }}" }
    }

    /**
     * Feeds the spacebar's speed with the word just committed. Only a word with real touches
     * behind it counts: a pick of a predicted word has none, and its time would be the
     * user's reading, not their typing.
     */
    private fun recordTypingSpeed(word: String) {
        val start = wordStartMs
        wordStartMs = -1L
        if (!config.typingSpeed || editorState.privateMode || start < 0 || word.isEmpty()) return
        val wpm = typingSpeed.logWord(start, wordEndMs, word.length)
        DecodeTrace.log { "  wpm display=${wpm?.let { "%.1f".format(it) }} rate=${typingSpeed.rate()?.let { "%.1f".format(it) }} letters=${word.length}" }
        val kv = keyboardView ?: return
        kv.speedLabel = wpm?.let { getString(R.string.typing_speed_label, it.roundToInt()) }
        mainHandler.removeCallbacks(speedHideRunnable)
        mainHandler.postDelayed(speedHideRunnable, TypingSpeed.IDLE_BREAK_MS)
    }

    private fun enterLabelText(label: EnterBehavior.Label): String = when (label) {
        is EnterBehavior.Label.Custom -> label.text
        EnterBehavior.Label.SEARCH -> getString(R.string.enter_label_search)
        EnterBehavior.Label.SEND -> getString(R.string.enter_label_send)
        EnterBehavior.Label.GO -> getString(R.string.enter_label_go)
        EnterBehavior.Label.NEXT -> getString(R.string.enter_label_next)
        EnterBehavior.Label.DONE -> getString(R.string.enter_label_done)
        EnterBehavior.Label.PREVIOUS -> getString(R.string.enter_label_previous)
    }

    private fun onSuggestionPicked(word: String) {
        cancelAutospace()
        midWordOffer?.let {
            pickMidWord(it, word)
            return
        }
        val choice = expansionChoice
        if (choice != null) {
            expansionChoice = null
            val i = choice.shown.indexOf(word)
            if (i >= 0 && suggestionBar?.showsWords(choice.shown) == true) {
                pickExpansion(choice, i)
                return
            }
        }
        if (barPredictions.isNotEmpty() && keptBar == null && tentativeLength == 0) {
            // The pair is learned from the word on screen, not from whatever the composer last
            // committed: a cursor move can put a prediction after any word.
            previousWordForPrediction(ich.textBeforeCursor(PREDICT_TAIL_CHARS))
                ?.let { composer?.anchorContext(it) }
            barPredictions = emptyList()
            DecodeTrace.log { "  predict pick word=$word" }
        }
        val kept = keptBar
        if (kept != null) {
            keptBar = null
            val now = ich.textBeforeCursor(KEPT_BAR_TAIL_CHARS)?.toString() ?: ""
            val span = keptBarPickSpan(now, kept.tailAtClose, kept.staleWord)
            DecodeTrace.log { "  stale pick word=$word span=${if (span < 0) "refused" else span}" }
            if (span < 0) {
                abandonWord()
                return
            }
            // What replaceTentative replaces: the earlier decode the closed buffer left on
            // screen, re-proved against the editor, not remembered.
            tentativeLength = span
            tentativeWord = kept.staleWord
        }
        replaceTentative(word)
        commitWordInternal(word)
        if (spacesAfterPick(editorState.addressField)) {
            commitTracked(" ")
            // A picked word's space is the keyboard's own, like the autospace, so punctuation
            // takes it back. commitTracked clears the flag, so this comes after it.
            autospaceInserted = true
        }
        updateAutoShift()
        // After the space, so the next word's predictions have a word to follow.
        refreshPredictions()
    }

    private fun onCorrectionPicked(replacement: String) {
        val current = lastCommit.stripWord ?: return
        // The strip outlives the commit it names, and commitWordInternal records the
        // commit inside its own learning guard, so the editor can have moved on
        // from the word this is about. A refusal costs one tap; counting back a remembered
        // length ate real text.
        val before = ich.textBeforeCursor(current.length + COMMIT_TAIL_CHARS) ?: ""
        val span = commitSpan(before, current, COMMIT_TAIL_CHARS)
        if (span < 0) {
            DecodeTrace.log { "  correction refused word=$current" }
            return
        }
        cancelAutospace()
        val tail = before.subSequence(before.length - span + current.length, before.length)
        commitHistory.replaced(cursorExpected() - span, current, replacement)
        ich.replaceBeforeCursor(span, replacement + tail)
        expectAfter(span, replacement.length + tail.length, rewritesWord = true)
        lastCommit.onReplaced(replacement)
        recentWords.onReplaced(0, replacement)
        // Same transfer the unigram counts get below: the pair the wrong commit recorded
        // is taken back and the corrected one recorded in its place.
        val prevForPair = composer?.contextSnapshot()?.getOrNull(
            (composer?.contextSnapshot()?.size ?: 0) - 2,
        )
        unlearnLastPair()
        composer?.replaceLastCommit(replacement.lowercase())
        val replacementLang = sharedFiling.languageFor(heldBy(replacement), languageOf(replacement))
        if (prevForPair != null) {
            learnPair(listOf(prevForPair, replacement.lowercase()), replacement, replacementLang)
        }
        // The tapped word is the real final commit: it earns the weight, and
        // the replaced word hands back the count the unwanted commit earned.
        learnWord(replacement, lang = replacementLang)
        if (!current.equals(replacement, ignoreCase = true)) {
            unlearnWord(current)
        }
        lastLearnedWord = replacement.lowercase()
        lastLearnedWordLang = replacementLang
    }

    // ---------------------------------------------------------- word state

    /**
     * Called before any delimiter. Applies tap autocorrect when the literal
     * word is unknown and the best candidate is geometrically confident.
     * Returns true when a pending word was committed.
     */
    private fun finalizePendingWord(): Boolean {
        // The offer's seeded word is not a word in progress and must never be committed.
        if (midWordOffer != null) {
            abandonWord()
            return false
        }
        val comp = composer ?: run {
            abandonWord()
            return false
        }
        if (!comp.hasPendingWord) {
            // A delimiter says the user has moved on from a kept bar's words.
            if (keptBar != null) abandonWord()
            return false
        }

        var finalWord = tentativeWord
        val p = predictor
        val threshold = config.autocorrectConfidence
        if (p != null && !comp.hasSwipeToken() && lastLiteral.isNotEmpty() &&
            !editorState.privateMode
        ) {
            val target = if (threshold != null) {
                p.tapAutocorrect(lastLiteral, lastTentative, threshold)
            } else {
                null
            }
            if (target != null) {
                val display = displayWord(target.word)
                val typed = tentativeWord
                replaceTentative(display)
                finalWord = display
                autocorrectUndo = AutocorrectUndo(typed, display, cursorExpected())
            }
        }
        // English's lone "i". Nothing upstream can reach it: letters are
        // committed one at a time before there is a word to look at, so the
        // shift state is positional only, and autocorrect never rewrites a word
        // the dictionary already has. Applies to the tap path here and to
        // decoded words through displayWord.
        val cased = AutoCapitalization.forWord(finalWord, config.language, languageOf(finalWord))
        if (cased != finalWord) {
            replaceTentative(cased)
            finalWord = cased
        }
        commitWordInternal(finalWord)
        return true
    }

    /**
     * Adaptive weighting: every final commit adds [amount] (default 1) to the
     * word's personal count, in the live map (ranking reacts immediately) and
     * in Room (survives restarts, merges into the trie at the next load).
     * [amount] may be negative (slide-to-de-reinforce); both the live map here
     * and the DAO (`MAX(0, ...)`) clamp at zero so a downgrade can never drive
     * a count negative.
     */
    private fun learnWord(word: String, amount: Int = 1, lang: String = languageOf(word)) {
        if (editorState.teachesNothing) return
        val w = word.lowercase()
        if (w.length > KineticaConstants.MAX_WORD_LEN || !WORD_RE.matches(w)) return
        // A blocked word is not learned back: its row would count up again behind the block.
        if (amount > 0 && w in blockedSpellings) {
            DecodeTrace.log { "  learn refused word=$w blocked" }
            return
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
                KineticaDb.get(this).userWords().upsertAdd(w, lang, amount, now)
            } catch (e: RuntimeException) {
                Log.w(TAG, "learn failed for $w", e)
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
    }

    /**
     * Arms the reload that makes a word just learned searchable.
     *
     * [unlearnWord] has no counterpart: a word pushed back below the merge floor keeps its
     * trie entry until the next load, but its ranking multiplier drops with the count.
     */
    private fun askUserDictReload(word: String, lang: String, before: Int, after: Int) {
        val p = when {
            lang == secondaryLanguage && lang != config.language -> secondaryPredictor
            lang == extraLanguage && lang != config.language -> extraPredictor
            else -> predictor
        }
        // No predictor yet means the first load has not finished; it will merge this word.
        if (!userDictNeedsReload(before, after, p?.isWord(word) ?: true)) return
        DecodeTrace.log { "  userdict stale word=$word count=$after lang=$lang" }
        scheduleUserDictReload()
    }

    /**
     * One more pick recorded for [emoji]. Same shape as [learnWord]: the resident
     * map is updated synchronously so the panel is right the next time it opens,
     * and Room is written on [dbExecutor].
     */
    private fun recordEmojiUse(emoji: String) {
        // A field that must not teach the dictionary must not populate a visible list
        // of what was typed in it either.
        if (editorState.teachesNothing || emoji.isEmpty()) return
        val now = System.currentTimeMillis()
        emojiUses.compute(emoji) { _, v -> EmojiRecents.Use(emoji, (v?.count ?: 0) + 1, now) }
        dbExecutor.execute {
            try {
                KineticaDb.get(this).emojiUses().upsertAdd(emoji, 1, now)
            } catch (e: RuntimeException) {
                Log.w(TAG, "emoji use not recorded", e)
            }
        }
    }

    /** Seeds [emojiUses] once, so opening the picker never reads Room on the main thread. */
    private fun reloadEmojiUses() {
        dbExecutor.execute {
            val rows = try {
                KineticaDb.get(this).emojiUses().topN(EmojiRecents.MAX)
            } catch (e: RuntimeException) {
                Log.w(TAG, "emoji use table unavailable", e)
                emptyList()
            }
            for (row in rows) {
                emojiUses[row.emoji] = EmojiRecents.Use(row.emoji, row.count, row.updatedAt)
            }
        }
    }

    /** Peck-type on or off; the pref listener applies the state change. */
    private fun togglePeckMode() {
        vibrateForKeyPress()
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit().putBoolean(Prefs.PECK_MODE, !config.peckMode).apply()
    }

    @Suppress("DEPRECATION") // method.xml declares legacy imeSubtypeLocale values.
    private fun subtypeLanguage(subtype: InputMethodSubtype?): String? =
        kineticaLanguageOf(subtype?.locale?.substringBefore('_'))

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype?) {
        // Inbound only: with the sync off, Android may still change its own subtype but
        // it no longer decides what Kinetica types.
        if (!config.syncSystemLanguage) return
        val language = subtypeLanguage(newSubtype) ?: return
        acceptSubtypeLanguage(language)
    }

    private fun acceptSubtypeLanguage(language: String) {
        // A subtype locale this build has no dictionary for must never reach the
        // preference: the load throws, the previous predictor keeps serving, and the
        // settings row shows nothing selected. kineticaLanguageOf already folds the known
        // spellings, so anything left over is a language we do not ship.
        if (language !in Prefs.ALL_LANGUAGES) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        // Called from onStartInput: asking first avoids opening a preference editor at every
        // field focus when everything already agrees.
        if (!languageSyncNeedsWrite(
                prefs.getString(Prefs.LANGUAGE, null),
                prefs.getString(Prefs.SYNCED_LANGUAGE, null),
                language,
            )
        ) {
            return
        }
        prefs.edit()
            .putString(Prefs.LANGUAGE, language)
            .putString(Prefs.SYNCED_LANGUAGE, language)
            .apply()
    }

    /**
     * Writes [arrangementOnLanguageChange]'s answer, or nothing when there is nothing to
     * write. Cheap enough to ask on every input start, so an existing French user gets
     * AZERTY without re-selecting the language.
     *
     * A write re-enters the preference listener once, harmlessly: the nested call sees the
     * language unchanged and builds the same board the caller is about to build.
     */
    private fun applyArrangementForLanguage(prefs: SharedPreferences, language: String) {
        val stored = config.keyArrangement
        val auto = prefs.getBoolean(
            Prefs.ARRANGEMENT_AUTO_APPLIED, Prefs.DEFAULT_ARRANGEMENT_AUTO_APPLIED,
        )
        val change = arrangementOnLanguageChange(stored, auto, language)
        if (change.arrangement == null && change.autoApplied == auto) return
        val edit = prefs.edit()
        change.arrangement?.let { edit.putString(Prefs.KEY_ARRANGEMENT, it) }
        edit.putBoolean(Prefs.ARRANGEMENT_AUTO_APPLIED, change.autoApplied)
        edit.apply()
    }

    /** The subtype set last handed to Android, so an unchanged one is not sent again. */
    private var enabledSubtypeHashes: IntArray? = null

    /**
     * Enables in Android the subtypes of the languages enabled here, so the picker names the
     * language being typed, not the system default (`English (US)` with English off). API
     * 34 is the first with a public call for it; earlier releases keep the system's choice.
     */
    private fun syncEnabledSubtypes() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val info = inputMethodInfo ?: return
        val subtypes = (0 until info.subtypeCount).map { info.getSubtypeAt(it) }
        val hashes = subtypesFor(subtypes.map { it.locale }, config.enabledLanguages)
            .map { subtypes[it].hashCode() }.toIntArray()
        if (hashes.isEmpty() || hashes.contentEquals(enabledSubtypeHashes)) return
        try {
            inputMethodManager.setExplicitlyEnabledInputMethodSubtypes(info.id, hashes)
            enabledSubtypeHashes = hashes
            DecodeTrace.log { "  subtypes enabled=${config.enabledLanguages}" }
        } catch (e: RuntimeException) {
            // Refused by the system: its own choice stays, as before API 34.
            DecodeTrace.log { "  subtypes refused ${e.javaClass.simpleName}" }
        }
    }

    private fun synchronizeLanguageOnStart() {
        syncEnabledSubtypes()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val systemLanguage = subtypeLanguage(inputMethodManager.currentInputMethodSubtype)
        val language = languageOnInputStart(
            prefs.getString(Prefs.LANGUAGE, null),
            prefs.getString(Prefs.SYNCED_LANGUAGE, null),
            systemLanguage,
            config.syncSystemLanguage,
        )
        if (language == systemLanguage) {
            acceptSubtypeLanguage(language)
        } else {
            requestLanguageSubtype(language)
        }
        applyArrangementForLanguage(prefs, language)
    }

    private fun requestLanguageSubtype(language: String) {
        val info = inputMethodInfo ?: return
        // A Settings edit can arrive while another IME is selected. Keep the
        // local choice unacknowledged until this IME is active again.
        if (Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) !=
            info.id
        ) return
        val subtype = (0 until info.subtypeCount).asSequence()
            .map { info.getSubtypeAt(it) }
            .firstOrNull { subtypeLanguage(it) == language } ?: return
        if (subtype == inputMethodManager.currentInputMethodSubtype) {
            acceptSubtypeLanguage(language)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchInputMethod(info.id, subtype)
        } else {
            // The service overload was added in API 28; the IME token supplies
            // the same authority through InputMethodManager on Android 8.
            val token = window.window?.attributes?.token ?: return
            @Suppress("DEPRECATION")
            inputMethodManager.setInputMethodAndSubtype(token, info.id, subtype)
        }
    }

    /** ?123-chord language switch: next enabled language in canonical order. */
    private fun cycleLanguage() {
        // Shared with the shortcut notice, so the name it shows is the language this picks.
        val next = ActionRow.nextLanguage(config.enabledLanguages, config.language) ?: return
        vibrateForKeyPress()
        // The preference listener does the rest: layout swap now, predictions
        // when the new dictionary finishes parsing.
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit().putString(Prefs.LANGUAGE, next).apply()
    }

    /** Learned pairs for [lang], or none when the store is unavailable. */
    private fun userBigramRows(lang: String): List<UserBigram> = try {
        KineticaDb.get(this).userBigrams().topN(lang, USER_PAIR_LIMIT)
    } catch (e: RuntimeException) {
        Log.w(TAG, "phrase store unavailable for $lang", e)
        emptyList()
    }

    private fun pairMap(rows: List<UserBigram>): ConcurrentHashMap<String, Int> {
        val out = ConcurrentHashMap<String, Int>(rows.size * 2 + 1)
        // Folded keys, so two spellings of one pair add up instead of overwriting.
        for (r in rows) out.merge(pairKey(r.prev, r.next), r.count, Int::plus)
        return out
    }

    /** Live pair map backing [lang]'s predictor; the active one by default. */
    private fun pairsFor(lang: String): ConcurrentHashMap<String, Int> = when {
        lang == secondaryLanguage && lang != config.language -> secondaryPairs
        lang == extraLanguage && lang != config.language -> extraPairs
        else -> personalPairs
    }

    /** Key for the pair store; the separator cannot occur in a word. */
    private fun pairKey(prev: String, next: String): String = WordPredictor.pairKey(prev, next)

    /**
     * The resident languages whose lexicon holds [word], each with its frequency byte there.
     * Empty with one language resident, which leaves every filing to provenance.
     */
    private fun heldBy(word: String): Map<String, Int> {
        val second = secondaryPredictor ?: return emptyMap()
        val secondLang = secondaryLanguage ?: return emptyMap()
        val out = HashMap<String, Int>(3)
        // Held means this exact spelling: `è` is Italian's, not English's through its `e`.
        fun held(p: WordPredictor, lang: String) {
            if (p.holdsSpelling(word)) p.frequencyByte(word).takeIf { it >= 0 }?.let { out[lang] = it }
        }
        predictor?.let { held(it, config.language) }
        held(second, secondLang)
        val third = extraPredictor
        val thirdLang = extraLanguage
        if (third != null && thirdLang != null) held(third, thirdLang)
        return out
    }

    /**
     * The dictionary a word on offer came from, defaulting to the active language for anything
     * not in the current candidate list (a typed literal, a correction never a candidate).
     */
    private fun languageOf(word: String): String {
        val w = word.lowercase()
        return candidateLanguages[w] ?: correctionLanguages[w] ?: config.language
    }

    /** Live count map backing [lang]'s predictor; the active one by default. */
    private fun countsFor(lang: String): ConcurrentHashMap<String, Int> = when {
        lang == secondaryLanguage && lang != config.language -> secondaryCounts
        lang == extraLanguage && lang != config.language -> extraCounts
        else -> personalCounts
    }

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
    private fun learnPair(context: List<String>?, word: String, lang: String) {
        if (!config.learnPhrases || editorState.teachesNothing) return
        val prev = context?.getOrNull((context.size) - 2) ?: return
        val w = word.lowercase()
        val p = prev.lowercase()
        if (!WORD_RE.matches(w) || !WORD_RE.matches(p)) return
        if (w.length > KineticaConstants.MAX_WORD_LEN || p.length > KineticaConstants.MAX_WORD_LEN) return
        lastLearnedPair = p to w
        lastLearnedPairLang = lang
        pairsFor(lang).compute(pairKey(p, w)) { _, v -> (v ?: 0) + 1 }
        val now = System.currentTimeMillis()
        dbExecutor.execute {
            try {
                KineticaDb.get(this).userBigrams().upsertAdd(p, w, lang, 1, now)
            } catch (e: RuntimeException) {
                Log.w(TAG, "phrase learn failed", e)
            }
        }
    }

    /**
     * One learned pair up or down by [delta], under [learnPair]'s guards. A pair is only taken
     * down where it exists, so the store never holds a negative row.
     */
    private fun adjustPair(prev: String, word: String, lang: String, delta: Int) {
        if (!config.learnPhrases || editorState.teachesNothing || delta == 0) return
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
                val dao = KineticaDb.get(this).userBigrams()
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
                Log.w(TAG, "phrase adjust failed", e)
            }
        }
    }

    /** Takes back the pair [learnPair] last recorded: the retype says that commit was wrong. */
    private fun unlearnLastPair() {
        val pair = lastLearnedPair ?: return
        val lang = lastLearnedPairLang ?: return
        lastLearnedPair = null
        lastLearnedPairLang = null
        val key = pairKey(pair.first, pair.second)
        pairsFor(lang).computeIfPresent(key) { _, v -> (v - 1).coerceAtLeast(0) }
        val now = System.currentTimeMillis()
        dbExecutor.execute {
            try {
                KineticaDb.get(this).userBigrams().upsertAdd(pair.first, pair.second, lang, -1, now)
            } catch (e: RuntimeException) {
                Log.w(TAG, "phrase unlearn failed", e)
            }
        }
    }

    /** Inverse of [learnWord] for commits the user explicitly took back. */
    private fun unlearnWord(word: String) {
        if (editorState.teachesNothing) return
        val w = word.lowercase()
        if (w.length > KineticaConstants.MAX_WORD_LEN || !WORD_RE.matches(w)) return
        val lang = lastLearnedWordLang?.takeIf { w == lastLearnedWord } ?: languageOf(word)
        countsFor(lang).computeIfPresent(w) { _, v -> (v - 1).coerceAtLeast(0) }
        val now = System.currentTimeMillis()
        dbExecutor.execute {
            try {
                KineticaDb.get(this).userWords().upsertAdd(w, lang, -1, now)
            } catch (e: RuntimeException) {
                Log.w(TAG, "unlearn failed for $w", e)
            }
        }
    }

    private fun commitWordInternal(word: String) {
        recordTypingSpeed(word)
        noteLanguageMomentum(word)
        // Correction options, each a tappable zone: the committed word, the other ranked
        // candidates, then the literal tap string (the way back from a wrong autocorrect).
        val options = ArrayList<String>(KineticaConstants.TOP_K + 1)
        options.add(word)
        for (c in lastCandidates) {
            val d = displayWord(c.word)
            if (!options.contains(d)) options.add(d)
        }
        var typed: String? = null
        if (lastLiteral.isNotEmpty()) {
            val d = displayWord(lastLiteral)
            if (!options.contains(d)) options.add(d)
            if (!d.equals(word, ignoreCase = true)) typed = d
        }
        // The word itself stays first; a blocked one among the others is never offered back.
        if (blockedSpellings.isNotEmpty()) {
            options.subList(1, options.size).removeAll { it.lowercase() in blockedSpellings }
            if (typed?.lowercase() in blockedSpellings) typed = null
        }
        val lang = languageOf(word)
        if (word.isNotEmpty()) {
            val alts = options.drop(1).let { o -> if (typed == null) o else listOf(typed) + (o - typed) }
            val langs = candidateLanguages + (typed?.let { mapOf(it.lowercase() to lang) } ?: emptyMap())
            commitHistory.record(cursorExpected() - word.length, word, alts, langs)
        }
        composer?.commitWord(word.lowercase())
        tentativeLength = 0
        tentativeWord = ""
        lastCandidates = emptyList()
        lastTentative = null
        keptBar = null
        // The strip outlives the candidate list, so provenance is snapshotted, not cleared:
        // a correction pick is a real commit.
        correctionLanguages = candidateLanguages
        candidateLanguages = emptyMap()
        lastLiteral = ""
        suggestionBar?.clearSuggestions()
        if (word.isNotEmpty() && !editorState.teachesNothing) {
            // Every commit (top prediction, tapped correction or manual typing) is one unit of
            // personal evidence, filed to the dictionary the word came from by its per-word
            // provenance. The one commit that learns nothing is a swipe whose full-buffer
            // decode gave no auto-committable word: the screen then shows a stale partial decode.
            if (!swipeDecodeEmpty && learnsOnCommit(word, reloadedWord)) {
                val filed = sharedFiling.languageFor(heldBy(word), lang)
                learnWord(word, lang = filed)
                lastLearnedWord = word.lowercase()
                lastLearnedWordLang = filed
                // The pair is learned from the same evidence as the word, under the same
                // guards. composer.commitWord has already pushed, so the predecessor is the
                // second-from-last context entry; lastCommit is emptied on every field change.
                learnPair(composer?.contextSnapshot(), word, filed)
            }
        }
        // Not under the learning guard above, and not the same condition: see
        // showsCorrectionStrip. With recent words on, the alternatives are already in the
        // word's column, so the strip gives the bar back to next-word predictions.
        val strip = showsCorrectionStrip(word, editorState.offersCorrections, options.size) && !config.recentWords
        if (word.isNotEmpty()) lastCommit.onCommit(word, strip, options.drop(1), correctionLanguages)
        // The letters as typed lead a corrected word's column: the strip's way back from a wrong
        // autocorrect, which the recent column replaces when it is on.
        if (word.isNotEmpty()) {
            val alts = options.drop(1).let { o -> if (typed == null) o else listOf(typed) + (o - typed) }
            recentWords.onCommit(word, alts, correctionLanguages + (typed?.let { mapOf(it.lowercase() to lang) } ?: emptyMap()))
        }
        backspaceTarget = null
        if (strip) {
            suggestionBar?.showCorrection(
                options.take(KineticaConstants.TOP_K).map { barSuggestion(it) },
                selected = 0,
            )
        }
        swipeDecodeEmpty = false
        reloadedWord = null
        updateBarWordPending()
    }

    private fun abandonWord() {
        keptBar = null
        midWordOffer = null
        midWordHistory = null
        wordStartMs = -1L
        expansionChoice = null
        composer?.clear()
        reloadedWord = null
        seededWithoutTokens = false
        tentativeLength = 0
        tentativeWord = ""
        lastCandidates = emptyList()
        lastTentative = null
        candidateLanguages = emptyMap()
        lastLiteral = ""
        swipeDecodeEmpty = false
        suggestionBar?.clearSuggestions()
        clearCorrection()
        updateBarWordPending()
    }

    /**
     * Ends the buffer as the stale timeout always has, but leaves its candidates up.
     *
     * A populated bar with nothing auto-committed is merge's `no-native` return: the active
     * language decoded nothing and another did. Those are the words the user is reading, so
     * they stay pickable. The buffer still closes, so the next gesture starts a word,
     * and provenance stays, so a pick is learned into the language it came from.
     */
    private fun closeBufferKeepBar() {
        val tail = ich.textBeforeCursor(KEPT_BAR_TAIL_CHARS)?.toString()
        if (tail == null) {
            abandonWord()
            return
        }
        keptBar = KeptBar(tentativeWord, tail)
        composer?.clear()
        reloadedWord = null
        seededWithoutTokens = false
        tentativeLength = 0
        tentativeWord = ""
        lastTentative = null
        lastLiteral = ""
        swipeDecodeEmpty = false
        clearCorrection()
        updateBarWordPending()
        DecodeTrace.log { "  stale keep n=${lastCandidates.size} staleLen=${keptBar?.staleWord?.length}" }
    }

    private fun clearCorrection() {
        val stripUp = lastCommit.stripWord != null
        lastCommit.clear()
        if (stripUp) suggestionBar?.clearCorrection()
    }

    /**
     * Records where an own edit leaves the cursor: [deleted] characters before it (or before the
     * selection, which a write replaces) gone and [inserted] written. Chained edits start from the
     * last position expected, since the editor's reports lag behind them.
     */
    private fun expectAfter(deleted: Int, inserted: Int, rewritesWord: Boolean = false) {
        val start = cursorExpected()
        // A rewrite of a recorded word updates its record itself; any other edit moves the records
        // after it and drops one it cuts into.
        if (!rewritesWord) commitHistory.onEdit(start, deleted, inserted)
        expectAt((start - deleted).coerceAtLeast(0) + inserted)
    }

    /** Where the cursor is once the edits made so far land. */
    private fun cursorExpected(): Int = selectionLedger.lastExpected()?.first ?: selStart

    private fun expectAt(start: Int, end: Int = start) {
        selectionLedger.expect(start, end, SystemClock.uptimeMillis())
    }

    private fun replaceTentative(word: String) {
        ich.replaceBeforeCursor(tentativeLength, word)
        expectAfter(tentativeLength, word.length)
        tentativeLength = word.length
        tentativeWord = word
    }

    private fun commitTracked(text: String) {
        ich.commitText(text)
        expectAfter(0, text.length)
        // Cleared here so the flags only describe the space written last. The callers that
        // write an automatic space set them again right after.
        autospaceInserted = false
        autospaceFromTaps = false
    }

    /**
     * A decoded word as it should appear: the captured shift state applied,
     * then any language-mandated capitalization on top (idempotent, so a word
     * already upper-cased by SHIFT or CAPS_LOCK is unaffected).
     */
    private fun displayWord(word: String): String {
        val shifted = when (wordShift) {
            ShiftState.State.NONE -> word
            ShiftState.State.SHIFT -> word.replaceFirstChar { it.uppercaseChar() }
            ShiftState.State.CAPS_LOCK -> word.uppercase()
        }
        return AutoCapitalization.forWord(shifted, config.language, languageOf(word))
    }

    private fun updateAutoShift() {
        if (!config.autoCapitalize) {
            // Drop out of any auto-shift already applied, or turning the setting
            // off mid-sentence leaves the keyboard stuck in the shifted state it
            // happened to be in.
            if (shift.state == ShiftState.State.SHIFT) {
                shift.autoShift(false)
                keyboardView?.setShiftUppercase(shift.isShifted)
            }
            return
        }
        if (!editorState.capSentences) return
        // Decided from the text, not asked of the editor: see startsNewSentence. A null read
        // is a connection that cannot answer, not an empty field, so the shift state stays.
        val before = ich.textBeforeCursor(CAPS_LOOKBACK_CHARS) ?: return
        shift.autoShift(startsNewSentence(before))
        keyboardView?.setShiftUppercase(shift.isShifted)
    }

    private companion object {
        const val TAG = "KineticaIME"
        const val USER_DICT_LIMIT = 5000
        const val USER_PAIR_LIMIT = 5000
        // A learned word only becomes searchable at a dictionary load, and a load parses
        // 49k words plus 100k bigrams. 1500 ms lets a burst of new words in one sentence
        // cost one parse, and still has the word usable while the user is typing the
        // thing they added it for.
        const val USER_DICT_RELOAD_DELAY_MS = 1500L
        // How long a shortcut's notice stays on the spacebar. Chosen, not measured: long
        // enough to read two words, gone before the next word is typed.
        const val SPACEBAR_NOTICE_MS = 1500L
        // How much editor text a kept bar compares to prove nothing changed before a pick.
        // Longer than any word, so a space or a deleted letter after the stale text shows.
        const val KEPT_BAR_TAIL_CHARS = 64

        /** Characters of an expansion target shown on the bar when several are offered. */
        const val EXPANSION_PICK_CHARS = 24

        /** Editor text read to find the word a prediction follows: one long word and its space. */
        const val PREDICT_TAIL_CHARS = 48

        /** Spaces read back for a backspace to collapse; a longer run collapses in steps. */
        const val SPACE_RUN_READ_CHARS = 32

        /** Longest selection the shift popup re-cases: a long document is not one tap's work. */
        const val SELECTION_RECASE_MAX_CHARS = 5000

        /** Commits the recent-words bar remembers: one more than it shows, for the strip's. */
        const val RECENT_WORDS_KEPT = 3

        /** Recent-word columns on the bar; two leave three fifths of it to the live words. */
        const val RECENT_BAR_COLUMNS = 2

        /** Editor text read to find the recent words again: three long words and their gaps. */
        const val RECENT_TAIL_CHARS = 96

        // How far each way COPY_LINE reads for the line's ends. A longer line is refused,
        // not half copied.
        const val COPY_LINE_READ_CHARS = 2000
        // Backspace slide: how much text to fetch for word-span staging.
        const val STAGE_FETCH_CHARS = 256
        // Spacebar word slide: how far to read for one word boundary. Smaller than the staging
        // window because it is one step, not a span, and the walk falls back to the arrow key
        // when the window holds no boundary.
        const val CURSOR_WORD_WINDOW = 64
        // Sentence caps: enough tail to skip closing punctuation and walk one
        // word back for the abbreviation check.
        const val CAPS_LOOKBACK_CHARS = 48
        // How far past a committed word [commitSpan] looks for the marks the editor put after
        // it. Nothing the keyboard writes after a word is longer, and `going...` needs three;
        // past this the word is not where the caller thinks, and the span is refused.
        const val COMMIT_TAIL_CHARS = 8

        // Any-letter (accented Italian included) with internal apostrophes.
        val WORD_RE = Regex("^\\p{L}+(?:'\\p{L}+)*$")
    }
}

/**
 * Whether the language preferences need writing at all.
 *
 * Beside [languageOnInputStart] and pure for the same reason: it is asked on every input
 * start, and the answer is no every time nothing has moved.
 */
internal fun languageSyncNeedsWrite(
    storedLanguage: String?,
    syncedLanguage: String?,
    language: String,
): Boolean = storedLanguage != language || syncedLanguage != language

/**
 * Choose the initial language without losing Settings edits made while the
 * service was absent. An existing preference also wins on the first sync;
 * once acknowledged, Android can select a different subtype on a cold start.
 *
 * [followSystem] off is the opt-out. Android gives the system locale's subtype to a user who never
 * forced one in its picker, so with stored and synced agreeing an English phone went back to
 * English at every cold start. Off, a stored choice always wins, and only a first run with
 * nothing stored takes the subtype.
 */
internal fun languageOnInputStart(
    storedLanguage: String?,
    syncedLanguage: String?,
    subtypeLanguage: String?,
    followSystem: Boolean = true,
): String {
    if (storedLanguage != null && storedLanguage != syncedLanguage) return storedLanguage
    if (!followSystem) return storedLanguage ?: subtypeLanguage ?: Prefs.DEFAULT_LANGUAGE
    return subtypeLanguage ?: storedLanguage ?: Prefs.DEFAULT_LANGUAGE
}

/**
 * The Kinetica language code an Android subtype locale names, or null.
 *
 * Norwegian needs folding: `method.xml` declares `nb_NO`, the correct Bokmal locale, while
 * the asset and [Prefs.ALL_LANGUAGES] use `no`. Unfolded, Norwegian falls out of the sync and
 * Android's picker stores a code with no dictionary behind it.
 */
internal fun kineticaLanguageOf(subtypeLanguage: String?): String? = when (subtypeLanguage) {
    null -> null
    "nb", "nn" -> "no"
    // Android still names Hebrew by its legacy code.
    "iw" -> "he"
    else -> subtypeLanguage
}

/**
 * Which of the declared subtypes, by their [locales] in declaration order, belong to the
 * [languages] enabled here: the set Android should offer in its picker.
 */
internal fun subtypesFor(locales: List<String?>, languages: Collection<String>): List<Int> =
    locales.indices.filter { kineticaLanguageOf(locales[it]?.substringBefore('_')) in languages }

/**
 * What the active language does to the letter arrangement, and whether the value left in
 * the preference is this keyboard's or the user's.
 *
 * [arrangement] is null to leave the preference alone.
 */
internal data class ArrangementChange(val arrangement: String?, val autoApplied: Boolean)

/**
 * French is typed on AZERTY, its own layout file, so selecting the language moves the
 * arrangement setting to match.
 *
 * The setting is global while the file is per language: a value left behind by French would
 * show "AZERTY" in Settings beside a German QWERTZ board, since [AlphaLayouts.name] finds no
 * azerty_de.json and falls through. So the write is recorded and leaving French hands it back.
 * An arrangement the user chose is never touched: [autoApplied] marks this keyboard's write,
 * and a stale marker is dropped once the stored value is no longer the one written.
 */
internal fun arrangementOnLanguageChange(
    stored: String,
    autoApplied: Boolean,
    language: String,
): ArrangementChange {
    val azerty = LayoutMutations.ARRANGEMENT_AZERTY
    val plain = Prefs.DEFAULT_KEY_ARRANGEMENT
    if (language == "fr") {
        if (stored == plain) return ArrangementChange(azerty, true)
        return ArrangementChange(null, autoApplied && stored == azerty)
    }
    if (autoApplied && stored == azerty) return ArrangementChange(plain, false)
    return ArrangementChange(null, false)
}

/** How far each side of the cursor [wordStepDirection] looks for a letter with a direction. */
internal const val WORD_STEP_LOOK_CHARS = 16

/**
 * The text-order direction of a word step the thumb made in [direction] (+1 right, -1 left).
 *
 * The spacebar's letter step sends DPAD keys, which the editor moves visually; the word step
 * sets the selection, which is text order. In right-to-left text those disagree, so the word step
 * is turned round when the nearest letter with a direction, on either side, is right-to-left.
 * Digits, spaces and punctuation have none and are looked past.
 */
internal fun wordStepDirection(direction: Int, before: CharSequence, after: CharSequence): Int {
    var i = before.length - 1
    var j = 0
    while (i >= 0 || j < after.length) {
        if (i >= 0) {
            rightToLeft(before[i])?.let { return if (it) -direction else direction }
            i--
        }
        if (j < after.length) {
            rightToLeft(after[j])?.let { return if (it) -direction else direction }
            j++
        }
    }
    return direction
}

private fun rightToLeft(c: Char): Boolean? = when (Character.getDirectionality(c)) {
    Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> true
    Character.DIRECTIONALITY_LEFT_TO_RIGHT -> false
    else -> null
}

/**
 * Whether picking a word from the bar writes a space after it.
 *
 * Not in a one-token field (an address, a URL, an expansion trigger): the same reason
 * [autospacesTappedWord] arms nothing there. A space in such a field is never wanted; a trigger
 * field strips it on save (#19).
 */
internal fun spacesAfterPick(addressField: Boolean): Boolean = !addressField

/**
 * True when a word typed entirely by tapping has earned an automatic space.
 *
 * A swipe ends with a lift, so its word is over; a tapped letter looks like the middle of a
 * longer word. The gates:
 * - [literalIsWord] is necessary and far from sufficient: of tap states spelling a real word,
 *   57% were mid-word. The delay filters most (the median gap inside a word is 168 ms), and
 *   about one fire in five is still premature at 300 ms, barely fewer at 800 ms. Rejected: a
 *   frequency floor removes good fires as fast as bad, and "top candidate equals the literal"
 *   removes 9% of misfires for 6% of good fires; the OpenSubtitles lists hold `ke`, `wh` and
 *   `whe`. The error is affordable because
 *   [retractsAutospace] takes the space back when another letter of the word follows.
 * - [literalIsStandaloneLetter] covers one letter: one-letter words were 12% of prose words
 *   (16 of 130), and every letter a-z is in every bundled list, so [StandaloneLetters] holds
 *   a curated set. One letter waits longer ([singleLetterDelayMs]); at 300 ms it spaces 17 of
 *   22 one-letter words with no premature fire on a finished word.
 * - [carriesNoToken]: a reload after a deleted space re-seeds the word as taps, and without
 *   this the timer put the space back. Scoped to one decode, since a flag that outlives its
 *   word silenced correct spaces.
 * - [addressField]: no automatic space in an email or URL field.
 *
 * All gates are read here so the whole decision stays in one testable place.
 */
internal fun autospacesTappedWord(
    enabled: Boolean,
    hasSwipeToken: Boolean,
    literal: String,
    literalIsWord: Boolean,
    literalIsStandaloneLetter: Boolean,
    addressField: Boolean,
    joinedToWhatPrecedes: Boolean,
    joinedTokenIsWord: Boolean,
    joinedByApostrophe: Boolean,
    carriesNoToken: Boolean,
): Boolean = enabled && !hasSwipeToken && !addressField && !carriesNoToken &&
    // A word that continues an earlier token takes no space unless the whole token is a
    // word: `don't` is, `automaticop` is not. An English contraction autospaces this way,
    // since its tail is too short for the length rule (see joinedTokenForAutospace).
    //
    // After an apostrophe the piece is judged on its own when the token is not a word:
    // Italian elisions (`dell'anno`, `d'accordo`, `un'ora`) are in no wordlist, while `anno`
    // and `accordo` are. Only the apostrophe: `log-12.com`, `example.com`, `name@mail.com`
    // and `notes_14.log` must keep refusing.
    //
    // The cost: `'quoted text'` gains a space, and a pause inside an elision can split it,
    // since `l'al` and `dell'ann` are entries. That was chosen over failing
    // elisions; retractsAutospace takes the space back at the next letter.
    if (joinedToWhatPrecedes) {
        joinedTokenIsWord || (joinedByApostrophe && literal.length >= 2 && literalIsWord)
    } else if (literal.length == 1) {
        // One letter: the only question is whether it is a word in this language (see
        // StandaloneLetters). After the joined branch, so the `a` of `dell'anno`, preceded
        // by an apostrophe, never reaches here.
        literalIsStandaloneLetter
    } else {
        literal.length >= 2 && literalIsWord
    }

/**
 * True when the character joining this word to what precedes it is an apostrophe.
 *
 * Separate from [joinsPrecedingToken], which asks whether the token is finished here, not
 * which joiner it is. Only the apostrophe lets [autospacesTappedWord] judge the piece after
 * it, because elision is a word boundary the dictionary cannot see. Both quote forms count:
 * the apostrophe key offers the typographic one as an alternate.
 *
 * [before] is the text preceding the word, so its last character is the one in question.
 */
internal fun joinedByApostrophe(before: CharSequence): Boolean =
    before.isNotEmpty() && (before[before.length - 1] == '\'' || before[before.length - 1] == '\u2019')

/**
 * The letters after the last apostrophe in [word], or "" when there is none.
 *
 * The retraction's second question: `dell'ann` + `o` must retract, but the elided form is
 * not in the trie, so the tail `anno` is asked. Separate from [wordBeforeAutospace], which
 * must return the whole run, or `it's` + `a` would fuse into `it'sa`.
 */
internal fun tailAfterLastApostrophe(word: String): String {
    val i = maxOf(word.lastIndexOf('\''), word.lastIndexOf('\u2019'))
    return if (i < 0) "" else word.substring(i + 1)
}

/**
 * True when a word containing at least one swipe token has earned an automatic space.
 *
 * One predicate for both call sites, so neither can miss a gate. A swipe earns its space
 * because the finger lifted and the decode landed; only two conditions remain.
 *
 * - No "user deleted a space" gate: a word swiped after a slide is a new word and earns its
 *   space, and such a flag outlives its word.
 * - [carriesNoToken] is a belt: `WordComposer.seed` replaces the token list with taps, so a
 *   reloaded word carries no swipe today. It keeps a reload that ever seeds a gesture right.
 * - [addressField]: no automatic space in an email or URL field.
 */
internal fun autospacesSwipedWord(
    hasSwipeToken: Boolean,
    addressField: Boolean,
    carriesNoToken: Boolean,
): Boolean = hasSwipeToken && !addressField && !carriesNoToken

/**
 * The whole editor token the word being typed belongs to, letters and joiners together, or ""
 * when there is nothing word-shaped there.
 *
 * A tapped apostrophe is not a letter to the composer: it routes to `onPunctuation`, which
 * finalizes the word, so `don't` reaches the composer as `don` and a one-letter `t`. Read back
 * from the editor, `don't`, `it's` and `can't` are words and space, while `log-12.com` and
 * `example.com` are not (`Alphabet.encode` rejects digits) and stay refused.
 *
 * Italian elisions are absent from the wordlist (its ten apostrophe entries are junk such as
 * `e'o` and `n'roll`), so [autospacesTappedWord] judges the piece after the apostrophe for
 * them. Generated elided forms would still let one be decoded as a single gesture.
 *
 * [before] is the text before the cursor with the word's own letters still on the end.
 */
internal fun joinedTokenForAutospace(before: CharSequence): String {
    var start = before.length
    while (start > 0) {
        val c = before[start - 1]
        // Digits are part of the token, so `log-12.com` is one thing, and they make the
        // lookup fail, since Alphabet.encode refuses them.
        if (c.isLetterOrDigit() || c in WORD_JOINERS) start-- else break
    }
    val token = before.subSequence(start, before.length).toString()
    // A run with no letter in it spells nothing to look up.
    return if (token.any { it.isLetter() }) token else ""
}

/**
 * True when the character before the word being typed joins it to what precedes, so the
 * word may be a fragment of a longer token.
 *
 * This only says the token is not finished here; whether it is finished at all is
 * [joinedTokenForAutospace]'s question, and [autospacesTappedWord] asks both. Decided from
 * the text, not the field type, so `notes_14.log` in a rename box, `e-mail`, `don't`,
 * `example.com` and `a/b` all refuse without knowing the editor.
 *
 * [before] is the text preceding the word, so its last character is the one in question.
 * Whitespace and opening punctuation allow the space, so `"hello world"` still spaces; an
 * empty read is the start of the field and allows it too.
 */
internal fun joinsPrecedingToken(before: CharSequence): Boolean {
    if (before.isEmpty()) return false
    val c = before[before.length - 1]
    if (c.isWhitespace()) return false
    if (c in SENTENCE_OPENERS || c in WORD_OPENERS) return false
    return c.isLetterOrDigit() || c in WORD_JOINERS
}

/**
 * True when an automatic space should be taken back because the word was not over.
 *
 * Only a space this keyboard put after a tapped word, and only while nothing else has
 * happened since. A swipe's autospace is never retracted: the gesture ended, so a following
 * letter starts a new word.
 *
 * [elapsedMs] is bounded because a space is provisional only while typing is in flow: `is`,
 * a break, then `land` must not produce `island`.
 *
 * [fusedIsPrefix] retracts only when the fused form could still become a word: `autom` can,
 * `automaticop` cannot. Over 67 captured word pairs it refuses 70% of would-be fusions, and
 * no first word of six letters or more fuses, the class that produced empty decodes. Short
 * words stay ambiguous, which the window covers.
 */
internal fun retractsAutospace(
    fromTappedWord: Boolean,
    tentativeLength: Int,
    elapsedMs: Long,
    windowMs: Long,
    fusedIsPrefix: Boolean,
): Boolean = fromTappedWord && tentativeLength == 0 && elapsedMs <= windowMs && fusedIsPrefix

/**
 * The timestamp the first synthetic anchor of a reopened word takes, so that all [count] of
 * them land strictly before [before].
 *
 * [before] is the touch time of the letter that caused the reopen, or now for a cursor move.
 * Based on `now`, that letter's earlier touch time sorted it before the whole reloaded word
 * whenever latency exceeded the word's length in ms: `automatico` + `per` became
 * `pautomatico`, which decodes to nothing.
 */
internal fun reloadAnchorBase(before: Long, count: Int): Long = before - count - 1

/**
 * The bar for a word the cursor sits inside: as written first, then the words its own bar had
 * when this keyboard committed it, then the decode of its letters.
 */
internal fun midWordWords(decoded: List<String>, history: CommitHistory.Record?, offer: WordAround?): List<String> {
    if (history == null || offer == null) return decoded
    val out = ArrayList<String>()
    fun add(w: String) {
        if (out.none { it.equals(w, ignoreCase = true) }) out.add(w)
    }
    add(offer.head + offer.tail)
    add(history.word)
    history.alternatives.forEach(::add)
    decoded.forEach(::add)
    return out
}

/** The word before the one whose first [head] characters end [before], lowercased; null at the start. */
internal fun wordBefore(before: CharSequence, head: String): String? {
    var i = before.length - head.length
    while (i > 0 && !isWordChar(before[i - 1])) i--
    var j = i
    while (j > 0 && isWordChar(before[j - 1])) j--
    return if (j < i) before.subSequence(j, i).toString().lowercase() else null
}

/**
 * [word] as tap anchors at its keys' centres, timed to end before [beforeTime] (see
 * [reloadAnchorBase]), or null when a letter has no key on this board. Accents fold onto their
 * base keys and apostrophes are skipped: the trie puts dictionary apostrophes back for free.
 */
internal fun tapAnchors(word: String, g: KeyboardGeometry, beforeTime: Long): List<InputToken>? {
    val codes = g.alphabet.encode(AccentFolder.fold(word.lowercase())) ?: return null
    val taps = ArrayList<InputToken>(codes.size)
    val base = reloadAnchorBase(beforeTime, codes.size)
    for (i in codes.indices) {
        val code = codes[i]
        if (code == g.alphabet.apostrophe) continue
        if (!g.hasKey(code)) return null
        taps.add(
            TapToken(
                StreamId.LEFT, code, g.centerX(code), g.centerY(code),
                longPress = false, tStart = base + i, tEnd = base + i + 1,
            ),
        )
    }
    return taps.ifEmpty { null }
}

/** The shift a reloaded word's alternatives are shown in, read off the word itself. */
internal fun shiftOf(word: String): ShiftState.State = when {
    word.length > 1 && word.all { it.isUpperCase() } -> ShiftState.State.CAPS_LOCK
    word.firstOrNull()?.isUpperCase() == true -> ShiftState.State.SHIFT
    else -> ShiftState.State.NONE
}

/** True when the word around the cursor is still the one [offer] was made for. */
internal fun midWordStillThere(before: CharSequence, after: CharSequence, offer: WordAround): Boolean =
    wordAroundCursor(before, after, KineticaConstants.MAX_WORD_LEN) == offer

/**
 * The word an automatic space would be taken back into: the letter run right before the space
 * at the cursor, or "" when there is no such space or word, and the retraction refuses.
 *
 * Split out from [retractAutospace] so the string half can be tested; the dictionary half is
 * one [WordPredictor.isLivePrefix] call at the site.
 */
internal fun wordBeforeAutospace(before: CharSequence): String {
    if (before.isEmpty() || before[before.length - 1] != ' ') return ""
    return trailingLetterRun(before, before.length - 1)
}

/**
 * The run of letters (and apostrophes) ending at [end] in [before], or "" when the
 * character there is not one.
 *
 * One walk for the word the cursor ends ([KineticaIME.reloadWordUnderCursor]), the word an
 * automatic space would rejoin ([wordBeforeAutospace]) and the word a retype deletes
 * ([retypeSpan]), so they cannot disagree about where a word starts. The apostrophe counts:
 * `l'altro` and `don't` are one word to a reader.
 */
internal fun trailingLetterRun(before: CharSequence, end: Int = before.length): String {
    var start = end.coerceIn(0, before.length)
    val stop = start
    while (start > 0 && isWordChar(before[start - 1])) start--
    return before.subSequence(start, stop).toString()
}

/** What [trailingLetterRun] and [commitSpan] both count as belonging to a word. */
private fun isWordChar(c: Char): Boolean = c.isLetter() || c == '\''

/**
 * The expandify trigger sitting at the cursor, and how many characters it occupies there.
 *
 * Its own walk, not [trailingLetterRun], because a trigger is not a word: `.`, `^^` and
 * `(-.-)'` have no letters, so the only boundary is whitespace.
 *
 * One trailing space is skipped and counted in [span], since the autospace has usually
 * written one; the caller puts it back. [span] is measured from the text just handed in,
 * never remembered, so it cannot drift from the text.
 */
internal fun triggerAtCursor(before: CharSequence, maxLen: Int): TriggerSpan {
    var end = before.length
    // At most one, so `word\n\n` or a run of spaces is a boundary and not a licence to
    // reach back over it for something the user has finished with.
    if (end > 0 && before[end - 1] == ' ') end--
    var start = end
    while (start > 0 && end - start < maxLen && !before[start - 1].isWhitespace()) start--
    val trigger = before.subSequence(start, end).toString()
    if (trigger.isEmpty()) return TriggerSpan("", 0)
    return TriggerSpan(trigger, before.length - start)
}

/** [trigger] and the characters it occupies before the cursor, trailing space included. */
internal data class TriggerSpan(val trigger: String, val span: Int)

/**
 * Longest expandify trigger, shared by the walk and by the editor that validates one.
 *
 * Generous against the reporter's own, which run from one character to six, because a
 * trigger may also be a whole keyword standing in for a link. Cheap either way: it bounds
 * one editor read.
 */
const val MAX_TRIGGER_CHARS = 32

/**
 * A target written into a field that holds one line.
 *
 * `commitText` passes a newline straight through, and only the enter key asks whether
 * newlines are allowed. A single-line editor's handling of one is unpredictable: a framework
 * EditText filters it out, others keep it and break their own layout.
 */
internal fun expansionForField(target: String, multiline: Boolean): String =
    if (multiline) target else target.replace('\n', ' ').replace('\r', ' ')

/** What firing an expansion does: write text, run an action, or refuse. */
internal sealed interface ExpansionEffect {
    data class Text(val text: String) : ExpansionEffect
    data class Action(val action: EditorAction) : ExpansionEffect
    data class Combo(val combo: KeyCombo) : ExpansionEffect
    data class Refused(val why: String) : ExpansionEffect
}

/**
 * Reads an expansion's target (#19: "replace the trigger with text, or do an action").
 *
 * A target in the `action:` form runs that action, as chords and edge swipes already do.
 * [EditorAction.NOT_EXPANSION_TARGETS] and a misspelled action are refused, and refused
 * before the caller deletes anything: a typo in a stored target must not cost the trigger.
 * Anything else is text.
 */
internal fun expansionEffect(target: String): ExpansionEffect {
    // A key combination is a target, as it is for chords and edge swipes.
    KeyCombo.parse(target)?.let { return ExpansionEffect.Combo(it) }
    val action = EditorAction.of(target)
    return when {
        action != null && action in EditorAction.NOT_EXPANSION_TARGETS -> ExpansionEffect.Refused(action.name)
        action != null -> ExpansionEffect.Action(action)
        EditorAction.isUnknownAction(target) -> ExpansionEffect.Refused("unknown")
        else -> ExpansionEffect.Text(target)
    }
}

/**
 * The line the cursor is on, or null when it cannot be seen whole.
 *
 * [before] and [after] are what the editor returned for a read of [read] characters each
 * way, and [selected] is any selection between them. A side with no newline that filled
 * its read may continue past it, so the line is refused, not cut. A selection that crosses a
 * line is not one line.
 */
internal fun lineAroundCursor(
    before: CharSequence,
    selected: CharSequence,
    after: CharSequence,
    read: Int,
): String? {
    if (selected.contains('\n')) return null
    val start = before.lastIndexOf('\n')
    if (start < 0 && before.length >= read) return null
    val end = after.indexOf('\n')
    if (end < 0 && after.length >= read) return null
    val head = before.substring(start + 1)
    val tail = if (end < 0) after.toString() else after.substring(0, end)
    return head + selected + tail
}

/**
 * How much of the committed [word] is left before the cursor while backspace eats it: -1 while
 * it is still whole, the letters left while the run before the cursor is a prefix of it, 0 once
 * it is gone, null once the cursor has left it.
 */
internal fun backspaceLeft(before: CharSequence, word: String, maxTrailing: Int): Int? {
    if (commitSpan(before, word, maxTrailing) >= 0) return -1
    val run = trailingLetterRun(before)
    if (run.isEmpty()) return 0
    return if (run.length < word.length && word.startsWith(run, ignoreCase = true)) run.length else null
}

/**
 * How many characters before the cursor the committed [word] and whatever the editor put
 * after it occupy, or -1 when the editor does not hold [word] there.
 *
 * Asked of the editor, since a remembered trailing string drifted and re-casing `be?` gave
 * `bBE`. [maxTrailing] bounds what one mis-tracked commit can delete:
 * a longer run of marks means the word is not where the caller believes, so the answer is a
 * refusal. The word run is walked with [isWordChar], as the recase, retype and reload do.
 * Case is ignored because only the length is used, and auto-capitalization may have written
 * a letter the caller does not carry.
 */
internal fun commitSpan(before: CharSequence, word: String, maxTrailing: Int): Int {
    if (word.isEmpty()) return -1
    var i = before.length
    while (i > 0 && before.length - i < maxTrailing && !isWordChar(before[i - 1])) i--
    val start = i - word.length
    if (start < 0) return -1
    if (!before.subSequence(start, i).toString().equals(word, ignoreCase = true)) return -1
    return before.length - start
}

/**
 * How much text a pick from a kept bar replaces, or -1 to refuse.
 *
 * The stale timeout closed the buffer and left its candidates up, so the pick arrives after
 * the word state was cleared and no remembered length can be trusted.
 * [tailAtClose] is what the editor held before the cursor when the buffer closed: unless it
 * still holds that, something was typed or deleted since. [staleWord] is the earlier
 * decode still on screen, empty when the gesture never produced one.
 */
internal fun keptBarPickSpan(tailNow: CharSequence, tailAtClose: CharSequence, staleWord: String): Int {
    if (tailNow.toString() != tailAtClose.toString()) return -1
    if (!tailAtClose.endsWith(staleWord)) return -1
    return staleWord.length
}

/**
 * Which dictionary each candidate came from, keyed the way `languageOf` looks a word up:
 * lowercased, so a capitalized German noun such as `Haus` is filed under German, not the
 * active language.
 */
internal fun provenanceOf(candidates: List<WordCandidate>): Map<String, String> {
    if (candidates.isEmpty()) return emptyMap()
    val out = HashMap<String, String>(candidates.size * 2)
    for (c in candidates) if (c.language.isNotEmpty()) out.putIfAbsent(c.word.lowercase(), c.language)
    return out
}

/** True when a word character sits on both sides of the cursor. */
internal fun cursorInsideWord(before: CharSequence, after: CharSequence): Boolean =
    before.isNotEmpty() && isWordChar(before[before.length - 1]) &&
        after.isNotEmpty() && isWordChar(after[0])

/**
 * The word the cursor is parked inside, split at the cursor, or null.
 *
 * Both halves are walked out of text read from the editor in the same call, so there is no
 * remembered length to drift from. The caller reads [maxLen] + 1
 * characters each side: a word that runs past either read is longer than [maxLen] and is
 * refused, not half re-cased.
 */
internal fun wordAroundCursor(before: CharSequence, after: CharSequence, maxLen: Int): WordAround? {
    val head = trailingLetterRun(before)
    var end = 0
    while (end < after.length && isWordChar(after[end])) end++
    val tail = after.subSequence(0, end).toString()
    if (head.isEmpty() || tail.isEmpty() || head.length + tail.length > maxLen) return null
    return WordAround(head, tail)
}

/** A word split at the cursor: [head] before it, [tail] after it. */
internal data class WordAround(val head: String, val tail: String)

/**
 * True when a user-driven selection change parks a collapsed cursor somewhere a word
 * could be reopened.
 *
 * Only the shape of the selection is decided here; whether a word precedes the cursor is
 * [KineticaIME.reloadWordUnderCursor]'s question. A selection is never a reopen: the user is
 * acting on a range. Offset 0 is never one either, since nothing precedes it.
 */
internal fun reopensWordUnderCursor(selectionLength: Int, selStart: Int, selEnd: Int): Boolean =
    selectionLength == 0 && selStart == selEnd && selStart > 0

/**
 * How long a one-letter word waits before its automatic space arrives.
 *
 * Longer than a word's, because one letter is weaker evidence: `a` is a word and also the
 * start of `and` and `arrivato`, and only silence tells them apart. Over 22 captured
 * one-letter words against 28 word-starts whose first letter is also a word:
 *
 * | delay | spaced | premature |
 * |---|---|---|
 * | 204 | 21/22 | 10/28 |
 * | 250 | 19/22 | 5/28 |
 * | 275 | 18/22 | 3/28 |
 * | 300 | 17/22 | 3/28 |
 * | 350 | 17/22 | 3/28 |
 * | 600 | 14/22 | 1/28 |
 *
 * [Prefs.SINGLE_LETTER_MIN_DELAY_MS] sits mid-plateau at 300: 275 is 1 ms above a cost sample,
 * 300 has 26 ms of margin below and 161 above. A floor, not a fixed value: a single letter
 * should never space sooner than a word whose delay the user raised.
 */
internal fun singleLetterDelayMs(tapDelayMs: Long, floorMs: Long): Long =
    maxOf(tapDelayMs, floorMs)

/**
 * How much text a retype deletes: the word in progress, else the word just committed with
 * whatever the keyboard put after it, else the letters the cursor is parked at the end of.
 *
 * Autospace commits fast, so a wrong word is usually committed by the time it is noticed; its
 * trailing space goes too, or the retyped word would land one space along. [wordUnderCursor]
 * covers an undecodable buffer: the stale timeout's `abandonWord` clears [tentativeLength]
 * and [CommitMemory] while the letters stay on screen. It is the read `reloadWordUnderCursor`
 * trusts, with the same guard, so a cursor parked mid-word does nothing.
 */
internal fun retypeSpan(
    tentativeLength: Int,
    commitSpan: Int,
    wordUnderCursor: String,
): Int = when {
    tentativeLength > 0 -> tentativeLength
    commitSpan >= 0 -> commitSpan
    else -> wordUnderCursor.length
}

/**
 * Which of [retypeSpan]'s three cases answered, for the trace.
 *
 * Takes [commitSpan], not the committed word: a word the editor no longer holds did not
 * answer, the run under the cursor did, and the trace has to say so.
 */
internal fun retypeSource(tentativeLength: Int, commitSpan: Int): String = when {
    tentativeLength > 0 -> "tentative"
    commitSpan >= 0 -> "commit"
    else -> "cursor"
}

/**
 * How long an undecodable buffer stays open: twice the swipe delay, never under
 * [STALE_TIMEOUT_FLOOR_MS].
 *
 * The swipe delay goes down to 10 ms (#2), and twice that would close a word typed in pieces
 * 20 ms after each piece. The floor is twice the old 100 ms minimum, so every setting
 * reachable before keeps its timeout.
 */
internal fun staleTimeoutMs(swipeDelayMs: Long): Long = maxOf(2 * swipeDelayMs, STALE_TIMEOUT_FLOOR_MS)

internal const val STALE_TIMEOUT_FLOOR_MS = 200L

/**
 * Whether [text] is punctuation that sits against the word before it, so an automatic space
 * in front of it should go.
 *
 * Sentence and clause punctuation and closing brackets hug: "Hi" + "!" is "Hi!". An opening
 * bracket, a dash, a digit or a letter keep the space, as in "a (b)". Listed explicitly, not
 * derived from a character class: an em dash is punctuation and takes a space, and an
 * apostrophe hugs but never arrives here.
 */
internal fun hugsPreviousWord(text: String): Boolean =
    text.length == 1 && text[0] in HUGGING_PUNCTUATION

/**
 * Whether the text before the cursor ends a sentence, so the next letter is capitalized.
 * [before] is at most `CAPS_LOOKBACK_CHARS` of the editor's text; empty is the start of the
 * field.
 *
 * `InputConnection.getCursorCapsMode` cannot answer this: `TextUtils.getCapsMode` reports
 * CAP_MODE_SENTENCES only once whitespace follows the terminator, and CAP_MODE_WORDS at a
 * paragraph start, which a CAP_SENTENCES field masks away. Those are the moments this
 * keyboard asks, since the space before a word is written with that word's commit.
 *
 * - A newline starts a paragraph and so a sentence.
 * - Closing punctuation is skipped, so 'he said "hi."' still ends one.
 * - A lone period inside its own word is an abbreviation, the platform's rule, so "e.g. "
 *   stays lower-case; a run of marks is a terminator, so "Wait..." is one.
 * - Accepted cost: "example.com" typed in a prose field reads "example.Com", as on stock
 *   keyboards; a URL field asks for no sentence caps.
 */
internal fun startsNewSentence(before: CharSequence): Boolean {
    var i = before.length
    while (i > 0 && (before[i - 1] == ' ' || before[i - 1] == '\t')) i--
    val spaced = i < before.length
    if (i == 0) return true
    if (before[i - 1] == '\n') return true
    while (i > 0 && before[i - 1] in SENTENCE_CLOSERS) i--
    if (i == 0) return true
    val last = before[i - 1]
    if (last in SENTENCE_OPENERS) return true
    if (last !in SENTENCE_TERMINATORS) return false
    var run = i
    while (run > 0 && before[run - 1] in SENTENCE_TERMINATORS) run--
    if (i - run > 1 || last != '.') return true
    // One letter and a period with no space yet is an initial, the `e.` of `e.g.`;
    // once a space follows it may end a sentence, as `Plan A. ` does.
    if (!spaced && run >= 1 && before[run - 1].isLetter() &&
        (run == 1 || before[run - 2].isWhitespace())
    ) return false
    var j = run
    while (j > 0) {
        val c = before[j - 1]
        if (c == ' ' || c == '\t' || c == '\n') break
        if (c == '.') return false
        j--
    }
    return true
}

/**
 * How much one backspace removes with tidy spaces on: a run of spaces before the cursor
 * becomes one, so the next press takes the last; anything else is one character.
 */
internal fun backspaceSpan(before: CharSequence): Int {
    var n = 0
    while (n < before.length && before[before.length - 1 - n] == ' ') n++
    return if (n >= 2) n - 1 else 1
}

/** What a space tap does with tidy spaces on. */
internal enum class SpaceTap { WRITE, SWALLOW, SENTENCE_END }

/**
 * A space tapped straight after a space: with the double-space full stop on and a word before
 * it, the pair ends the sentence as a double tap would; otherwise the tap is dropped, because
 * the space is already there. [before] is the two characters before the cursor.
 */
internal fun tidySpaceTap(before: CharSequence, doubleSpacePeriod: Boolean): SpaceTap = when {
    before.isEmpty() || before[before.length - 1] != ' ' -> SpaceTap.WRITE
    doubleSpacePeriod && doubleSpaceEndsSentence(before) -> SpaceTap.SENTENCE_END
    else -> SpaceTap.SWALLOW
}

/**
 * Whether the automatic space after a word should be written: with tidy spaces on, not when the
 * editor already has whitespace or a closing quote or bracket right after the cursor.
 */
internal fun autospaceWanted(tidy: Boolean, after: CharSequence?): Boolean {
    if (!tidy || after.isNullOrEmpty()) return true
    val c = after[0]
    if (c in SENTENCE_CLOSERS) return false
    // A space or tab holds only with a word right after it (`word| next`). A newline, or a
    // space a field keeps at its end, is not the separator the next word needs: counting them
    // silenced every autospace in such a field (17 of 17 holds in one capture).
    if (c == ' ' || c == '\t') return !(after.length >= 2 && !after[1].isWhitespace())
    return true
}

/**
 * Whether the space just typed can become a sentence end.
 *
 * [before] is the two characters before the cursor. True only for a single space after a
 * letter or digit, the one shape where ". " reads as finishing a sentence. A run of spaces is
 * deliberate whitespace, and after punctuation `e.g. ` would become `e.g.. `.
 */
internal fun doubleSpaceEndsSentence(before: CharSequence): Boolean {
    if (before.length < 2) return false
    if (before[before.length - 1] != ' ') return false
    return before[before.length - 2].isLetterOrDigit()
}

private const val SENTENCE_TERMINATORS = ".!?\u2026"

/** Skipped before looking for a terminator: quotes and closing brackets. */
private const val SENTENCE_CLOSERS = ")]}\"'\u00bb\u201d\u2019"

/** Spanish opens a sentence with these, so the word after one begins it. */
private const val SENTENCE_OPENERS = "\u00bf\u00a1"

// Punctuation that opens a word instead of joining one, so a space after the word that
// follows it is still right: brackets and the opening quote forms.
// Not the straight apostrophe, although it opens single quotes too: Italian elision is far
// more common than quoted text, and `al` is a word, so `l'altro` would become `l'al tro`.
// The cost is one space typed by hand inside 'quoted text'.
private const val WORD_OPENERS = "([{\"\u00ab\u201c\u2018"

// Punctuation that binds two word-shaped pieces into one token. Every one of these
// appears mid-token in something a keyboard has to type without spacing it:
// snake_case, e-mail, don't, example.com, a/b, user@host, a\\b, C:name.
private const val WORD_JOINERS = "_-'\u2019./\\@:"

private const val HUGGING_PUNCTUATION = ".,!?;:)]}\u00bb\u2026"

/**
 * True when a slide or an unlearn takes a word that was merged into the trie (at or above the
 * merge floor) down: its trie frequency still holds the old count until the next load.
 */
internal fun userDictDemoted(countBefore: Int, countAfter: Int): Boolean =
    countBefore >= KineticaConstants.PERSONAL_MERGE_MIN_COUNT && countAfter < countBefore

/**
 * Whether the trie must be rebuilt because a word's personal count has just made it
 * mergeable, judged from the count before and after one [KineticaIME.learnWord] call.
 *
 * The count map only supplies a ranking multiplier and cannot boost a candidate the trie
 * never produced, so a word the dictionary lacks stays undecodable until a load merges it.
 * True on the crossing only, so a word the reload cannot admit
 * (blocked, or past USER_DICT_LIMIT) costs one parse, not one per commit.
 */
internal fun userDictNeedsReload(
    countBefore: Int,
    countAfter: Int,
    trieHasWord: Boolean,
): Boolean = !trieHasWord &&
    countBefore < KineticaConstants.PERSONAL_MERGE_MIN_COUNT &&
    countAfter >= KineticaConstants.PERSONAL_MERGE_MIN_COUNT

/**
 * [candidates] with the word a retype just rejected moved to the end.
 *
 * Demoted, not dropped: of 78 retype presses, 12 handed back the word just rejected, but the
 * wanted word was among the alternates in only 5 of them, and a retype aimed at a
 * space still needs the word. Moving it last promotes the runner-up and hides nothing.
 *
 * Returns [candidates] itself when there is nothing to do, so the caller can test identity.
 */
internal fun demoteRejected(
    candidates: List<WordCandidate>,
    rejected: String?,
): List<WordCandidate> {
    if (rejected == null || candidates.size < 2) return candidates
    val i = candidates.indexOfFirst { it.word.equals(rejected, ignoreCase = true) }
    if (i < 0) return candidates
    return candidates.toMutableList().apply { add(removeAt(i)) }
}

/**
 * Whether committing [word] adds a unit of personal weight, given the word [reloadedFrom]
 * seeded back from the editor (null when typed from nothing).
 *
 * Deleting only the space after "hello" reloads it, and the next delimiter commits it again:
 * one authored word would earn two units, the self-reinforcing drift the merge floor and the
 * fade are tuned against. An edit still learns: "hell" reloaded and committed as "hello"
 * counts. Pure because the learn path has no JVM reach.
 */
internal fun learnsOnCommit(word: String, reloadedFrom: String?): Boolean {
    if (reloadedFrom == null) return true
    return !word.equals(reloadedFrom, ignoreCase = true)
}

/**
 * Whether a commit puts the correction strip up. [offersCorrections] is the field's own
 * answer, which is where the privateMode / noLearning split is decided and documented.
 *
 * An [optionCount] of one is suppressed: its only zone is the selected one, and a tap on the
 * selected zone is a no-op, so the strip would offer nothing.
 */
internal fun showsCorrectionStrip(
    word: String,
    offersCorrections: Boolean,
    optionCount: Int,
): Boolean = word.isNotEmpty() && offersCorrections && optionCount > 1

/**
 * The typed letters' zone on the bar, or "" for none. Letters that leave the accents off a word
 * (`pojsc` for `pójść`) are not offered while autocorrect is on: the word is on the bar, and the
 * correction strip still holds the letters after a commit. With autocorrect off the user asked
 * for what they type.
 */
internal fun literalZone(literal: String, autocorrects: Boolean, leavesAccentsOff: Boolean, blocked: Boolean = false): String =
    if (blocked || (autocorrects && leavesAccentsOff)) "" else literal

/**
 * How much a backspace takes back to undo an autocorrect: the corrected word, plus the one space
 * or mark typed after it when [typedAfter] is 1. -1 when the text before the cursor no longer
 * ends that way, or more than one character followed.
 */
internal fun autocorrectUndoSpan(before: CharSequence, corrected: String, typedAfter: Int): Int {
    if (typedAfter !in 0..1 || corrected.isEmpty()) return -1
    val span = corrected.length + typedAfter
    if (before.length < span) return -1
    val tail = before.subSequence(before.length - span, before.length)
    if (!tail.startsWith(corrected)) return -1
    if (typedAfter == 1 && tail.last().isLetterOrDigit()) return -1
    return span
}

/** [words] without the blocked spellings: a block means never offered, wherever the word was kept. */
internal fun notBlocked(words: List<String>, blocked: Set<String>): List<String> =
    if (blocked.isEmpty()) words else words.filter { it.lowercase() !in blocked }

/**
 * Composition-mode zone list: the ranked candidates, then the all-tap literal as the last zone
 * when it is not already among them, so an out-of-dictionary word commits verbatim with one
 * tap. Pure so the JVM suite can pin it; callers pass an empty literal for swipe buffers.
 */
internal fun suggestionZoneWords(candidates: List<String>, literal: String): List<String> =
    if (literal.isEmpty() || candidates.contains(literal)) candidates
    else candidates + literal

/**
 * The word a next-word prediction follows, from the text before the cursor, or null.
 *
 * Only after a finished word and its space: nothing mid-word, nothing after a sentence end or
 * other punctuation (there is no pair across it to read), and nothing inside a token such as
 * `name@mail.com`, whose last piece is not a word the user wrote.
 */
internal fun previousWordForPrediction(before: CharSequence?): String? {
    if (before.isNullOrEmpty() || before.last() != ' ') return null
    var end = before.length
    while (end > 0 && before[end - 1] == ' ') end--
    var start = end
    while (start > 0 && (before[start - 1].isLetter() || before[start - 1] == '\'')) start--
    if (start == end) return null
    if (start > 0 && !before[start - 1].isWhitespace() && before[start - 1] !in PREDICT_OPENERS) return null
    val word = before.substring(start, end).trim('\'')
    return word.ifEmpty { null }
}

private const val PREDICT_OPENERS = "\"(«“‘"

/** Two languages' predictions in one list: strongest first, one entry per word. */
internal fun mergeNextWords(
    active: List<WordPredictor.NextWord>,
    other: List<WordPredictor.NextWord>,
    limit: Int,
): List<WordPredictor.NextWord> {
    val seen = HashSet<String>()
    return (active + other).sortedByDescending { it.score }.filter { seen.add(it.word.lowercase()) }.take(limit)
}
