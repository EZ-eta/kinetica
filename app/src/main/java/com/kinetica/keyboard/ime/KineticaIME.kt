package com.kinetica.keyboard.ime

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.preference.PreferenceManager
import com.kinetica.keyboard.data.DictionaryStore
import com.kinetica.keyboard.data.KineticaDb
import com.kinetica.keyboard.data.UserWord
import com.kinetica.keyboard.engine.AccentFolder
import com.kinetica.keyboard.engine.Alphabet
import com.kinetica.keyboard.engine.DecodeTrace
import com.kinetica.keyboard.engine.DictionaryLoader
import com.kinetica.keyboard.engine.GestureEngine
import com.kinetica.keyboard.engine.KeyboardGeometry
import com.kinetica.keyboard.engine.KineticaConstants
import com.kinetica.keyboard.engine.WordComposer
import com.kinetica.keyboard.engine.WordPredictor
import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.TapToken
import com.kinetica.keyboard.engine.models.WordCandidate
import com.kinetica.keyboard.keys.AutoCapitalization
import com.kinetica.keyboard.keys.DeleteSpan
import com.kinetica.keyboard.keys.EdgeSwipeBindings
import com.kinetica.keyboard.keys.ShiftState
import com.kinetica.keyboard.layout.Key
import com.kinetica.keyboard.layout.KeyType
import com.kinetica.keyboard.layout.KeyboardLayout
import com.kinetica.keyboard.layout.LayoutLoader
import com.kinetica.keyboard.layout.LayoutMutations
import com.kinetica.keyboard.settings.KeyboardConfig
import com.kinetica.keyboard.settings.Prefs
import com.kinetica.keyboard.settings.SettingsActivity
import com.kinetica.keyboard.ui.EmojiPickerView
import com.kinetica.keyboard.ui.InputContainerView
import com.kinetica.keyboard.ui.KeyboardTheme
import com.kinetica.keyboard.ui.KeyboardView
import com.kinetica.keyboard.ui.SuggestionBarView
import java.io.IOException
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
    private val mainExecutor = Executor { mainHandler.post(it) }
    private val decodeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "kinetica-decode").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    private val dbExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "kinetica-db")
    }

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

    // Personal commit counts, mirrored from the user_words table. Main thread
    // writes, the decode thread reads through the predictor: concurrent map.
    // One map per resident predictor - user_words is keyed by language and so
    // is the boost, so a word committed in the other enabled language must be
    // reinforced there and nowhere else.
    private var personalCounts = ConcurrentHashMap<String, Int>()
    private var secondaryCounts = ConcurrentHashMap<String, Int>()
    private var secondaryLanguage: String? = null

    // Language of each candidate currently on offer, keyed by its display form
    // (lowercased). The bar and the commit path work in strings, so this is how
    // a picked or committed word finds the dictionary it came from.
    private var candidateLanguages: Map<String, String> = emptyMap()

    // Same map, snapshotted at commit time: the correction strip outlives
    // lastCandidates, and a correction pick is a real commit that must learn
    // into the right language too.
    private var correctionLanguages: Map<String, String> = emptyMap()

    // letter code -> expansion, mirrored from chord_shortcuts. Read on the UI
    // thread at pointer-down; refreshed on every input start so edits made in
    // settings apply as soon as the keyboard regains focus.
    @Volatile
    private var chordMap: Map<Int, String> = emptyMap()

    private var keyboardView: KeyboardView? = null
    private var suggestionBar: SuggestionBarView? = null
    private var containerView: InputContainerView? = null
    private var emojiPicker: EmojiPickerView? = null
    private var currentGeometry: KeyboardGeometry? = null
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
    private var lastLiteral = ""
    private var expectedSelectionUpdates = 0
    // Set when the latest decode of a swipe-bearing word returned no candidates:
    // the visible tentative is then a stale earlier partial decode that must not
    // be autospaced or learned.
    private var swipeDecodeEmpty = false

    // Correction strip: the last committed word and what followed it.
    private var lastCommitWord: String? = null
    private var lastCommitTrailing = ""

    private var autospacePending = false
    private val autospaceRunnable = Runnable {
        autospacePending = false
        if (composer?.hasPendingWord == true && composer?.hasSwipeToken() == true) {
            if (finalizePendingWord()) {
                commitTracked(" ")
                lastCommitTrailing = " "
                updateAutoShift()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Decode tracing to Logcat in debuggable builds only: the resume-after-
        // interruption bug class is a real two-thumb
        // gesture the JVM suite cannot reproduce, so `adb logcat -s KineticaTrace`
        // is how a device session captures the actual token buffer and split.
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        DecodeTrace.sink = if (debuggable) { m -> Log.d("KineticaTrace", m) } else null
        engine.maxPointers = 2
        @Suppress("DEPRECATION")
        vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        config = KeyboardConfig.from(prefs)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, _ ->
            val previous = config
            config = KeyboardConfig.from(p)
            applyViewConfig()
            if (config.peckMode != previous.peckMode) {
                // Entering or leaving literal mode mid-word would leave a
                // half-tracked tentative; settle it as plain text first.
                abandonWord()
            }
            if (config.language != previous.language) {
                // Swap layout immediately; predictions swap when the new
                // dictionary finishes parsing (the old one keeps serving).
                abandonWord()
                keyboardView?.setKeyboardLayout(alphaLayout())
                loadDictionaryAsync()
            } else if (config.dictionaryGeneration != previous.dictionaryGeneration ||
                config.autoDetectLanguage != previous.autoDetectLanguage ||
                config.enabledLanguages != previous.enabledLanguages
            ) {
                // Stored dictionary data changed, or the secondary-language
                // predictor must be loaded or dropped: reload in place.
                loadDictionaryAsync()
            } else if (config.emojiKey != previous.emojiKey ||
                config.numberPriority != previous.numberPriority ||
                config.commaMode != previous.commaMode ||
                config.commaCustom != previous.commaCustom
            ) {
                closeEmojiPicker()
                keyboardView?.setKeyboardLayout(alphaLayout())
            }
        }
        prefListener = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
        loadDictionaryAsync()
    }

    override fun onDestroy() {
        prefListener?.let {
            PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(it)
        }
        decodeExecutor.shutdown()
        dbExecutor.shutdown()
        super.onDestroy()
    }

    private fun loadDictionaryAsync() {
        val lang = config.language
        // Auto-detect is pairwise by design: one resident secondaryPredictor,
        // so with 3+ enabled languages only the FIRST non-active one
        // participates. Documented in ADDING_A_LANGUAGE.md; lifting it means
        // one resident predictor per enabled language (memory) and an N-way
        // vote in WordComposer.
        val detectLang = if (config.autoDetectLanguage) {
            config.enabledLanguages.firstOrNull { it != lang }
        } else {
            null
        }
        Thread({
            try {
                // Learned words merge into the trie at load, gated and scaled
                // by the engine's merge policy (PERSONAL_MERGE_MIN_COUNT /
                // USER_FREQ_SCALE - rationale in KineticaConstants).
                val userRows = try {
                    KineticaDb.get(this).userWords().topN(lang, USER_DICT_LIMIT)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "user dictionary unavailable", e)
                    emptyList()
                }
                val userWords =
                    DictionaryLoader.userWordsForMerge(userRows.map { it.word to it.frequency })
                val dict = openWordlist(lang).bufferedReader().use {
                    DictionaryLoader.load(it, userWords)
                }
                val bigrams = assets.open(bigramsAsset(lang)).bufferedReader().use {
                    DictionaryLoader.loadBigrams(it, dict.trie)
                }
                // The other enabled language stays resident so swipe decodes can
                // consult both dictionaries. It carries its OWN personal counts:
                // asymmetric weighting used to distort the two lists against each
                // other,
                // and now that both lists rank together the asymmetry would be a
                // standing thumb on the scale for the active language - measured
                // at up to 1.83x on device against 1.00x for every foreign word.
                var altRows: List<UserWord> = emptyList()
                val alt = detectLang?.let { other ->
                    try {
                        altRows = try {
                            KineticaDb.get(this).userWords().topN(other, USER_DICT_LIMIT)
                        } catch (e: RuntimeException) {
                            Log.w(TAG, "user dictionary unavailable for $other", e)
                            emptyList()
                        }
                        val d = openWordlist(other).bufferedReader().use {
                            DictionaryLoader.load(
                                it,
                                DictionaryLoader.userWordsForMerge(
                                    altRows.map { r -> r.word to r.frequency },
                                ),
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
                mainHandler.post {
                    // The user may have toggled languages again mid-parse.
                    if (lang != config.language) return@post
                    val counts = ConcurrentHashMap<String, Int>(userRows.size * 2)
                    for (row in userRows) counts[row.word] = row.frequency
                    personalCounts = counts
                    val p = WordPredictor(
                        dict.trie, bigrams, currentGeometry, dict.forms, counts, lang,
                    )
                    predictor = p
                    val altCounts = ConcurrentHashMap<String, Int>(altRows.size * 2)
                    for (row in altRows) altCounts[row.word] = row.frequency
                    secondaryCounts = altCounts
                    secondaryLanguage = detectLang
                    // The language stamp is load-bearing, not decoration:
                    // WordComposer.merge tells the two lists apart by it.
                    secondaryPredictor = alt?.let { (d, b) ->
                        WordPredictor(
                            d.trie, b, currentGeometry, d.forms, altCounts,
                            language = detectLang ?: "",
                        )
                    }
                    composer = WordComposer(p, decodeExecutor, mainExecutor, this).also {
                        it.alternatePredictor = secondaryPredictor
                    }
                    Log.i(
                        TAG,
                        "dictionary ready [$lang]: ${dict.trie.wordCount} words, " +
                            "${bigrams.size} bigrams, ${dict.forms.size} display forms" +
                            (detectLang?.let { d -> ", auto-detect vs $d" } ?: ""),
                    )
                }
            } catch (e: IOException) {
                Log.e(TAG, "dictionary load failed for $lang", e)
            }
        }, "kinetica-dict-load").start()
    }

    /** Imported (AOSP-merged) wordlist overrides the bundled asset. */
    private fun openWordlist(lang: String): java.io.InputStream {
        val override = DictionaryStore.wordlistOverride(this, lang)
        return if (override.exists()) override.inputStream() else assets.open(wordlistAsset(lang))
    }

    // Bundled layout names, listed once: the language alpha layer falls back
    // to plain qwerty when no qwerty_<lang>.json is bundled, so a newly
    // registered language never silently inherits another language's accent
    // alternates (before this, any third language got the English layout).
    private val bundledLayouts: Set<String> by lazy {
        (assets.list("layouts") ?: emptyArray())
            .map { it.removeSuffix(".json") }
            .toSet()
    }

    /** Alpha layer asset for the active language. */
    private fun alphaLayoutName(): String {
        val name = "qwerty_${config.language}"
        return if (name in bundledLayouts) name else "qwerty"
    }

    /** Alpha layout with settings-driven mutations applied. */
    private fun alphaLayout(): KeyboardLayout {
        var l = layoutFor(alphaLayoutName())
        // Enter's held/slide-up alternate popup is always on; the
        // symbols are settings-configurable (first is the primary).
        l = LayoutMutations.withEnterAlternates(l, config.enterAlternates)
        // Optional apostrophe key in the home-row right padding.
        if (config.apostropheKey) l = LayoutMutations.withApostropheKey(l)
        if (config.emojiKey) l = LayoutMutations.withEmojiOnComma(l)
        if (config.numberPriority) l = LayoutMutations.withNumberPriority(l)
        // After the emoji mutation, so a removal can relocate the emoji
        // alternate and a repurposed key keeps it in its popup.
        l = LayoutMutations.withCommaKey(l, config.commaMode, config.commaCustom)
        return l
    }

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
            barHeightPx = dpToPx(SUGGESTION_BAR_DP),
            keyboardHeightPx = keyboardHeightPx(),
            minKeyboardPx = minKeyboardPx(),
            maxKeyboardPx = maxKeyboardPx(),
            onHeightCommitted = { px -> persistHeightPct(px) },
        )
        containerView = container
        applyViewConfig()
        return container
    }

    private fun openEmojiPicker() {
        val container = containerView ?: return
        cancelAutospace()
        finalizePendingWord()
        val picker = emojiPicker ?: EmojiPickerView(
            this,
            onEmoji = { commitTracked(it) },
            onBackspace = { onBackspace() },
            onClose = { closeEmojiPicker() },
        ).also { emojiPicker = it }
        container.showEmojiPicker(picker)
    }

    private fun closeEmojiPicker() {
        containerView?.hideEmojiPicker(emojiPicker)
    }

    private fun layoutFor(name: String): KeyboardLayout =
        layouts.getOrPut(name) { LayoutLoader.load(assets, "layouts/$name.json") }

    // The 180dp floor must yield to the percentage ceiling: on short screens
    // (landscape phones, split-screen) half the screen is less than 180dp, and
    // an inverted min..max range makes coerceIn throw - which killed the whole
    // app process the moment the keyboard opened.
    private fun minKeyboardPx(): Int = minOf(
        maxOf(
            dpToPx(MIN_KEYBOARD_DP),
            resources.displayMetrics.heightPixels * Prefs.MIN_HEIGHT_PCT / 100,
        ),
        maxKeyboardPx(),
    )

    private fun maxKeyboardPx(): Int =
        resources.displayMetrics.heightPixels * Prefs.MAX_HEIGHT_PCT / 100

    private fun persistHeightPct(px: Int) {
        val screenH = resources.displayMetrics.heightPixels
        val pct = (px * 100f / screenH).toInt()
            .coerceIn(Prefs.MIN_HEIGHT_PCT, Prefs.MAX_HEIGHT_PCT)
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit().putInt(Prefs.KEYBOARD_HEIGHT_PCT, pct).apply()
    }

    /** Pushes the current [config] and editor-derived flags into the views. */
    private fun applyViewConfig() {
        val kv = keyboardView ?: return
        kv.zenMode = config.zenMode
        // A swipe trail visually leaks what was typed: never draw one in
        // password or no-learning fields. In peck mode swipes do nothing, so
        // a trail would advertise a gesture that has no effect.
        kv.trailsEnabled = !editorState.privateMode && !config.peckMode
        val theme = KeyboardTheme.resolve(this, config.themeMode, config.themeColor)
        kv.theme = theme
        suggestionBar?.theme = theme
        containerView?.applyTheme(theme)
        kv.trailBaseHue =
            if (config.trailColorMode == "theme") theme.accentHue else config.trailBaseHue
        kv.longPressMs = config.longPressMs
        kv.layoutMode = config.layoutMode
        kv.autospaceDot = config.autospace
        kv.backspaceCharSlide = config.backspaceCharSlide
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
        val targetH = keyboardHeightPx()
        val lp = kv.layoutParams
        if (lp != null && lp.height != targetH) {
            lp.height = targetH
            kv.requestLayout()
        }
    }

    /**
     * Spacebar status text. The language code is shown only when
     * several languages are enabled - for a monolingual setup it carries no
     * information and would be pure noise. Peck mode appends a TAP marker so
     * the disabled gesture engine is visible at a glance.
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

    private fun keyboardHeightPx(): Int {
        val screenH = resources.displayMetrics.heightPixels
        return ((screenH * config.heightPct) / 100).coerceIn(minKeyboardPx(), maxKeyboardPx())
    }

    private fun dpToPx(dp: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics,
    ).toInt()

    // ------------------------------------------------------------- lifecycle

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        editorState = EditorState.from(attribute)
        abandonWord()
        composer?.reset()
        expectedSelectionUpdates = 0
        updateAutoShift()
        reloadChords()
    }

    private fun reloadChords() {
        dbExecutor.execute {
            val rows = try {
                KineticaDb.get(this).chordShortcuts().all()
            } catch (e: RuntimeException) {
                Log.w(TAG, "chord table unavailable", e)
                emptyList()
            }
            val map = HashMap<Int, String>(rows.size * 2)
            for (row in rows) {
                val code = (row.chord.firstOrNull() ?: continue) - 'a'
                if (code in 0 until Alphabet.LETTERS && row.expansion.isNotEmpty()) {
                    map[code] = row.expansion
                }
            }
            chordMap = map
        }
    }

    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        suggestionBar?.clearSuggestions()
        keyboardView?.setShiftUppercase(shift.isShifted)
        closeEmojiPicker()
        applyViewConfig()
    }

    override fun onFinishInput() {
        abandonWord()
        composer?.reset()
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
        if (expectedSelectionUpdates > 0) {
            expectedSelectionUpdates--
            return
        }
        // The user moved the cursor themselves: the pending word is no longer
        // under the cursor, abandon it.
        if (tentativeLength > 0 || composer?.hasPendingWord == true) {
            abandonWord()
        }
    }

    // -------------------------------------------------- engine listener (UI)

    override fun onTokenFinalized(token: InputToken) {
        cancelAutospace()
        clearCorrection()
        // Peck-type mode: pure literal insertion. Taps commit their letter
        // (shift-aware) and never feed the composer, so there is no pending
        // word, no suggestions, no autocorrect, and no learning; swipes are
        // ignored outright. For out-of-dictionary text the engine mangles.
        if (config.peckMode) {
            if (token is TapToken) {
                val ch = shift.apply(Alphabet.charOf(token.code))
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
                // Long presses never reach here: the view's hold timer cancels
                // the engine pointer and opens the alternates popup instead.
                if (tentativeLength == 0) wordShift = shift.state
                val ch = shift.apply(Alphabet.charOf(token.code))
                commitTracked(ch.toString())
                tentativeLength += 1
                tentativeWord += ch
                if (shift.state == ShiftState.State.SHIFT) {
                    shift.onLetterCommitted()
                    keyboardView?.setShiftUppercase(shift.isShifted)
                }
                comp?.onToken(token)
            }
            is SwipeToken -> {
                if (tentativeLength == 0) wordShift = shift.state
                if (comp == null) {
                    Log.w(TAG, "swipe before dictionary ready, dropped")
                    return
                }
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
        lastCandidates = candidates
        lastLiteral = literal
        candidateLanguages = if (candidates.isEmpty()) {
            emptyMap()
        } else {
            HashMap<String, String>(candidates.size * 2).apply {
                for (c in candidates) if (c.language.isNotEmpty()) putIfAbsent(c.word, c.language)
            }
        }
        if (!editorState.privateMode) {
            pushSuggestions()
        }
        val comp = composer ?: return
        // Swipe-bearing words are tentative: the editor shows the candidate the
        // merge cleared for auto-commit. All-tap words keep the literal text.
        if (comp.hasSwipeToken()) {
            if (tentative != null) {
                swipeDecodeEmpty = false
                replaceTentative(displayWord(tentative.word))
                // Autospace arms only once both thumbs are up; any new touch
                // cancels it via onKeyPressFeedback.
                if (!engine.hasActivePointers()) scheduleAutospace()
            } else {
                // Nothing has earned the editor: either the full token buffer
                // has no decode at all (the visible tentative is a stale
                // earlier partial one), or only a non-active language could
                // explain the gesture, which WordComposer.merge treats the same
                // way - evidence the gesture was undecodable, not evidence
                // about language. Flag the word so a
                // delimiter neither autospaces nor learns it. The bar keeps
                // whatever candidates exist and they stay pickable.
                swipeDecodeEmpty = true
                cancelAutospace()
            }
        }
    }

    private fun scheduleAutospace() {
        if (!config.autospace || editorState.privateMode) return
        cancelAutospace()
        autospacePending = true
        mainHandler.postDelayed(autospaceRunnable, config.autospaceDelayMs)
    }

    private fun cancelAutospace() {
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
        }

        override fun onCursorMove(direction: Int) {
            // The resulting selection change is unexpected by design: it will
            // abandon the pending word via onUpdateSelection.
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
            finalizeThenCommitText(output)
            updateAutoShift()
        }

        override fun onKeyAlternate(key: Key, text: String) {
            cancelAutospace()
            if (text.isEmpty()) return
            if (text == LayoutMutations.EMOJI_ALTERNATE) {
                openEmojiPicker()
                return
            }
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

        override fun onSettingsRequested() {
            cancelAutospace()
            finalizePendingWord()
            requestHideSelf(0)
            startActivity(
                Intent(this@KineticaIME, SettingsActivity::class.java).apply {
                    // A service context has no activity task to attach to.
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
        }

        override fun hasChord(letterCode: Int): Boolean =
            isLangCycleChord(letterCode) || isPeckChord(letterCode) ||
                chordMap.containsKey(letterCode)

        override fun onChordTriggered(letterCode: Int) {
            // Reserved chords own their designated letters even when a text
            // chord is also assigned: mode switches are the rarer, more
            // deliberate acts and must stay reachable. Precedence: language
            // cycle, then peck toggle, then user text chords.
            if (isLangCycleChord(letterCode)) {
                cycleLanguage()
                return
            }
            if (isPeckChord(letterCode)) {
                togglePeckMode()
                return
            }
            val expansion = chordMap[letterCode] ?: return
            cancelAutospace()
            finalizeThenCommitText(expansion)
            updateAutoShift()
        }

        override fun onKeyPressFeedback() {
            cancelAutospace()
            vibrateForKeyPress()
        }
    }

    private val suggestionListener = object : SuggestionBarView.Listener {
        override fun onSuggestionPicked(word: String) =
            this@KineticaIME.onSuggestionPicked(word)

        override fun onCorrectionPicked(replacement: String) =
            this@KineticaIME.onCorrectionPicked(replacement)

        override fun onSuggestionReinforced(word: String, delta: Int) =
            this@KineticaIME.onSuggestionReinforced(word, delta)

        override fun onReinforceStep() = vibrateForKeyPress()
    }

    /** Current candidates -> suggestion bar, with personal-weight badges. */
    private fun pushSuggestions() {
        // The all-tap literal rides along as a final escape-hatch zone: the
        // one-tap way to commit an out-of-dictionary word verbatim (mirrors
        // the literal option commitWordInternal appends in correction mode).
        // A pick rides onSuggestionPicked, which bypasses finalizePendingWord,
        // so autocorrect never touches it.
        // A typed accent lives in tentativeWord but not in the buffer's literal,
        // which WordComposer.buildLiteral rebuilds from folded key codes - so the
        // zone would offer "matador" for a typed "matadór". When the two agree up
        // to folding, what the user actually typed wins.
        val literal = when {
            composer?.hasSwipeToken() == true -> ""
            tentativeLength > 0 &&
                AccentFolder.fold(tentativeWord.lowercase()) == lastLiteral -> tentativeWord
            else -> displayWord(lastLiteral)
        }
        suggestionBar?.setSuggestions(
            suggestionZoneWords(lastCandidates.map { displayWord(it.word) }, literal)
                .map { barSuggestion(it) },
        )
    }

    private fun barSuggestion(display: String): SuggestionBarView.Suggestion {
        val count = countsFor(languageOf(display))[display.lowercase()] ?: 0
        return SuggestionBarView.Suggestion(
            display,
            tier = KineticaConstants.personalTier(count),
            count = count,
        )
    }

    /** Long-press weight adjustment from the bar: signed, scaled by config. */
    private fun onSuggestionReinforced(word: String, delta: Int) {
        if (editorState.privateMode || delta == 0) return
        learnWord(word, delta)
        vibrateForKeyPress()
        // Redraw so the adjusted badge appears under the finger.
        pushSuggestions()
    }

    private fun finalizeThenCommitText(text: String) {
        val committed = finalizePendingWord()
        commitTracked(text)
        if (committed) lastCommitTrailing = text
    }

    /**
     * An accented letter chosen from a long-press popup EXTENDS the word being
     * written instead of ending it. Returns false when [text] is not one, so the
     * caller falls back to the shipped commit-then-insert path.
     *
     * Long presses never reach onTokenFinalized: the view's hold timer cancels
     * the engine pointer (KeyboardView.onHoldTimerFired -> cancelPointer, which
     * emits no token) and opens the popup, so the only way in is here. Every
     * insertion in this app then went through finalizeThenCommitText, which is
     * right for a digit, a symbol or an emoji - they end a word - and wrong for
     * an accent, which is a letter of it: tap-typing "matadór" committed "matad"
     * at the accent and started a fresh buffer for the "r". Sub-word
     * insertions in the same family are deliberately unchanged: the optional
     * apostrophe key breaks the word because "nell'immagine" is no
     * dictionary word, and the popup's own base cell is not an accent.
     *
     * The token carries the FOLDED base key, which is what the trie is keyed on,
     * while the editor and [tentativeWord] carry the accented glyph. That
     * divergence is the shipped pattern - reloadWordUnderCursor already seeds a
     * folded token buffer under accented text - and it is what lets the decode
     * keep composing while the user's explicit accent survives on screen.
     */
    private fun composeAccentedLetter(text: String): Boolean {
        if (config.peckMode || editorState.privateMode) return false
        val comp = composer ?: return false
        val g = currentGeometry ?: return false
        val code = AccentFolder.accentedLetterCode(text)
        if (code < 0 || !g.hasKey(code)) return false
        if (comp.tokenCount >= KineticaConstants.MAX_WORD_LEN) return false
        clearCorrection()
        if (tentativeLength == 0) wordShift = shift.state
        // The popup already applied the layout's case to its cells, so the glyph
        // goes in as chosen rather than through shift.apply a second time.
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

    // Reversible backspace slide: [stagedDeletion] is the span that WOULD be
    // deleted, previewed struck-through above the backspace key. Nothing is
    // deleted until the finger lifts with a non-empty stage; sliding back
    // right retracts unit by unit down to a no-op. A unit is a word, or a single
    // character when the char-slide preference is on.
    private var stagedDeletion = ""

    private fun stageDeletion(units: Int, chars: Boolean) {
        if (units <= 0) {
            if (stagedDeletion.isNotEmpty()) {
                stagedDeletion = ""
                keyboardView?.setDeletePreview(null)
            }
            return
        }
        cancelAutospace()
        if (composer?.hasPendingWord == true || tentativeLength > 0) abandonWord()
        val before = ich.textBeforeCursor(STAGE_FETCH_CHARS) ?: return
        val span = if (chars) DeleteSpan.chars(before, units) else DeleteSpan.words(before, units)
        stagedDeletion = before.subSequence(before.length - span, before.length).toString()
        keyboardView?.setDeletePreview(
            when {
                stagedDeletion.isEmpty() -> null
                // Never echo password characters into the preview chip.
                editorState.privateMode -> "•".repeat(stagedDeletion.length.coerceAtMost(24))
                else -> stagedDeletion
            },
        )
    }

    private fun commitStagedDeletion() {
        val staged = stagedDeletion
        stagedDeletion = ""
        keyboardView?.setDeletePreview(null)
        if (staged.isEmpty()) return
        ich.deleteBeforeCursor(staged.length)
        expectedSelectionUpdates++
        abandonWord()
        updateAutoShift()
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
        cancelAutospace()
        val committed = finalizePendingWord()
        commitTracked(" ")
        if (committed) lastCommitTrailing = " "
        updateAutoShift()
    }

    private fun onPunctuation(text: String) {
        cancelAutospace()
        // Reserved action outputs (configurable comma key) never reach the
        // editor as text; the pending word is finalized first so the action
        // applies to settled content.
        when (text) {
            LayoutMutations.ACTION_PASTE -> {
                finalizePendingWord()
                ich.performContextMenuAction(android.R.id.paste)
                expectedSelectionUpdates++
                return
            }
            LayoutMutations.ACTION_SELECT_ALL -> {
                finalizePendingWord()
                ich.performContextMenuAction(android.R.id.selectAll)
                expectedSelectionUpdates++
                return
            }
        }
        val committed = finalizePendingWord()
        commitTracked(text)
        if (committed) lastCommitTrailing = text
        updateAutoShift()
    }

    private fun onBackspace() {
        cancelAutospace()
        ich.deleteBeforeCursor(1)
        expectedSelectionUpdates++
        // Editing inside a decoded word invalidates gesture tracking; the
        // remaining text becomes plain committed text, then reloads as exact
        // anchors so continued typing corrects the word instead of starting a
        // disconnected fragment.
        abandonWord()
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
    private fun reloadWordUnderCursor() {
        if (editorState.privateMode) return
        // Peck mode has no prediction to re-seed; reloading would resurrect
        // the suggestion pipeline through the backspace path.
        if (config.peckMode) return
        val comp = composer ?: return
        val g = currentGeometry ?: return
        val after = ich.textAfterCursor(1)
        if (after != null && after.isNotEmpty() && after[0].isLetter()) return
        val before = ich.textBeforeCursor(KineticaConstants.MAX_WORD_LEN + 1) ?: return
        var start = before.length
        while (start > 0 && (before[start - 1].isLetter() || before[start - 1] == '\'')) start--
        val fragment = before.subSequence(start, before.length).toString()
        if (fragment.isEmpty() || fragment.length > KineticaConstants.MAX_WORD_LEN) return
        val codes = Alphabet.encode(AccentFolder.fold(fragment.lowercase())) ?: return

        val taps = ArrayList<InputToken>(codes.size)
        val base = SystemClock.uptimeMillis() - codes.size - 1
        for (i in codes.indices) {
            val code = codes[i]
            if (code == Alphabet.APOSTROPHE) continue
            if (!g.hasKey(code)) return
            taps.add(
                TapToken(
                    StreamId.LEFT, code, g.centerX(code), g.centerY(code),
                    longPress = false, tStart = base + i, tEnd = base + i + 1,
                ),
            )
        }
        if (taps.isEmpty()) return
        wordShift = when {
            fragment.length > 1 && fragment.all { it.isUpperCase() } -> ShiftState.State.CAPS_LOCK
            fragment.first().isUpperCase() -> ShiftState.State.SHIFT
            else -> ShiftState.State.NONE
        }
        tentativeLength = fragment.length
        tentativeWord = fragment
        comp.seed(taps)
    }

    private fun onEnter() {
        cancelAutospace()
        val committed = finalizePendingWord()
        if (editorState.multiline ||
            editorState.actionId == EditorInfo.IME_ACTION_NONE ||
            editorState.actionId == EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            commitTracked("\n")
            if (committed) lastCommitTrailing = "\n"
        } else {
            ich.performEditorAction(editorState.actionId)
        }
        updateAutoShift()
    }

    private fun onSuggestionPicked(word: String) {
        cancelAutospace()
        replaceTentative(word)
        commitWordInternal(word)
        commitTracked(" ")
        lastCommitTrailing = " "
        updateAutoShift()
    }

    private fun onCorrectionPicked(replacement: String) {
        val current = lastCommitWord ?: return
        cancelAutospace()
        ich.replaceBeforeCursor(
            current.length + lastCommitTrailing.length,
            replacement + lastCommitTrailing,
        )
        expectedSelectionUpdates++
        lastCommitWord = replacement
        composer?.replaceLastCommit(replacement.lowercase())
        // The tapped word is the real final commit: it earns the weight, and
        // the replaced word hands back the count the unwanted commit earned.
        learnWord(replacement)
        if (!current.equals(replacement, ignoreCase = true)) {
            unlearnWord(current)
        }
    }

    // ---------------------------------------------------------- word state

    /**
     * Called before any delimiter. Applies tap autocorrect when the literal
     * word is unknown and the best candidate is geometrically confident.
     * Returns true when a pending word was committed.
     */
    private fun finalizePendingWord(): Boolean {
        val comp = composer ?: run {
            abandonWord()
            return false
        }
        if (!comp.hasPendingWord) return false

        var finalWord = tentativeWord
        val p = predictor
        val threshold = config.autocorrectConfidence
        if (p != null && !comp.hasSwipeToken() && lastLiteral.isNotEmpty() &&
            !editorState.privateMode
        ) {
            val target = if (threshold != null) {
                p.autocorrectTarget(lastLiteral, lastCandidates, threshold)
            } else {
                null
            }
            if (target != null) {
                val display = displayWord(target.word)
                replaceTentative(display)
                finalWord = display
            }
        }
        // English's lone "i". Nothing upstream can reach it: letters are
        // committed one at a time before there is a word to look at, so the
        // shift state is positional only, and autocorrect never rewrites a word
        // the dictionary already has. Applies to the tap path here and to
        // decoded words through displayWord.
        val cased = AutoCapitalization.forWord(finalWord, config.language)
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
        if (editorState.privateMode) return
        val w = word.lowercase()
        if (w.length > KineticaConstants.MAX_WORD_LEN || !WORD_RE.matches(w)) return
        countsFor(lang).compute(w) { _, v -> ((v ?: 0) + amount).coerceAtLeast(0) }
        val now = System.currentTimeMillis()
        dbExecutor.execute {
            try {
                KineticaDb.get(this).userWords().upsertAdd(w, lang, amount, now)
            } catch (e: RuntimeException) {
                Log.w(TAG, "learn failed for $w", e)
            }
        }
    }

    /** True when the ?123-chord letter is the designated language cycle. */
    private fun isLangCycleChord(letterCode: Int): Boolean =
        letterCode == config.langCycleKeyCode && config.enabledLanguages.size > 1

    /** True when the ?123-chord letter is the designated peck-mode toggle. */
    private fun isPeckChord(letterCode: Int): Boolean =
        letterCode == config.peckChordKeyCode

    /** ?123-chord peck toggle: the pref listener applies the state change. */
    private fun togglePeckMode() {
        vibrateForKeyPress()
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit().putBoolean(Prefs.PECK_MODE, !config.peckMode).apply()
    }

    /** ?123-chord language switch: next enabled language in canonical order. */
    private fun cycleLanguage() {
        val langs = config.enabledLanguages
        if (langs.size < 2) return
        val next = langs[(langs.indexOf(config.language) + 1).mod(langs.size)]
        vibrateForKeyPress()
        // The preference listener does the rest: layout swap now, predictions
        // when the new dictionary finishes parsing.
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit().putString(Prefs.LANGUAGE, next).apply()
    }

    /**
     * The dictionary a word on offer came from, defaulting to the active
     * language for anything not in the current candidate list (a typed
     * literal, a correction option that was never a candidate).
     */
    private fun languageOf(word: String): String {
        val w = word.lowercase()
        return candidateLanguages[w] ?: correctionLanguages[w] ?: config.language
    }

    /** Live count map backing [lang]'s predictor; the active one by default. */
    private fun countsFor(lang: String): ConcurrentHashMap<String, Int> =
        if (lang == secondaryLanguage && lang != config.language) secondaryCounts
        else personalCounts

    /** Inverse of [learnWord] for commits the user explicitly took back. */
    private fun unlearnWord(word: String) {
        if (editorState.privateMode) return
        val w = word.lowercase()
        if (w.length > KineticaConstants.MAX_WORD_LEN || !WORD_RE.matches(w)) return
        val lang = languageOf(word)
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
        // Correction options: the committed word first, then the remaining
        // ranked candidates, then the literal tap string (the way back from a
        // wrong autocorrect) - all as full-width tappable zones.
        val options = ArrayList<String>(KineticaConstants.TOP_K + 1)
        options.add(word)
        for (c in lastCandidates) {
            val d = displayWord(c.word)
            if (!options.contains(d)) options.add(d)
        }
        if (lastLiteral.isNotEmpty()) {
            val d = displayWord(lastLiteral)
            if (!options.contains(d)) options.add(d)
        }
        val lang = languageOf(word)
        composer?.commitWord(word.lowercase())
        tentativeLength = 0
        tentativeWord = ""
        lastCandidates = emptyList()
        // The strip outlives the candidate list, so provenance is snapshotted
        // rather than cleared: a correction pick is a real commit.
        correctionLanguages = candidateLanguages
        candidateLanguages = emptyMap()
        lastLiteral = ""
        suggestionBar?.clearSuggestions()
        if (word.isNotEmpty() && !editorState.privateMode) {
            // Every commit - top prediction, tapped correction, or manual
            // typing - is one unit of personal evidence, and it goes to the
            // dictionary the word actually came from. Before candidates
            // carried provenance a word from the other language was simply not
            // learned at all: the swap handed over a whole list with no
            // per-word provenance, so the only safe rule was to skip (Italian
            // "imposte"/"sonore" were caught entering the es dictionary). With
            // provenance the guard can be exact instead of conservative.
            //
            // The one commit that still learns nothing is a swipe whose
            // full-buffer decode produced no auto-committable word: what is on
            // screen is then a stale partial decode, not what the gesture
            // produced.
            if (!swipeDecodeEmpty) learnWord(word, lang = lang)
            lastCommitWord = word
            lastCommitTrailing = ""
            suggestionBar?.showCorrection(
                options.take(KineticaConstants.TOP_K).map { barSuggestion(it) },
                selected = 0,
            )
        }
        swipeDecodeEmpty = false
    }

    private fun abandonWord() {
        composer?.clear()
        tentativeLength = 0
        tentativeWord = ""
        lastCandidates = emptyList()
        candidateLanguages = emptyMap()
        lastLiteral = ""
        swipeDecodeEmpty = false
        suggestionBar?.clearSuggestions()
        clearCorrection()
    }

    private fun clearCorrection() {
        if (lastCommitWord != null) {
            lastCommitWord = null
            lastCommitTrailing = ""
            suggestionBar?.clearCorrection()
        }
    }

    private fun replaceTentative(word: String) {
        ich.replaceBeforeCursor(tentativeLength, word)
        expectedSelectionUpdates++
        tentativeLength = word.length
        tentativeWord = word
    }

    private fun commitTracked(text: String) {
        ich.commitText(text)
        expectedSelectionUpdates++
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
        return AutoCapitalization.forWord(shifted, config.language)
    }

    private fun updateAutoShift() {
        if (!editorState.capSentences) return
        val info = currentInputEditorInfo ?: return
        val caps = ich.cursorCapsMode(info.inputType) != 0
        shift.autoShift(caps)
        keyboardView?.setShiftUppercase(shift.isShifted)
    }

    private companion object {
        const val TAG = "KineticaIME"
        const val SUGGESTION_BAR_DP = 44f
        const val MIN_KEYBOARD_DP = 180f
        const val USER_DICT_LIMIT = 5000
        // Backspace slide: how much text to fetch for word-span staging.
        const val STAGE_FETCH_CHARS = 256
        // Any-letter (accented Italian included) with internal apostrophes.
        val WORD_RE = Regex("^\\p{L}+(?:'\\p{L}+)*$")
    }
}

/**
 * Composition-mode zone list: the ranked candidates, then the all-tap literal
 * as the LAST zone when it is not already among them - the escape hatch that
 * lets an out-of-dictionary word be committed verbatim with one tap. Kept as
 * a pure top-level function so the JVM suite can lock the contract
 * (CompletionTest); callers pass an empty literal for swipe-bearing buffers.
 */
internal fun suggestionZoneWords(candidates: List<String>, literal: String): List<String> =
    if (literal.isEmpty() || candidates.contains(literal)) candidates
    else candidates + literal
