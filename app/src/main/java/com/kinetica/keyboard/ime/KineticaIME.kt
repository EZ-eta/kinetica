package com.kinetica.keyboard.ime

import android.content.Intent
import android.content.SharedPreferences
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
import androidx.core.view.WindowInsetsControllerCompat
import androidx.preference.PreferenceManager
import com.kinetica.keyboard.data.DictionaryStore
import com.kinetica.keyboard.data.KineticaDb
import com.kinetica.keyboard.data.UserWord
import com.kinetica.keyboard.engine.AccentFolder
import com.kinetica.keyboard.engine.DecodeTrace
import com.kinetica.keyboard.engine.Alphabet
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
import com.kinetica.keyboard.keys.EditorAction
import com.kinetica.keyboard.keys.EdgeSwipeBindings
import com.kinetica.keyboard.keys.ShiftState
import com.kinetica.keyboard.keys.StandaloneLetters
import com.kinetica.keyboard.layout.Key
import com.kinetica.keyboard.layout.KeyType
import com.kinetica.keyboard.layout.KeyboardLayout
import com.kinetica.keyboard.layout.LayoutLoader
import com.kinetica.keyboard.layout.LayoutMutations
import com.kinetica.keyboard.settings.KeyboardConfig
import com.kinetica.keyboard.settings.KeyboardHeights
import com.kinetica.keyboard.settings.Prefs
import com.kinetica.keyboard.settings.SettingsActivity
import com.kinetica.keyboard.ui.EmojiPickerView
import com.kinetica.keyboard.ui.EmojiRecents
import com.kinetica.keyboard.ui.InputContainerView
import com.kinetica.keyboard.ui.Hsv
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
    // The word reloadWordUnderCursor seeded back from the editor, if any. Read
    // once at commit to keep a re-commit of unchanged text from being learned
    // twice; see learnsOnCommit.
    private var reloadedWord: String? = null

    // The editor's selection, normalized so start <= end; equal means a plain
    // cursor. Insertions need none of this - commitText replaces a selection by
    // itself, and this app never sets a composing region for it to prefer - but
    // deleteSurroundingText is specified relative to the selection boundaries and
    // leaves the selection standing, so backspace over selected text used to
    // delete a character BESIDE it and leave the selection alone.
    private var selStart = 0
    private var selEnd = 0
    // Set when the latest decode of a swipe-bearing word returned no candidates:
    // the visible tentative is then a stale earlier partial decode that must not
    // be autospaced or learned.
    private var swipeDecodeEmpty = false

    // Correction strip: the last committed word and what followed it.
    private var lastCommitWord: String? = null
    private var lastCommitTrailing = ""

    // True while the space directly before the cursor is one autospace put there,
    // not one the user typed. Only an automatic space is taken back by punctuation:
    // a deliberate space before a dash is the user's own and stays.
    private var autospaceInserted = false

    // A buffer whose decode came back empty is closed after a pause instead of
    // being left open. See staleBufferRunnable.
    private var stalePending = false
    private val staleBufferRunnable = Runnable {
        stalePending = false
        abandonWord()
    }

    // True while the space before the cursor is an autospace that followed a TAPPED
    // word. Only that kind is provisional: a swipe's space was earned by a finished
    // gesture, so a letter after it starts a new word rather than continuing the old one.
    private var autospaceFromTaps = false
    private var autospaceAt = 0L

    // True while the composer holds a word that was RELOADED from the editor and has
    // received no token since. A reload is not a finished word: it is a word the user
    // parked a cursor in, or one a delete laid bare, and nothing about it says the user
    // is done typing. WordComposer.seed decodes, so that decode reaches onCandidates
    // indistinguishable from a decode caused by a thumb - which is how a reopened word
    // came to arm the timer and put a second space in. KNOWN_ISSUES item 46.
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
            // Re-read when the timer fires rather than trusted from scheduling
            // time: this is the site that actually inserts the space, and the
            // text can have moved under it in the meantime.
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
            if (finalizePendingWord()) {
                commitTracked(" ")
                lastCommitTrailing = " "
                autospaceInserted = true
                // Recorded after commitTracked, which clears the flag it sets.
                autospaceFromTaps = taps
                autospaceAt = SystemClock.uptimeMillis()
                DecodeTrace.log { "  autospace fire" }
                updateAutoShift()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Decode tracing, and only the developer build has any. The resume-after-
        // interruption bug class is a real two-thumb gesture the JVM suite cannot
        // reproduce, so a capture of the actual token buffer and split is the only
        // evidence that exists for it. Which build installs a sink, and where the
        // lines go, is TraceRecorder's business: the release source set has a stub
        // that installs nothing.
        TraceRecorder.install(this)
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
            } else if (config.keyArrangement != previous.keyArrangement ||
                config.emojiKey != previous.emojiKey ||
                config.numberPriority != previous.numberPriority ||
                config.plainLetterAlternates != previous.plainLetterAlternates ||
                config.commaMode != previous.commaMode ||
                config.commaCustom != previous.commaCustom ||
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
                // Words the user has blocked never reach the trie, so they
                // cannot be decoded, completed or suggested.
                val blocked = blockedWords(lang)
                val dict = openWordlist(lang).bufferedReader().use {
                    DictionaryLoader.load(it, userWords, blocked)
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
                                blockedWords(other),
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

    /**
     * Blocked spellings for [lang], lower-cased to match the loader's test. An
     * unavailable table is an empty block list rather than a failed load: the
     * keyboard has to come up either way.
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
        // First in the chain: every mutation below matches keys by output or
        // by id, so they must see the letters where the user will.
        l = LayoutMutations.withLetterArrangement(l, config.keyArrangement)
        // Enter's held/slide-up alternate popup is always on; the
        // symbols are settings-configurable (first is the primary).
        l = LayoutMutations.withEnterAlternates(l, config.enterAlternates)
        // Before the emoji and comma-role mutations, so a user list still gets
        // the emoji entry prepended and still survives a repurposed comma.
        l = LayoutMutations.withPunctuationAlternates(
            l, config.periodAlternates, config.commaAlternates,
        )
        // Optional apostrophe key in the home-row right padding.
        if (config.apostropheKey) l = LayoutMutations.withApostropheKey(l)
        if (config.emojiKey) l = LayoutMutations.withEmojiOnComma(l)
        // Before the reorder: with the accents gone there is nothing left for
        // number-priority to move, so the two settings compose instead of
        // fighting over the same list.
        if (config.plainLetterAlternates) l = LayoutMutations.withoutForeignAlternates(l)
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
            barHeightPx = dpToPx(config.suggestionBarDp.toFloat()),
            keyboardHeightPx = keyboardHeightPx(),
            minKeyboardPx = minKeyboardPx(),
            maxKeyboardPx = maxKeyboardPx(),
            onHeightCommitted = { px -> persistHeightPct(px) },
            handleHeightPx = dpToPx(config.dragHandleDp.toFloat()),
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
            onEmoji = { recordEmojiUse(it); commitTracked(it) },
            onBackspace = { onBackspace() },
            onClose = { closeEmojiPicker() },
        ).also { emojiPicker = it }
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

    // Bounds arithmetic lives in KeyboardHeights, which is pure and therefore
    // testable: an inverted min..max range here once made coerceIn throw and took
    // the whole app process down with it.
    private fun minKeyboardPx(): Int = KeyboardHeights.minPx(
        resources.displayMetrics.heightPixels, resources.displayMetrics.density,
    )

    private fun maxKeyboardPx(): Int =
        KeyboardHeights.maxPx(resources.displayMetrics.heightPixels)

    private fun persistHeightPct(px: Int) {
        val pct = KeyboardHeights.pctFor(px, resources.displayMetrics.heightPixels)
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
        kv.layoutMode = config.layoutMode
        kv.autospaceDot = config.autospace
        kv.backspaceCharSlide = config.backspaceCharSlide
        kv.spacebarStepDp = config.spacebarStepDp
        kv.spacebarWordSlide = config.spacebarWordSlide
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
        suggestionBar?.retypeButton = config.retypeButton
        containerView?.setBarHeight(dpToPx(config.suggestionBarDp.toFloat()))
        containerView?.setHandleHeight(dpToPx(config.dragHandleDp.toFloat()))
        val targetH = keyboardHeightPx()
        val lp = kv.layoutParams
        if (lp != null && lp.height != targetH) {
            lp.height = targetH
            kv.requestLayout()
        }
    }

    /**
     * Paints the system navigation bar to match the keyboard.
     *
     * Nothing here used to touch the IME's window, so the strip below the keyboard
     * kept the platform default - a black band under a themed keyboard, which is
     * what it looked like.
     *
     * Icon contrast comes from the background's own luminance rather than a new
     * flag, so it stays right for a custom hue as well as for the two bundled
     * palettes. navigationBarColor is deprecated once a target of 35 enforces
     * edge-to-edge; at targetSdk 34 it still applies, and raising the target is a
     * reproducible-build change that has to be measured on its own.
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

    private fun keyboardHeightPx(): Int = KeyboardHeights.targetPx(
        resources.displayMetrics.heightPixels,
        resources.displayMetrics.density,
        config.heightPct,
    )

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
        // A field can open with text already selected, so seed from the editor
        // rather than assuming a collapsed cursor. Either offset is -1 when the
        // editor did not report one, which is no selection, not a huge one.
        val s = attribute?.initialSelStart ?: -1
        val e = attribute?.initialSelEnd ?: -1
        setSelectionCache(s, e)
        updateAutoShift()
        reloadChords()
        reloadEmojiUses()
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
        // Recorded on every update, including the ones this service caused: after
        // its own edit the cursor is where the editor says it is, and the delete
        // paths read these offsets.
        setSelectionCache(newSelStart, newSelEnd)
        if (expectedSelectionUpdates > 0) {
            expectedSelectionUpdates--
            return
        }
        // The user moved the cursor themselves: the pending word is no longer under
        // the cursor, abandon it.
        if (tentativeLength > 0 || composer?.hasPendingWord == true) {
            abandonWord()
        }
        // ...and if they parked it at the end of a word, reopen that one instead, so
        // it can be extended and so the bar offers alternatives for it. Until now this
        // only happened when the space after a word was deleted; a cursor placed there
        // by hand abandoned and left nothing. reloadWordUnderCursor keeps its own
        // guards - it refuses when a letter follows the cursor, so a mid-word cursor
        // still abandons - and it re-seeds the word as tap anchors, which is what makes
        // the bar fill: WordComposer.seed decodes, so the alternatives arrive through
        // the ordinary candidates path with nothing new plumbed.
        //
        // The word comes back as letters, not as the gesture that produced it, so those
        // alternatives are spelling neighbours of what is written rather than the
        // original decode's list. That is a second, different level of correction, not
        // the same one.
        if (reopensWordUnderCursor(selectionLength(), newSelStart, newSelEnd)) {
            reloadWordUnderCursor()
        }
        // The correction strip names the text immediately before the cursor, and
        // with a selection up it is not that. Left standing it stayed tappable,
        // and onCorrectionPicked's replaceBeforeCursor would then delete text
        // BESIDE the selection and have commitText replace the selection too -
        // two edits, neither of them the one asked for. Narrowed to the selection
        // case on purpose: a plain cursor move already clears the strip through
        // abandonWord whenever a word was pending, and widening it here would
        // make any editor that miscounts expectedSelectionUpdates lose the strip.
        if (selectionLength() > 0) clearCorrection()
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
                //
                // Before anything is committed, because commitTracked clears the flag
                // this reads: a letter arriving straight after a tapped word's
                // autospace says the word was not over, so the space goes and the word
                // comes back. That is what makes an early space cost nothing instead of
                // costing a backspace.
                val fusedBase = wordBeforeAutospace(
                    ich.textBeforeCursor(KineticaConstants.MAX_WORD_LEN + 2) ?: "",
                )
                if (retractsAutospace(
                        fromTappedWord = autospaceFromTaps && autospaceInserted,
                        tentativeLength = tentativeLength,
                        elapsedMs = SystemClock.uptimeMillis() - autospaceAt,
                        windowMs = config.autospaceRetractMs,
                        // The word this letter would rejoin, asked of the dictionary
                        // rather than of the clock. Read from the editor rather than from
                        // lastLiteral so it agrees with what reloadWordUnderCursor will
                        // actually put back.
                        fusedIsPrefix = fuseIsLivePrefix(fusedBase, Alphabet.charOf(token.code)),
                    )
                ) {
                    // The letter that caused this: the reopened word must land before it.
                    retractAutospace(token.tStart)
                }
                if (tentativeLength == 0) wordShift = shift.state
                val ch = shift.apply(Alphabet.charOf(token.code))
                commitTracked(ch.toString())
                tentativeLength += 1
                tentativeWord += ch
                if (shift.state == ShiftState.State.SHIFT) {
                    shift.onLetterCommitted()
                    keyboardView?.setShiftUppercase(shift.isShifted)
                }
                // Cleared HERE and not at the top of this method: retractAutospace above
                // reloads the word from inside this very call, so a clear before that
                // would be undone by the reload's own set and the real token's decode
                // would stay suppressed.
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
        if (!comp.hasSwipeToken()) {
            // An all-tap word has never had a timer, because every tapped letter looks
            // exactly like the middle of a longer word. Behind its own setting it gets
            // one, and only when the letters so far spell something the dictionary
            // holds - see autospacesTappedWord for what that is worth, and for why the
            // space is retractable rather than merely well-guessed.
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
                if (!engine.hasActivePointers()) scheduleStaleTimeout()
            }
        }
    }

    /**
     * Ends a buffer that decodes to nothing, once the user has paused.
     *
     * An empty decode cancels the autospace and cannot re-arm it - the timer is only
     * scheduled when a decode returns something - so before this the word boundary
     * simply disappeared. The next gesture then appended to a dead buffer, which
     * decoded to nothing as well, and one failure sustained itself: 41% of the empty
     * decodes in the 2026-08-19 capture contain a pause over 600 ms, against 2% of the
     * working ones, and several are two attempts at the same word merged into one
     * buffer.
     *
     * Nothing is committed and nothing is learned. The stale tentative on screen is
     * exactly what [swipeDecodeEmpty] exists to keep out of the editor's history, so
     * this only clears the composer and lets the next gesture start a word.
     *
     * The pause, not the emptiness, is what ends it: a long word typed in pieces
     * decodes to nothing in between, and that is a word in progress rather than a dead
     * buffer. Working decodes have a median inter-token gap of 0 ms and a p90 of
     * 415 ms, so twice the autospace delay - 600 ms at its default - sits above the
     * continuations and below the retries, and reuses a tunable the user already has
     * rather than adding one.
     */
    private fun scheduleStaleTimeout() {
        if (editorState.privateMode || config.wordEndsOnSpace) return
        cancelStaleTimeout()
        stalePending = true
        // Follows the SWIPE delay: the buffer this ends is a swipe buffer, and the
        // inter-token reasoning above is about swipes.
        mainHandler.postDelayed(staleBufferRunnable, 2 * config.autospaceDelayMs)
    }

    private fun cancelStaleTimeout() {
        if (stalePending) {
            mainHandler.removeCallbacks(staleBufferRunnable)
            stalePending = false
        }
    }

    /**
     * Arms the automatic space, on the delay belonging to the kind of word in hand.
     *
     * The two were one value until 2026-08-29. They are split because a swipe and a tap
     * leave different silences mid-word: over three captures the intra-word gap between
     * consecutive tokens runs to p99 878 ms while swiping against 569 ms while tapping, so
     * a swiped word wants more patience before the keyboard decides it is over. Both
     * default to the same 300 ms - the tails differ, the middles barely do - and the
     * sliders exist so the difference is found on a thumb rather than from a percentile.
     */
    /**
     * Whether the whole editor token this word belongs to is itself a word - the override
     * that lets `don't` autospace although the composer only ever saw a one-letter `t`.
     *
     * Read wide enough to hold a long token plus the word's own letters. `isWord` folds
     * accents and checks spellings, so `perche` does not pass as `perché`.
     */
    /**
     * Whether [base] plus [next] could still become a word, asked of the whole run and
     * then of the tail after its last apostrophe.
     *
     * The second question is what makes an elision retractable. `dell'ann` earns a space
     * because `ann` is an entry; the `o` that follows has to take it back, and
     * `isLivePrefix("dell'anno")` answers no - not because the fusion is wrong but because
     * `it_wordlist.txt` holds no elided form at all. `anno` answers yes. The same reading
     * that let the space fire has to be available to the retraction, or the space fires
     * and never comes back.
     *
     * The whole run is still asked first and still wins, so `don't` and `it's` keep the
     * behaviour item 43 measured.
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

    private fun joinedTokenIsWord(): Boolean {
        val p = predictor ?: return false
        val before = ich.textBeforeCursor(2 * KineticaConstants.MAX_WORD_LEN) ?: return false
        val token = joinedTokenForAutospace(before)
        return token.isNotEmpty() && p.isWord(token)
    }

    private fun scheduleAutospace() {
        if (!config.autospace || editorState.privateMode || config.wordEndsOnSpace) return
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
        }

        override fun onCursorMove(direction: Int, byWord: Boolean) {
            // The resulting selection change is unexpected by design: it will
            // abandon the pending word via onUpdateSelection.
            if (byWord && moveCursorByWord(direction)) return
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
            // A chord may name an editor command instead of text; anything else
            // inserts as before.
            if (!performIfAction(expansion)) finalizeThenCommitText(expansion)
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

        override fun onRetype() {
            vibrateForKeyPress()
            // Through the shared action rather than straight to the handler: the bar
            // button and a ?123 chord are two triggers for one implementation, which is
            // what EditorAction exists for.
            performIfAction(EditorAction.RETYPE.output)
        }
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

    /**
     * Runs [text] as an editor command when it names one, and reports whether it
     * did. Shared by the configurable comma key and the chord shortcuts, which is
     * the point: the chord path used to insert "action:paste" into the document as
     * literal text because it never consulted this at all.
     *
     * The pending word is settled first so the command applies to finished
     * content rather than to a half-decoded one. A string carrying the reserved
     * prefix but naming no command is swallowed rather than typed - it is a typo
     * in a chord expansion, and inserting it is the worse of the two answers.
     *
     * RETYPE is the exception to the settling, because the pending word is exactly what it
     * is aimed at; it is dispatched before the finalize and is not a context-menu action
     * at all.
     */
    private fun performIfAction(text: String): Boolean {
        val action = EditorAction.of(text)
        if (action == null) return EditorAction.isUnknownAction(text)
        val menuId = when (action) {
            EditorAction.PASTE -> android.R.id.paste
            EditorAction.COPY -> android.R.id.copy
            EditorAction.CUT -> android.R.id.cut
            EditorAction.SELECT_ALL -> android.R.id.selectAll
            EditorAction.RETYPE -> null
        }
        if (menuId == null) {
            retypeCurrentWord()
            return true
        }
        finalizePendingWord()
        ich.performContextMenuAction(menuId)
        expectedSelectionUpdates++
        return true
    }

    /**
     * Deletes the word in progress - or, when nothing is in progress, the one just
     * committed together with whatever the keyboard put after it - and leaves the cursor
     * where it was so the word can be gestured again in place.
     *
     * Both cases are wanted and the second is the common one: the autospace commits fast,
     * so by the time a wrong word is noticed it is usually finished. `retypeSpan` decides
     * which, purely.
     *
     * When the keyboard knows of no word it does nothing rather than reading one back out
     * of the editor. Deleting text the user did not point at is the worse failure, and a
     * retype with nothing to retype is a no-op the user will simply repeat.
     */
    private fun retypeCurrentWord() {
        cancelAutospace()
        // The same guard the reload uses: with a letter after the cursor the user is parked
        // inside a word rather than at the end of one, and nothing here should guess which
        // half they meant.
        val after = ich.textAfterCursor(1)
        val midWord = after != null && after.isNotEmpty() && after[0].isLetter()
        val run = if (midWord) {
            ""
        } else {
            trailingLetterRun(ich.textBeforeCursor(KineticaConstants.MAX_WORD_LEN + 1) ?: "")
        }
        val span = retypeSpan(tentativeLength, lastCommitWord, lastCommitTrailing, run)
        DecodeTrace.log {
            "  retype span=$span src=${retypeSource(tentativeLength, lastCommitWord)}" +
                (if (midWord) " midword" else "")
        }
        if (span <= 0) {
            abandonWord()
            return
        }
        ich.deleteBeforeCursor(span)
        expectedSelectionUpdates++
        abandonWord()
        // The word is gone, so the space that followed it is not the keyboard's any more
        // and nothing is left for punctuation to eat or a letter to retract.
        forgetAutospace()
        updateAutoShift()
    }

    /**
     * Removes an automatically inserted space when [text] is punctuation that hugs
     * the word before it. No-op for a space the user typed, and no-op once anything
     * else has been committed since - the flag is cleared by every other path
     * through commitTracked.
     */
    private fun eatAutospaceBefore(text: String) {
        if (!autospaceInserted || !hugsPreviousWord(text)) return
        if (ich.textBeforeCursor(1)?.toString() != " ") return
        ich.deleteBeforeCursor(1)
        expectedSelectionUpdates++
        autospaceInserted = false
        autospaceFromTaps = false
        if (lastCommitTrailing == " ") lastCommitTrailing = ""
        DecodeTrace.log { "  autospace eat punct=$text" }
    }

    /**
     * Removes an automatic space that followed a tapped word, and reopens that word.
     *
     * The same edit [eatAutospaceBefore] makes for punctuation, with the word put back
     * afterwards: [reloadWordUnderCursor] re-seeds it from the text still in the editor,
     * which is the path a deleted space already uses. So `car`, pause, space, `pet`
     * arrives at the composer as one word rather than two.
     *
     * The word returns as tap anchors, which for a word that was tapped in the first
     * place is exactly what it was.
     */
    private fun retractAutospace(beforeTime: Long) {
        if (ich.textBeforeCursor(1)?.toString() != " ") return
        ich.deleteBeforeCursor(1)
        expectedSelectionUpdates++
        autospaceInserted = false
        autospaceFromTaps = false
        if (lastCommitTrailing == " ") lastCommitTrailing = ""
        DecodeTrace.log { "  autospace retract" }
        reloadWordUnderCursor(beforeTime)
    }

    /**
     * Forgets the automatic space this keyboard had put before the cursor, because it is
     * no longer there: a backspace or a slide has just deleted it.
     *
     * Only the two flags that DESCRIBE that space. Nothing here says anything about what
     * the user meant by deleting it - that used to be an `autospaceRefused` flag, and
     * KNOWN_ISSUES item 46 is the measurement of why it had to go: it was set on one word
     * and read by later, unrelated ones, so it silenced two spaces that were correct.
     *
     * Without this, `autospaceInserted` and `autospaceFromTaps` stayed set after the space
     * was gone. Harmless in practice, because [retractAutospace] re-reads the text before
     * it acts, but it is a flag describing something that does not exist.
     */
    private fun forgetAutospace() {
        autospaceInserted = false
        autospaceFromTaps = false
    }

    private fun finalizeThenCommitText(text: String) {
        eatAutospaceBefore(text)
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
    private var stagedDeletionLength = 0
    // Cursor offset the staged span is measured back from, captured ONCE when
    // staging starts. selStart/selEnd follow every programmatic selection made
    // below, so re-reading them mid-slide would walk this backwards a span at a
    // time. -1 means nothing is staged.
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
        // One tick per unit, because BackspaceController only reports a CHANGED
        // count - so this fires on each threshold crossing and never repeats while
        // the finger sits still. Retractions tick too: the reading that matters is
        // "the count moved", and not feeling a retraction is how a slide deletes
        // less than intended. Same idiom as the suggestion bar's reinforce steps,
        // and it honours the existing vibration setting rather than adding one.
        vibrateForKeyPress()
        if (composer?.hasPendingWord == true || tentativeLength > 0) abandonWord()

        // Snapshot the editor ONCE, when staging starts. Everything after this
        // reads the snapshot, because from the first highlight onwards the live
        // selection is one this method made: getTextBeforeCursor would then return
        // the text before that highlight and selectionLength() would report it as
        // the user's own, so the span would grow by a whole unit per crossing and
        // a retraction would grow it too.
        if (stagedDeletionLength == 0) {
            stageAnchor = selEnd
            // A selection the USER had when the slide began is the first staged
            // unit (DeleteSpan.staged); its text is captured for the chip while it
            // is still readable.
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
        // Highlight what would go, so it is visible in the text itself and not only
        // as a chip - the chip stays, because the text may have scrolled out of
        // view or be a password field. Presentation only: the span deleted on lift
        // is the same number of characters either way.
        if (stageAnchor >= span) {
            ich.setSelection(stageAnchor - span, stageAnchor)
            expectedSelectionUpdates++
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
            expectedSelectionUpdates++
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
        // Do NOT restore the cursor here: the span is about to go, and collapsing
        // the selection first would only make the delete flicker.
        clearStagedDeletion(restoreCursor = false)
        if (span <= 0) return
        // The span is highlighted by now, so collapse to the anchor and delete
        // back from it. Falls back to the relative call when there is no anchor,
        // which is the path a staged span without a captured cursor would take.
        // Whether the span about to go contains the keyboard's own space, decided BEFORE
        // the delete for the same reason onBackspace does it there: afterwards the evidence
        // is gone.
        val deletedAutospace = autospaceInserted && staged.endsWith(" ")
        if (anchor >= span) ich.deleteEndingAt(anchor, span) else ich.deleteBeforeCursor(span)
        expectedSelectionUpdates++
        abandonWord()
        if (deletedAutospace) {
            forgetAutospace()
            DecodeTrace.log { "  autospace refuse src=slide" }
        }
        updateAutoShift()
    }

    /**
     * Moves the cursor one word in [direction], returning false when it could not be done
     * from what the editor will hand over - the caller then falls back to the arrow key.
     *
     * Reuses the backspace slide's own walk (`DeleteSpan.words` and its forward mirror), so
     * "one word" means the same thing on both gestures: punctuation is part of a word and
     * the whitespace comes with it. That is what makes `word,` one step rather than two.
     *
     * The read is bounded, so a word longer than the window - or a cursor deep inside a
     * paragraph of no whitespace - simply falls back rather than jumping somewhere wrong.
     * A selection is collapsed to the edge the movement heads for, which is the standard
     * editing contract and the same choice the backspace slide makes.
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
        cancelAutospace()
        val committed = finalizePendingWord()
        commitTracked(" ")
        if (committed) lastCommitTrailing = " "
        updateAutoShift()
    }

    private fun onPunctuation(text: String) {
        cancelAutospace()
        // Reserved action outputs never reach the editor as text.
        if (performIfAction(text)) return
        // "Hi" + autospace + "!" should read "Hi!", not "Hi !".
        eatAutospaceBefore(text)
        val committed = finalizePendingWord()
        commitTracked(text)
        if (committed) lastCommitTrailing = text
        updateAutoShift()
    }

    private fun onBackspace() {
        cancelAutospace()
        // Whether this delete is aimed at the keyboard's own space, decided BEFORE the
        // deletion because afterwards the evidence is gone. Deleting a letter is an
        // ordinary correction and says nothing about the space; deleting the space itself
        // means the flags describing it are stale. One query tells them apart.
        val deletedAutospace = autospaceInserted && ich.textBeforeCursor(1)?.toString() == " "
        // Selected text is what backspace deletes, and only the whole of it: the
        // standard editing contract, and the one case where deleting a single
        // character would destroy text the user did not point at.
        val selected = selectionLength()
        if (selected > 0) ich.deleteEndingAt(selEnd, selected) else ich.deleteBeforeCursor(1)
        expectedSelectionUpdates++
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
        val codes = Alphabet.encode(AccentFolder.fold(fragment.lowercase())) ?: return

        val taps = ArrayList<InputToken>(codes.size)
        val base = reloadAnchorBase(beforeTime, codes.size)
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
        reloadedWord = fragment
        // Whatever the timer was armed for, it is not what the composer holds now.
        cancelAutospace()
        comp.seed(taps)
        // After seed, not before: the decode runs on decodeExecutor and its callback
        // cannot reach onCandidates until this method returns, so the flag is up before
        // either arm site reads it.
        seededWithoutTokens = true
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
        // A picked word's space is the keyboard's own, exactly like the idle
        // autospace, so punctuation takes it back the same way. commitTracked
        // clears the flag, so this has to come after it.
        autospaceInserted = true
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

    /**
     * One more pick recorded for [emoji]. Same shape as [learnWord]: the resident
     * map is updated synchronously so the panel is right the next time it opens,
     * and Room is written on [dbExecutor].
     */
    private fun recordEmojiUse(emoji: String) {
        // A password field must not populate a visible list, exactly as it must
        // not teach the dictionary.
        if (editorState.privateMode || emoji.isEmpty()) return
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
            if (!swipeDecodeEmpty && learnsOnCommit(word, reloadedWord)) {
                learnWord(word, lang = lang)
            }
            lastCommitWord = word
            lastCommitTrailing = ""
            suggestionBar?.showCorrection(
                options.take(KineticaConstants.TOP_K).map { barSuggestion(it) },
                selected = 0,
            )
        }
        swipeDecodeEmpty = false
        reloadedWord = null
    }

    private fun abandonWord() {
        composer?.clear()
        reloadedWord = null
        seededWithoutTokens = false
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
        // Cleared here so the flags can only ever describe the space written last.
        // The two callers that write an automatic space - the autospace runnable
        // and a suggestion-bar pick - set them again immediately after.
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
        return AutoCapitalization.forWord(shifted, config.language)
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
        // Decided from the text rather than asked of the editor - see
        // startsNewSentence. A null read is a connection that cannot answer, not
        // an empty field, so the shift state is left as it stands.
        val before = ich.textBeforeCursor(CAPS_LOOKBACK_CHARS) ?: return
        shift.autoShift(startsNewSentence(before))
        keyboardView?.setShiftUppercase(shift.isShifted)
    }

    private companion object {
        const val TAG = "KineticaIME"
        const val USER_DICT_LIMIT = 5000
        // Backspace slide: how much text to fetch for word-span staging.
        const val STAGE_FETCH_CHARS = 256
        // Spacebar word slide: how far to read for ONE word boundary. Smaller than the
        // staging window because it is one step rather than a span, and because the walk
        // falls back to the arrow key when the window holds no boundary at all.
        const val CURSOR_WORD_WINDOW = 64
        // Sentence caps: enough tail to skip closing punctuation and walk one
        // word back for the abbreviation check.
        const val CAPS_LOOKBACK_CHARS = 48
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
/**
 * Whether committing [word] should add a unit of personal weight, given the word
 * [reloadedFrom] that was seeded back into the composer from text already in the
 * editor (null when the word was typed from nothing).
 *
 * The case this exists for: commit "hello", then delete only the trailing space.
 * That backspace reloads "hello" as tap anchors so continued typing corrects it,
 * and the next delimiter commits it a second time - so one authored word earned
 * two units. Personal weight is the lever the merge floor and the fade both had to
 * be tuned against, and silent inflation is exactly the self-reinforcing drift
 * those exist to prevent.
 *
 * An EDIT still learns. Backspacing into "hell" and typing "hello" seeds "hell"
 * and commits "hello", which differs, so it counts - the rule suppresses the
 * re-commit of an unchanged word and nothing else. Pure and top-level for the same
 * reason as [suggestionZoneWords]: the learn path itself has no JVM reach.
 */
/**
 * Whether [text] is punctuation that sits directly against the word before it, so
 * an automatically inserted space in front of it should go.
 *
 * Sentence and clause punctuation and closing brackets hug: "Hi" + "!" is "Hi!".
 * An opening bracket, a dash, a digit or a letter do not - "one - two" and
 * "a (b)" both want the space that is already there. Kept pure and listed
 * explicitly rather than derived from a character class, because "is this
 * punctuation" and "does it hug" are different questions: an em dash is
 * punctuation and takes a space, an apostrophe hugs but never arrives here.
 */
/**
 * True when a word typed entirely by tapping has earned an automatic space.
 *
 * A swipe-built word autospaces because the gesture is a complete statement of intent:
 * the finger lifted, the decode landed, the word is over. Tapping says nothing of the
 * kind - every letter looks exactly like the middle of a longer word - which is why an
 * all-tap word has never had a timer at all.
 *
 * [literalIsWord] is the only signal that carries any weight, and it is measured to be
 * necessary and far from sufficient. Over every capture, of the tap states whose letters
 * spell a real word, 57% were mid-word and the typing continued. The delay filters most
 * of that (the median gap while continuing a word is 168 ms) and roughly one fire in
 * five is still premature at the default 300 ms - a rate that barely improves at 800 ms.
 *
 * Two further gates were measured and rejected: a frequency floor removes good fires as
 * fast as bad ones, and requiring the top candidate to equal the literal removes 9% of
 * misfires while costing 6% of correct ones. The wordlist is OpenSubtitles-derived, so
 * `ke`, `wh` and `whe` are all real entries and no dictionary test can do better.
 *
 * What makes the remaining error affordable is not a better guess but a cheaper one -
 * see [retractsAutospace], which takes the space back when the next thing typed turns
 * out to be another letter of the same word. That mechanism was unobserved for three
 * captures and fired for the first time in the prose one, which is what unblocked the
 * length rule below.
 *
 * [literalIsStandaloneLetter] is that rule's replacement for a single letter, and the
 * length gate is why it has to be asked separately: `a` and `I` are words and were refused
 * outright, which cost a manual space on **12% of the words** in the first prose capture
 * (16 of 130). It cannot be a dictionary question, because every letter a-z is an entry in
 * every bundled list - `en` holds `l` at 126 518 and `t` at 72 881 - so
 * [StandaloneLetters] carries a curated per-language set instead.
 *
 * **What makes one letter affordable is the delay, not the list.** One letter is weaker
 * evidence than a word, so it waits longer: see [singleLetterDelayMs]. Swept over three
 * captures, 22 one-letter words against 28 word-starts whose first letter is also a word,
 * the plateau is 275-350 ms and 300 spaces 17 of the 22 at three premature fires - of which
 * one is the `dell'anno` case the joined branch already excludes and two are aborted
 * garbage buffers. **Zero premature fires on a real finished word**, and all three would be
 * retracted by the next letter anyway. KNOWN_ISSUES item 48.
 *
 * [carriesNoToken] is the other half of that, and it is what makes one delete enough.
 * Deleting the space reopens the word through [KineticaIME.reloadWordUnderCursor], which
 * re-seeds it as tap anchors, and that decode used to be indistinguishable here from a
 * freshly tapped one - so the timer armed again and put the space straight back. The
 * developer met that as an unwinnable fight against `name@mail.com`. What tells the two
 * apart is that the reload has no gesture behind it.
 *
 * **There used to be a `refused` gate beside it and it is gone; do not put it back.** It
 * recorded that the user had deleted an automatic space and held that until the word
 * finalized, which sounds like the same thing and is not: it was set on one word and read
 * by later, unrelated ones. Measured on the 1.0.5 capture it silenced two correct spaces -
 * `car`, delete, `pet` gets no space for the finished `carpet`, and a word freshly swiped
 * after a slide gets none either. Both were the flag outliving its word. `carriesNoToken`
 * is scoped to a single decode, which is the scope the question actually has.
 * KNOWN_ISSUES item 46.
 *
 * [addressField] is the same report's root cause rather than its symptom, and it is the
 * gate that actually won `name@mail.com`: in an email or URL field no automatic space is
 * ever wanted, so none is armed and the delete is never needed. All the gates are read
 * here rather than at the call sites so the whole decision stays in one testable place.
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
    // A word that continues an earlier token normally takes no space - but if the WHOLE
    // token is itself a word, it is finished and it does. `don't` is a word; `automaticop`
    // is not. That is the route by which an English contraction autospaces, because the
    // tail after the apostrophe is usually too short to pass the length rule on its own -
    // see joinedTokenForAutospace.
    //
    // When the joiner is an APOSTROPHE and the whole token is not a word, the piece after
    // it is judged on its own instead. Italian elision is why: `dell'anno`, `d'accordo`,
    // `un'ora`, `nell'immagine` are not in `it_wordlist.txt` and no dictionary test can
    // find them, so the joined lookup answers no for every one of them and the space was
    // never given. `anno` and `accordo` are words, and they are the part the user is
    // actually finishing. Restricted to the apostrophe on purpose: `log-12.com`,
    // `example.com`, `name@mail.com` and `notes_14.log` join on `.`, `@` and `_`
    // and must keep refusing, which is the behaviour the report asked to keep.
    //
    // Priced, not assumed. The cost is that `'quoted text'` gains a space after the word,
    // and that a PAUSE inside an elision can still split it - `l'al` and `dell'ann` are
    // both real dictionary entries. The developer chose that trade over the elision
    // failing, and retractsAutospace is what makes the second half cheap: the next letter
    // takes the space back, asking the dictionary about the tail after the apostrophe.
    if (joinedToWhatPrecedes) {
        joinedTokenIsWord || (joinedByApostrophe && literal.length >= 2 && literalIsWord)
    } else if (literal.length == 1) {
        // One letter, and the ONLY question is whether it is a word in this language -
        // see StandaloneLetters for why the dictionary cannot answer that. Deliberately
        // after the joined branch, which is what keeps the `a` of `dell'anno` out: it is
        // preceded by an apostrophe, so it never reaches here.
        literalIsStandaloneLetter
    } else {
        literal.length >= 2 && literalIsWord
    }

/**
 * True when the character joining this word to what precedes it is an apostrophe.
 *
 * Asked separately from [joinsPrecedingToken] because the two questions are different: one
 * is "is this token finished here", the other is "which joiner is it". Only the apostrophe
 * lets [autospacesTappedWord] fall back to judging the piece after it, and only because
 * elision is a word boundary that the dictionary cannot see. Both quote forms count - the
 * symbols layer's apostrophe key offers the typographic one as an alternate.
 *
 * [before] is the text immediately preceding the word, so its LAST character is the one in
 * question.
 */
internal fun joinedByApostrophe(before: CharSequence): Boolean =
    before.isNotEmpty() && (before[before.length - 1] == '\'' || before[before.length - 1] == '\u2019')

/**
 * The letters after the last apostrophe in [word], or "" when there is none.
 *
 * The retraction's second question. `dell'ann` + `o` has to retract, and asking
 * `isLivePrefix("dell'anno")` answers no because the elided form is not in the trie at
 * all - so the tail is asked instead, where `anno` is an ordinary Italian word. Kept
 * separate from [wordBeforeAutospace], which must keep returning the WHOLE run: narrowing
 * that one would have `it's` + a following `a` fuse into `it'sa`.
 */
internal fun tailAfterLastApostrophe(word: String): String {
    val i = maxOf(word.lastIndexOf('\''), word.lastIndexOf('\u2019'))
    return if (i < 0) "" else word.substring(i + 1)
}

/**
 * True when a word containing at least one swipe token has earned an automatic space.
 *
 * The swipe path had no predicate of its own - it was two inline conditions at two call
 * sites - and that is how a gate came to be missing from one of them. A swipe earns its
 * space because the gesture is a complete statement of intent: the finger lifted and the
 * decode landed. That leaves only the two conditions the intent cannot speak to.
 *
 * **A `refused` gate stood here for one release and was wrong; do not put it back.** It
 * read as "the user deleted an automatic space, so do not give another one", which on this
 * path meant a word freshly swiped after a slide got no space at all - the slide abandons
 * the word without reloading, so everything after it is a NEW word and its space is earned.
 * The `refused=true` wakes that looked like a re-fire in the 1.0.5k capture were correct
 * fires against a flag that had outlived its own word. KNOWN_ISSUES item 46.
 *
 * [carriesNoToken] is a belt here rather than the fix. A reload seeds tap anchors and
 * `WordComposer.seed` REPLACES the token list, so a reloaded word carries no swipe and
 * this path cannot currently be reached with it set - the whole of item 46 lands on
 * [autospacesTappedWord]. It is read here so both paths ask the same questions in the same
 * place, and so that a reload which ever seeds a gesture is already right.
 *
 * [addressField] is unchanged: no automatic space is ever wanted in an email or URL field.
 */
internal fun autospacesSwipedWord(
    hasSwipeToken: Boolean,
    addressField: Boolean,
    carriesNoToken: Boolean,
): Boolean = hasSwipeToken && !addressField && !carriesNoToken

/**
 * True when the character immediately before the word being typed is one that joins
 * it to what precedes, so the word MAY be a fragment of a longer token.
 *
 * On its own this only says the token is not finished HERE. Whether it is finished at
 * all is [joinedTokenForAutospace]'s question, and `autospacesTappedWord` asks both:
 * a joined token that is a word still earns its space.
 *
 * Reported by accident: a log file saved as `notes 14.log`, where only the
 * `14` was typed. `_` finalizes the word, so `trace` became a fresh word, it is in
 * the dictionary, and the only field guard is [EditorState.addressField] - which
 * covers email and URL fields but not a rename box. The timer then fired during the
 * pause before the digits.
 *
 * Deciding it from the text rather than from another field type is what makes it
 * general: the same read refuses `e-mail`, `don't`, `example.com` and `a/b` without
 * knowing anything about the editor.
 *
 * [before] is the text immediately preceding the word, so its LAST character is the
 * one in question. Whitespace allows the space, and so does opening punctuation -
 * `"hello world"` still spaces correctly, which is why this is not simply
 * "anything that is not whitespace". An empty read is the start of the field and
 * allows it.
 */
/**
 * The whole editor token the word being typed belongs to - letters and joiners together -
 * or "" when there is nothing word-shaped there.
 *
 * The apostrophe is why this exists. A tapped apostrophe is not a letter to the composer:
 * `Key.isLetter` requires `a`..`z`, so the key never enters the gesture engine and a tap on
 * it routes to `onPunctuation`, which FINALIZES the pending word. `d'accordo` therefore
 * reaches the composer as `d` and then `accordo`, and `don't` as `don` and then a one-letter
 * `t` that the length rule blocks outright. So no word containing an apostrophe has ever
 * autospaced, in any language.
 *
 * Reading the token back out of the editor is what makes the question answerable without
 * touching the geometry: `don't`, `it's` and `can't` are in `en_wordlist` with large counts,
 * so they are words and they space. `log-12.com` and `example.com` are not - `Alphabet.encode`
 * rejects digits outright - so they stay refused, which is the behaviour the report asked to
 * keep.
 *
 * **Italian elision is not reached by this lookup, and the reason is data, not logic.**
 * `it_wordlist.txt` holds ten apostrophe entries in total and every one is corpus junk
 * (`e'o`, `e'a`, `n'roll`); `d'accordo` and `l'altro` are simply absent, so no dictionary
 * test can find them here. [autospacesTappedWord] therefore does not rely on this lookup
 * for them: when the joiner is an apostrophe and the joined token is not a word, it judges
 * the piece after the apostrophe on its own. Generating the elided forms is still worth
 * doing - it is what would let an elided word be DECODED as one gesture - but the space no
 * longer waits on it.
 *
 * [before] is the text before the cursor with the word's own letters still on the end.
 */
internal fun joinedTokenForAutospace(before: CharSequence): String {
    var start = before.length
    while (start > 0) {
        val c = before[start - 1]
        // Digits are part of the token, not a break in it - `log-12.com` is one thing.
        // They also guarantee the lookup fails, since Alphabet.encode refuses them, which
        // is exactly the answer wanted for a token like that.
        if (c.isLetterOrDigit() || c in WORD_JOINERS) start-- else break
    }
    val token = before.subSequence(start, before.length).toString()
    // A run with no letter in it spells nothing to look up.
    return if (token.any { it.isLetter() }) token else ""
}

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
 * Only a space this keyboard put there after a TAPPED word, and only while nothing else
 * has happened since. A swipe's autospace is never retracted: there the gesture ended,
 * the space was earned, and a following letter starts a new word - the same distinction
 * [KineticaIME] already draws between its own space and one the user typed.
 *
 * [elapsedMs] is bounded because an automatic space is only provisional for as long as
 * the typing is still in flow. Typing `is`, leaving, and coming back to type `land`
 * must not silently produce `island`.
 *
 * [fusedIsPrefix] is the gate the clock could not provide, added 2026-08-29 after the
 * developer reported finished words swallowing the next one. The retraction is a guess
 * that the word was not over, so it should only be made when the fused form could still
 * BECOME a word: `autom` can, `automaticop` cannot. Measured over the 2026-08-29 capture's
 * 67 consecutive word pairs it refuses 70% of would-be fusions, and it is exact where the
 * damage is - **not one first word of six letters or more still fuses**, which is the whole
 * class that produced empty decodes. Short first words remain ambiguous because almost any
 * two-letter word plus a letter is a live prefix, and that residue is what the window is
 * for. KNOWN_ISSUES item 43.
 */
internal fun retractsAutospace(
    fromTappedWord: Boolean,
    tentativeLength: Int,
    elapsedMs: Long,
    windowMs: Long,
    fusedIsPrefix: Boolean,
): Boolean = fromTappedWord && tentativeLength == 0 && elapsedMs <= windowMs && fusedIsPrefix

/**
 * The word an automatic space would be taken back into, read from the text before the
 * cursor - i.e. the letter run that sits immediately before that space.
 *
 * Returns "" when there is no space at the cursor or nothing word-shaped before it, which
 * makes the retraction refuse: there is nothing to fuse into.
 *
 * Split out from [retractAutospace] so the string half can be tested; the dictionary half
 * is one [WordPredictor.isLivePrefix] call at the site.
 */
/**
 * The timestamp the first synthetic anchor of a reopened word takes, so that all [count] of
 * them land strictly before [before].
 *
 * [before] is the moment the reopened word must precede: the touch time of the letter that
 * caused the reopen, or now when a cursor move caused it. Basing it on `now` instead was a
 * real bug - the letter that triggered the reload carries its REAL touch time, which is
 * earlier than `now` whenever touch-to-reload latency exceeds the word's length in
 * milliseconds, so the new letter sorted before the whole reloaded word. On device that
 * turned `automatico` + `per` into `pautomatico`, `peautomatico`, `peautomaticor` - all of
 * which decode to nothing. Which way it went was decided by processing latency, which is
 * why it looked intermittent. KNOWN_ISSUES item 43.
 */
internal fun reloadAnchorBase(before: Long, count: Int): Long = before - count - 1

/**
 * The word an automatic space would be taken back into, read from the text before the
 * cursor - i.e. the letter run that sits immediately before that space.
 *
 * Returns "" when there is no space at the cursor or nothing word-shaped before it, which
 * makes the retraction refuse: there is nothing to fuse into.
 *
 * Split out from [retractAutospace] so the string half can be tested; the dictionary half
 * is one [WordPredictor.isLivePrefix] call at the site.
 */
internal fun wordBeforeAutospace(before: CharSequence): String {
    if (before.isEmpty() || before[before.length - 1] != ' ') return ""
    return trailingLetterRun(before, before.length - 1)
}

/**
 * The run of letters (and apostrophes) ending at [end] in [before], or "" when the
 * character there is not one.
 *
 * One walk shared by the three questions that ask it: which word the cursor is parked at
 * the end of ([KineticaIME.reloadWordUnderCursor]), which word an automatic space would be
 * taken back into ([wordBeforeAutospace]), and which word a retype throws away
 * ([retypeSpan]). They were separate copies of the same loop, and a retype that disagreed
 * with the reload about where a word starts would delete the wrong thing.
 *
 * The apostrophe is included for the reason the reload includes it: `l'altro` and `don't`
 * are one word to a reader, and to the editor.
 */
internal fun trailingLetterRun(before: CharSequence, end: Int = before.length): String {
    var start = end.coerceIn(0, before.length)
    val stop = start
    while (start > 0 && (before[start - 1].isLetter() || before[start - 1] == '\'')) start--
    return before.subSequence(start, stop).toString()
}

/**
 * True when a user-driven selection change parks a collapsed cursor somewhere a word
 * could be reopened.
 *
 * Only the shape of the selection is decided here - whether there is actually a word
 * before the cursor is [KineticaIME.reloadWordUnderCursor]'s question, and it already
 * answers it. A selection is never a reopen: the user is acting on a range, not
 * appending to a word. Offset 0 is never one either, since nothing precedes it.
 */
internal fun reopensWordUnderCursor(selectionLength: Int, selStart: Int, selEnd: Int): Boolean =
    selectionLength == 0 && selStart == selEnd && selStart > 0

/**
 * How much text a retype deletes: the word in progress, else the word just committed with
 * whatever the keyboard put after it, else the letters the cursor is parked at the end of.
 *
 * The order is what makes the action useful rather than merely available. "Delete the
 * current word and start again in its place" reads as being about the word in progress,
 * but the autospace commits fast, so by the time a wrong word is noticed there usually is
 * no word in progress. The second case deletes the trailing text too, because that is the
 * keyboard's own space and leaving it behind would put the retyped word one space further
 * along.
 *
 * **[wordUnderCursor] is the case the button was actually asked for, and the first version
 * of this did not have it.** Reported: swiping `praticamente` two-thumbed put `pimn` in the
 * editor, decoded to nothing, and the button did nothing at all. An undecodable buffer is
 * closed after a pause by the stale-buffer timeout, and that calls `abandonWord`, which
 * zeroes [tentativeLength] AND nulls [lastCommitWord] while the letters stay on screen. So
 * both of the cases above report nothing exactly when the text is garbage. The developer
 * reached for the button 5.7 s later, long after the 600 ms timeout.
 *
 * Reading the run back out of the editor is not the guess the first version refused to
 * make. The cursor is where the user's own gesture left it, and the run is the same read
 * `reloadWordUnderCursor` already trusts to reopen a word - the caller shares its walk and
 * its guard, so a cursor parked mid-word still does nothing. KNOWN_ISSUES item 47.
 */
/**
 * How long a ONE-LETTER word waits before its automatic space arrives.
 *
 * Longer than a word's, because one letter is weaker evidence: `a` is a word and it is also
 * the first letter of `and`, `arrivato` and `ad`, and no delay tells those apart by shape.
 * Only silence does.
 *
 * Swept over three captures - 22 one-letter words against 28 word-starts whose first letter
 * is also a word in the active language:
 *
 * | delay | spaced | premature |
 * |---|---|---|
 * | 204 (the developer's own slider) | 21/22 | 10/28 |
 * | 250 | 19/22 | 5/28 |
 * | 275 | 18/22 | **3/28** |
 * | **300** | **17/22** | **3/28** |
 * | 350 | 17/22 | **3/28** |
 * | 600 | 14/22 | 1/28 |
 *
 * The plateau is 275-350 and [Prefs.SINGLE_LETTER_MIN_DELAY_MS] sits in the middle of it.
 * 275 is one millisecond above a real cost sample and is therefore a knife edge; 300 has
 * 26 ms of margin below and 161 above, and it is already the shipped default for both other
 * autospace delays.
 *
 * A FLOOR rather than a fixed value: someone who raised the word delay to 600 ms meant it,
 * and a single letter should never be quicker to space than a whole word. Lowering the word
 * slider for speed is not a request for single letters to fire sooner.
 */
internal fun singleLetterDelayMs(tapDelayMs: Long, floorMs: Long): Long =
    maxOf(tapDelayMs, floorMs)

internal fun retypeSpan(
    tentativeLength: Int,
    lastCommitWord: String?,
    lastCommitTrailing: String,
    wordUnderCursor: String,
): Int = when {
    tentativeLength > 0 -> tentativeLength
    lastCommitWord != null -> lastCommitWord.length + lastCommitTrailing.length
    else -> wordUnderCursor.length
}

/** Which of [retypeSpan]'s three cases answered, for the trace. */
internal fun retypeSource(tentativeLength: Int, lastCommitWord: String?): String = when {
    tentativeLength > 0 -> "tentative"
    lastCommitWord != null -> "commit"
    else -> "cursor"
}

internal fun hugsPreviousWord(text: String): Boolean =
    text.length == 1 && text[0] in HUGGING_PUNCTUATION

/**
 * Whether the text immediately before the cursor ends a sentence, so the next
 * letter should be capitalized. [before] is the tail of the editor's text - at
 * most `CAPS_LOOKBACK_CHARS` characters - and empty means the cursor is at the
 * start of the field.
 *
 * This exists because `InputConnection.getCursorCapsMode` cannot answer the
 * question this keyboard asks, which is why autocapitalization did nothing on
 * device. `TextUtils.getCapsMode`, what editors implement it with, reports
 * CAP_MODE_SENTENCES only once whitespace separates the cursor from the
 * terminator, and at the start of a paragraph it reports CAP_MODE_WORDS, which a
 * field asking for CAP_SENTENCES alone masks away. Both are exactly the moments
 * this keyboard reads it: punctuation is committed with nothing after it, and the
 * space before the next word is written as part of that word's commit, so the
 * cursor is never sitting after ". " when the question is asked.
 *
 * Deciding it here also costs nothing: it replaces one query to the editor with
 * another.
 *
 * A newline starts a paragraph and so a sentence. Closing punctuation is skipped,
 * so 'he said "hi."' still ends one. A lone period inside its own word is an
 * abbreviation rather than a terminator, which is the platform's own rule and is
 * what keeps "e.g. " lower-case; a RUN of marks is a terminator, so "Wait..." is
 * not read as an abbreviation for the period it just gained.
 *
 * Accepted cost: a period typed inside a word in a prose field capitalizes what
 * follows, so "example.com" reads "example.Com". Stock keyboards do the same, and
 * a URL field asks for no sentence caps in the first place.
 */
internal fun startsNewSentence(before: CharSequence): Boolean {
    var i = before.length
    while (i > 0 && (before[i - 1] == ' ' || before[i - 1] == '\t')) i--
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
    var j = run
    while (j > 0) {
        val c = before[j - 1]
        if (c == ' ' || c == '\t' || c == '\n') break
        if (c == '.') return false
        j--
    }
    return true
}

private const val SENTENCE_TERMINATORS = ".!?\u2026"

/** Skipped before looking for a terminator: quotes and closing brackets. */
private const val SENTENCE_CLOSERS = ")]}\"'\u00bb\u201d\u2019"

/** Spanish opens a sentence with these, so the word after one begins it. */
private const val SENTENCE_OPENERS = "\u00bf\u00a1"

// Punctuation that OPENS a word rather than joining one, so a space after the word
// that follows it is still right: brackets and the opening quote forms.
//
// The straight apostrophe is deliberately NOT here, although it is also the opening
// single quote. Italian elision - l'altro, d'accordo, un'ora, dell'anno - is far more
// common on this keyboard than single-quoted text, and it is exactly where the
// premature space hurts: `al` is a word, so `l'altro` would autospace to `l'al tro`.
// The cost is one space the user types themselves inside 'quoted text'.
private const val WORD_OPENERS = "([{\"\u00ab\u201c\u2018"

// Punctuation that binds two word-shaped pieces into one token. Every one of these
// appears mid-token in something a keyboard has to type without spacing it:
// snake_case, e-mail, don't, example.com, a/b, user@host, a\\b, C:name.
private const val WORD_JOINERS = "_-'\u2019./\\@:"

private const val HUGGING_PUNCTUATION = ".,!?;:)]}\u00bb\u2026"

internal fun learnsOnCommit(word: String, reloadedFrom: String?): Boolean {
    if (reloadedFrom == null) return true
    return !word.equals(reloadedFrom, ignoreCase = true)
}

internal fun suggestionZoneWords(candidates: List<String>, literal: String): List<String> =
    if (literal.isEmpty() || candidates.contains(literal)) candidates
    else candidates + literal
