package com.kinetica.keyboard.ime

import android.content.SharedPreferences
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Vibrator
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodSubtype
import androidx.preference.PreferenceManager
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
import com.kinetica.keyboard.keys.ChordLabels
import com.kinetica.keyboard.keys.ChordTrigger
import com.kinetica.keyboard.keys.DeleteSpan
import com.kinetica.keyboard.keys.EditorAction
import com.kinetica.keyboard.keys.ShiftState
import com.kinetica.keyboard.layout.Key
import com.kinetica.keyboard.layout.KeyType
import com.kinetica.keyboard.layout.LayoutMutations
import com.kinetica.keyboard.settings.EdgeSwipeDefaults
import com.kinetica.keyboard.settings.KeyboardConfig
import com.kinetica.keyboard.settings.Prefs
import com.kinetica.keyboard.settings.SettingsSynonyms
import com.kinetica.keyboard.ui.EmojiPickerView
import com.kinetica.keyboard.ui.BarAdjust
import com.kinetica.keyboard.ui.InputContainerView
import com.kinetica.keyboard.ui.KeyboardView
import com.kinetica.keyboard.ui.SuggestionBarView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Kinetica input method service.
 *
 * Text model: commit-only, no composing region. Tap letters commit
 * immediately; the word in progress is tracked as [WordState.tentativeLength] committed
 * chars, and swipe decodes replace that span in a single batch edit. This
 * avoids the composing-region state machine and its OEM quirks entirely.
 *
 * The service owns the lifecycle, the touch and key entry points and the editor bookkeeping;
 * each concern it dispatches to is a collaborator over the shared [WordState]: [CommitPath],
 * [Autospace], [BarContents], [Backspace], [WordReopen], [WordEdits], [Expansions],
 * [EditorCommands], [KeyboardViews], with [Learner] and [ResidentDictionaries] beneath them.
 */
class KineticaIME : InputMethodService(), GestureEngine.Listener, WordComposer.Callbacks {

    internal val mainHandler = Handler(Looper.getMainLooper())

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
    internal val dbExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "kinetica-db")
    }

    internal val ich = InputConnectionHelper { currentInputConnection }
    internal val engine = GestureEngine(this)
    internal val shift = ShiftState()
    internal val residents = ResidentDictionaries(this, mainHandler)
    // The last commits by place, each with what it taught.
    internal val commitMarks = CommitMarks()
    internal val learner = Learner(
        this, mainHandler, dbExecutor, residents, commitMarks,
        config = { config },
        editorState = { editorState },
        languageOf = ::languageOf,
        wordPending = { composer?.hasPendingWord == true },
        reload = { loadDictionaryAsync() },
    )
    internal var composer: WordComposer? = null
        private set
    internal val wordState = WordState()

    // The concerns the service dispatches to, each over the word state above.
    internal val autospace = Autospace(this)
    internal val barContents = BarContents(this)
    internal val expansions = Expansions(this)
    internal val commands = EditorCommands(this)
    internal val backspace = Backspace(this)
    internal val views = KeyboardViews(this)
    internal val edits = WordEdits(this)
    internal val commit = CommitPath(this)
    internal val reopen = WordReopen(this)
    private val languageSync = LanguageSync(this) { config }

    /** Which resident language is being typed, with no primary language. Main thread. */
    internal val momentum = LanguageMomentum()

    internal var keyboardView: KeyboardView? = null
        private set
    internal var suggestionBar: SuggestionBarView? = null
        private set
    internal var containerView: InputContainerView? = null
        private set
    internal var emojiPicker: EmojiPickerView? = null
    internal var currentGeometry: KeyboardGeometry? = null
        private set

    /** The letters of the board on screen: a tap's code is a code of this alphabet. */
    private val boardAlphabet: Alphabet get() = currentGeometry?.alphabet ?: Alphabet.LATIN
    private var prefListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    internal var editorState = EditorState.DEFAULT
        private set

    internal lateinit var config: KeyboardConfig
        private set

    internal val selectionLedger = SelectionLedger()
    /** This field's commits and their bars' words, for an offer inside one of them. */
    internal val commitHistory = CommitHistory()

    // The word in progress before each of its gestures, for a backspace that takes one back.
    internal val gestureUndo = GestureUndo()

    // The editor's selection, normalized so start <= end; equal means a plain cursor.
    // Insertions do not need it, since commitText replaces a selection itself. Backspace does:
    // deleteSurroundingText works relative to the selection and leaves it standing, so it
    // would delete a character beside the selection.
    internal var selStart = 0
        private set
    internal var selEnd = 0
        private set

    internal val sharedFiling = SharedWordFiling()
    internal val lastCommit = CommitMemory()

    override fun onCreate() {
        super.onCreate()
        // Decode tracing, debug build only. Two-thumb gestures cannot be reproduced on
        // the JVM, so a capture of the real token buffer is the evidence for them. The
        // release source set's TraceRecorder is a stub that installs nothing.
        TraceRecorder.install(this)
        // Lengths and flags only, never text: what a crash line records about the word in hand.
        TraceRecorder.crashState = {
            "tentative=${wordState.tentativeLength} tokens=${composer?.tokenCount} offer=${wordState.midWordOffer != null} " +
                "kept=${wordState.keptBar != null} sel=$selStart,$selEnd field=${editorState.fieldKind}"
        }
        engine.maxPointers = 2
        @Suppress("DEPRECATION")
        views.vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        EdgeSwipeDefaults.applyTo(prefs)
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
            views.applyViewConfig()
            if (config.enabledLanguages != previous.enabledLanguages) languageSync.syncEnabledSubtypes()
            // Its own branch: an expansion edit touches no dictionary or layout, and folding
            // it into the chain below would rebuild one.
            if (config.expansionGeneration != previous.expansionGeneration) {
                expansions.reloadExpansions()
            }
            if (config.peckMode != previous.peckMode) {
                // Entering or leaving literal mode mid-word would leave a
                // half-tracked tentative; settle it as plain text first.
                commit.abandonWord()
            }
            if (config.language != previous.language) {
                // Before the layout is built, so the board comes out of the arrangement
                // the language asked for, not the one it replaces.
                languageSync.applyArrangementForLanguage(p, config.language)
                // Swap layout immediately; predictions swap when the new
                // dictionary finishes parsing (the old one keeps serving).
                commit.abandonWord()
                keyboardView?.setKeyboardLayout(views.alphaLayout())
                loadDictionaryAsync()
                languageSync.requestLanguageSubtype(config.language)
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
                config.fieldKeys != previous.fieldKeys ||
                config.periodMode != previous.periodMode ||
                config.periodCustom != previous.periodCustom ||
                config.periodAlternates != previous.periodAlternates ||
                config.commaAlternates != previous.commaAlternates
            ) {
                views.closeEmojiPicker()
                keyboardView?.setKeyboardLayout(views.alphaLayout())
            }
        }
        prefListener = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
        loadDictionaryAsync()
        expansions.reloadExpansions()
    }

    override fun onDestroy() {
        prefListener?.let {
            PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(it)
        }
        learner.cancelUserDictReload()
        // Every posted callback goes before the executors shut: one running after would hand
        // work to a closed executor and throw on the main thread.
        mainHandler.removeCallbacksAndMessages(null)
        decodeExecutor.shutdown()
        alternateDecodeExecutor.shutdown()
        extraDecodeExecutor.shutdown()
        dbExecutor.shutdown()
        super.onDestroy()
    }

    internal fun loadDictionaryAsync() {
        residents.load({ config }, { currentGeometry }, ::installWaits) { p ->
            // A language that left (a switch from Polish) must not keep its standing.
            momentum.retain(residentLanguages())
            composer = WordComposer(
                p, decodeExecutor, mainExecutor, this, alternateDecodeExecutor, extraDecodeExecutor,
            ).also {
                it.alternatePredictor = residents.secondaryPredictor
                it.extraPredictor = residents.extraPredictor
                it.languageWeights = equalWeights()
            }
        }
    }

    private fun installWaits(): Boolean =
        holdsDictionaryInstall(wordState.tentativeLength, composer?.hasPendingWord == true)

    /**
     * A word has ended: a dictionary held under it installs. Posted, since the path that ended it
     * still uses the composer, and a gesture queued before the post may start the next word.
     */
    internal fun wordEnded() {
        if (residents.holdsInstall) mainHandler.post { residents.releaseInstall(installWaits()) }
    }

    // ------------------------------------------------------------------ views

    override fun onCreateInputView(): View {
        val bar = SuggestionBarView(this)
        bar.listener = suggestionListener
        bar.flickEnabled = true

        val kv = KeyboardView(this)
        kv.engine = engine
        kv.listener = keyboardListener
        kv.setKeyboardLayout(views.alphaLayout())
        kv.setShiftUppercase(shift.isShifted)

        suggestionBar = bar
        keyboardView = kv
        emojiPicker = null

        val container = InputContainerView(
            this, bar, kv,
            barHeightPx = views.dpToPx(views.barHeightDp()),
            keyboardHeightPx = views.keyboardHeightPx(),
            minKeyboardPx = views.minKeyboardPx(),
            maxKeyboardPx = views.maxKeyboardPx(),
            onHeightCommitted = { px -> views.persistHeightPct(px) },
            handleHeightPx = views.dpToPx(config.dragHandleDp.toFloat()),
            bottomGapPx = views.dpToPx(config.bottomPadDp.toFloat()),
        )
        containerView = container
        views.applyViewConfig()
        return container
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (keyboardView != null) {
            setInputView(onCreateInputView())
        }
    }

    // ------------------------------------------------------------- lifecycle

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // Ctrl held for the next key belongs to the field it was armed in.
        if (!restarting) commands.ctrlPending = false
        // An app's restartInput lands here too and clears the bar with it.
        DecodeTrace.log {
            "  start restarting=$restarting pending=${wordState.tentativeLength > 0 || composer?.hasPendingWord == true}"
        }
        languageSync.synchronizeLanguageOnStart()
        editorState = EditorState.from(attribute)
        backspace.target = null
        commit.abandonWord()
        composer?.reset()
        barContents.recentWords.clear()
        commit.clearTypingSpeed()
        selectionLedger.clear()
        commitHistory.clear()
        forgetCommitMarks()
        wordState.reopenedContext = null
        backspace.autocorrectUndo = null
        // A field can open with text already selected, so seed from the editor. Either offset
        // is -1 when the editor did not report one, which means no selection.
        val s = attribute?.initialSelStart ?: -1
        val e = attribute?.initialSelEnd ?: -1
        setSelectionCache(s, e)
        // A field that asks for no sentence caps, an address above all, must not inherit the
        // capital the last field armed. Caps lock is the user's and stays.
        if (!editorState.capSentences && shift.state == ShiftState.State.SHIFT) {
            shift.autoShift(false)
            keyboardView?.setShiftUppercase(shift.isShifted)
        }
        updateAutoShift()
        expansions.reloadChords()
        learner.reloadEmojiUses()
    }

    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        suggestionBar?.clearSuggestions()
        barContents.updateBarWordPending()
        keyboardView?.setShiftUppercase(shift.isShifted)
        views.closeEmojiPicker()
        val onLetters = keyboardView?.showsLetters ?: true
        // Left by hand in the meantime, the pad is no longer the field's to take down.
        if (onLetters) views.numpadAuto = false
        when {
            opensOnNumpad(restarting, config.numpadForNumbers, editorState.fieldKind == FieldKind.NUMERIC) -> {
                keyboardView?.setKeyboardLayout(views.layoutFor("numpad"))
                views.numpadAuto = true
            }
            // A pad a number field put up goes with it, whatever the letters setting.
            opensOnLetters(restarting, config.lettersOnNewField || views.numpadAuto, onLetters) -> {
                keyboardView?.setKeyboardLayout(views.alphaLayout())
                views.numpadAuto = false
            }
            onLetters && !restarting && editorState.fieldKind != views.layoutFieldKind ->
                keyboardView?.setKeyboardLayout(views.alphaLayout())
        }
        views.applyViewConfig()
    }

    override fun onFinishInput() {
        commit.abandonWord()
        composer?.reset()
        // A notice belongs to the field it was shown in, not to the next one.
        commands.clearSpacebarNotice()
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
        if (verdict == SelectionLedger.Verdict.USER) {
            backspace.autocorrectUndo = null
            gestureUndo.forgetCommitted()
        }
        if (verdict != SelectionLedger.Verdict.USER) {
            DecodeTrace.log {
                val kind = if (verdict == SelectionLedger.Verdict.OWN) "own" else "mismatch"
                "  selection $kind old=$oldSelStart,$oldSelEnd new=$newSelStart,$newSelEnd left=${selectionLedger.pending}"
            }
            return
        }
        // The user moved the cursor themselves: the pending word is no longer under
        // the cursor, abandon it.
        if (wordState.tentativeLength > 0 || composer?.hasPendingWord == true) {
            // Printed because it clears a live bar: an editor that reports a move the user
            // never made shows up here.
            DecodeTrace.log {
                "  selection user old=$oldSelStart,$oldSelEnd new=$newSelStart,$newSelEnd abandon len=${wordState.tentativeLength}"
            }
            // A reopened commit the cursor leaves standing is the word before the next one again.
            wordState.reopenedContext?.let { w -> if (commitMarks.newest?.word == w) composer?.restoreLastCommit(w) }
            wordState.reopenedContext = null
            commit.abandonWord()
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
            if (!reopen.offerMidWord()) reopen.reloadWordUnderCursor()
        }
        // The correction strip names the text before the cursor, which a selection is not. A
        // pick would delete text beside the selection and replace the selection too. Only the
        // selection case: a plain cursor move already clears the strip through abandonWord,
        // and widening this would lose the strip in any editor the ledger misjudges.
        if (selectionLength() > 0) barContents.clearCorrection()
        // The shift was decided for the old cursor position: after a period, a cursor moved back
        // into the sentence would capitalize the next letter.
        if (selectionLength() == 0) updateAutoShift()
        barContents.refreshPredictions()
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

    internal fun selectionLength(): Int = selEnd - selStart

    // -------------------------------------------------- engine listener (UI)

    override fun onTokenFinalized(token: InputToken) {
        // Spent by this gesture, whatever it is.
        val shiftAt = autospace.takeShiftAt()
        gestureUndo.forgetCommitted()
        shift.onOtherKey()
        backspace.autocorrectUndo = null
        autospace.cancel()
        barContents.clearCorrection()
        // A tapped letter under the one-shot Ctrl is a key combination, not a letter; a swipe
        // is a word and lets Ctrl go.
        if (commands.ctrlPending) {
            if (token is TapToken && commands.consumeCtrl(boardAlphabet.charOf(token.code).toString())) return
            if (token is SwipeToken) {
                commands.ctrlPending = false
                DecodeTrace.log { "  ctrl dropped src=swipe" }
            }
        }
        // A new gesture starts a new word; a kept bar belongs to the one before it.
        wordState.keptBar = null
        commit.noteTouch(token)
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
                autospace.retractBeforeLetter(boardAlphabet.charOf(token.code), token.tStart, shiftAt)
                rememberWordBeforeGesture()
                if (wordState.tentativeLength == 0) wordState.wordShift = shift.state
                val ch = shift.apply(boardAlphabet.charOf(token.code))
                commitTracked(ch.toString())
                wordState.tentativeLength += 1
                wordState.tentativeWord += ch
                if (shift.state == ShiftState.State.SHIFT) {
                    shift.onLetterCommitted()
                    keyboardView?.setShiftUppercase(shift.isShifted)
                }
                // Cleared here, not at the top of this method: the retract above reloads
                // the word inside this call, so an earlier clear would be undone by the
                // reload and the token's decode would stay suppressed.
                wordState.seededWithoutTokens = false
                comp?.onToken(token)
            }
            is SwipeToken -> {
                if (comp == null) {
                    Log.w(TAG, "swipe before dictionary ready, dropped")
                    return
                }
                rememberWordBeforeGesture()
                if (wordState.tentativeLength == 0) wordState.wordShift = shift.state
                wordState.seededWithoutTokens = false
                comp.onToken(token)
                if (shift.state == ShiftState.State.SHIFT) {
                    shift.onLetterCommitted()
                    keyboardView?.setShiftUppercase(shift.isShifted)
                }
            }
        }
    }

    /** The word as it stands before the gesture about to join it; see Backspace.undoLastGesture. */
    private fun rememberWordBeforeGesture() {
        val comp = composer ?: return
        gestureUndo.push(
            GestureUndo.State(wordState.tentativeWord, comp.tokenCount, shift.state, wordState.wordShift, wordState.swipeDecodeEmpty),
        )
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
        val rejected = edits.retypeRejected
        val ranked = demoteRejected(candidates, rejected)
        // Spent on the first decode that has anything to say, so an empty one in between
        // does not waste it.
        if (candidates.isNotEmpty()) edits.retypeRejected = null
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
        decoded: List<WordCandidate>,
        decodedLead: WordCandidate?,
        literal: String,
    ) {
        // A word blocked a moment ago is still in the trie until the reload: it must not be
        // written or offered meanwhile.
        val blocked = residents.blockedSpellings
        val candidates = if (blocked.isEmpty()) decoded else decoded.filter { it.word.lowercase() !in blocked }
        // A blocked lead leaves the word pick-only: the next head may be a word the merge refused
        // to lead with, another language's.
        val tentative = if (decodedLead != null && decodedLead.word.lowercase() in blocked) null else decodedLead
        wordState.lastCandidates = candidates
        wordState.lastTentative = tentative
        wordState.lastLiteral = literal
        wordState.candidateLanguages = provenanceOf(candidates)
        if (!editorState.privateMode) {
            barContents.pushSuggestions()
        }
        val comp = composer ?: return
        // Swipe-bearing words are tentative: the editor shows the candidate the
        // merge cleared for auto-commit. All-tap words keep the literal text.
        if (!comp.hasSwipeToken()) {
            autospace.armForTappedWord(literal)
            return
        }
        if (comp.hasSwipeToken()) {
            if (tentative != null) {
                wordState.swipeDecodeEmpty = false
                replaceTentative(displayWord(tentative.word))
                autospace.armForSwipedWord()
            } else {
                // Nothing has earned the editor: the full buffer has no decode (the visible
                // tentative is a stale partial one), or only a non-active language explains
                // it, which WordComposer.merge reads as undecodable. Flag the word so a
                // delimiter neither autospaces nor learns it. The bar keeps whatever
                // candidates exist, still pickable.
                wordState.swipeDecodeEmpty = true
                autospace.cancel()
                if (!engine.hasActivePointers()) autospace.scheduleStaleTimeout()
            }
        }
    }

    // -------------------------------------------------------- key handling

    private val keyboardListener = object : KeyboardView.Listener {
        override fun onKeyTap(key: Key) {
            if (key.type != KeyType.SHIFT) shift.onOtherKey()
            when (key.type) {
                KeyType.CHAR -> onPunctuation(key.output)
                KeyType.SPACE -> onSpace()
                KeyType.BACKSPACE -> backspace.onKey(tap = true)
                KeyType.ENTER -> onEnter()
                KeyType.SHIFT -> {
                    autospace.stopRetractClockAtShift()
                    shift.onShiftKey()
                    keyboardView?.setShiftUppercase(shift.isShifted)
                }
                // ?123 always lands on page 1, so leaving and re-entering the
                // symbols layer resets any page-2 state.
                KeyType.MODE_SYMBOLS -> keyboardView?.setKeyboardLayout(views.layoutFor("symbols"))
                KeyType.MODE_SYMBOLS2 -> keyboardView?.setKeyboardLayout(views.layoutFor("symbols2"))
                KeyType.MODE_ALPHA -> keyboardView?.setKeyboardLayout(views.alphaLayout())
                KeyType.MODE_NUMPAD -> keyboardView?.setKeyboardLayout(views.layoutFor("numpad"))
                KeyType.EMOJI -> views.openEmojiPicker()
            }
        }

        override fun onGeometryChanged(geometry: KeyboardGeometry) {
            currentGeometry = geometry
            residents.setGeometry(geometry)
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

        override fun onDeleteChar(tap: Boolean) = backspace.onKey(tap)

        override fun onStageDelete(units: Int, granularity: DeleteSpan.Granularity) =
            backspace.stage(units, granularity)

        override fun onCommitStagedDelete() = backspace.commitStaged()

        override fun onEdgeSwipe(output: String) {
            autospace.cancel()
            if (output == "emoji") {
                // Always available: the secondary trigger independent of the
                // comma long-press opt-in.
                views.openEmojiPicker()
                return
            }
            // The binding editor is a free text field, so a reserved output can be typed into
            // it. It runs here as it does on the chord path, instead of being inserted.
            if (commands.performIfAction(output)) return
            // A shortcut placed first in a letter's list is also its up-swipe.
            ActionRow.actionForGlyph(output)?.let {
                commands.performShortcut(it)
                return
            }
            finalizeThenCommitText(output)
            updateAutoShift()
        }

        override fun onKeyAlternate(key: Key, text: String) {
            if (key.type != KeyType.SHIFT) shift.onOtherKey()
            autospace.cancel()
            if (text.isEmpty()) return
            // Shift's cells are labels, not text: the key is the command and the cell its
            // argument. So they need no reserved prefix, and this branch must come before
            // every commit path below.
            if (key.type == KeyType.SHIFT) {
                edits.recaseWordInHand(text)
                return
            }
            if (text == LayoutMutations.EMOJI_ALTERNATE) {
                views.openEmojiPicker()
                return
            }
            // Enter's hold where a tap writes the newline: the field's action.
            if (key.type == KeyType.ENTER && text == LayoutMutations.ACTION_ALTERNATE) {
                EnterBehavior.holdAction(editorState, config.enterHoldSends)?.let {
                    commit.finalizePendingWord()
                    DecodeTrace.log { "  enter action=${it.id} src=hold" }
                    ich.performEditorAction(it.id)
                    updateAutoShift()
                    return
                }
            }
            // Enter's hold, where enter runs the app's action: the one way left to a newline.
            if (key.type == KeyType.ENTER && text == LayoutMutations.NEWLINE_ALTERNATE) {
                commit.finalizePendingWord()
                commitTracked("\n")
                updateAutoShift()
                return
            }
            // A shortcut's symbol in any key's list runs the shortcut; a key combination or a
            // target written out (`combo:ctrl+a`) runs as it would from a chord.
            ActionRow.actionForGlyph(text)?.let {
                commands.performShortcut(it)
                return
            }
            if (commands.performIfAction(text)) return
            // A repurposed key's own cell is its label, `⎘` or `ALL`: it does what a tap does.
            if (!key.isLetter && text == key.label && key.output != text && commands.performIfAction(key.output)) {
                return
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
                KeyType.MODE_NUMPAD -> keyboardView?.setKeyboardLayout(views.layoutFor("numpad"))
                KeyType.MODE_ALPHA -> keyboardView?.setKeyboardLayout(views.alphaLayout())
                else -> Unit
            }
        }

        override fun onSettingsRequested() = commands.openSettings()

        override fun onEditAlternates(letter: Char) {
            views.vibrateForKeyPress()
            commands.openSettings(SettingsSynonyms.LONGPRESS_GROUP, Prefs.letterAlternatesKey(letter))
        }

        override fun onMenuAction(index: Int) = commands.performMenuAction(index)

        override fun onRestingFingerDropped() {
            if (!engine.hasActivePointers()) composer?.redecode()
        }

        override fun hasChord(trigger: ChordTrigger, key: Char): Boolean =
            expansions.chordMap.containsKey(ChordKey(trigger, key))

        override fun chordLabel(trigger: ChordTrigger, key: Char): String? =
            expansions.chordMap[ChordKey(trigger, key)]?.let { ChordLabels.of(it) }

        override fun onChordTriggered(trigger: ChordTrigger, key: Char, heldMs: Long) {
            val expansion = expansions.chordMap[ChordKey(trigger, key)] ?: return
            DecodeTrace.log { "  chord fired trigger=${trigger.name.lowercase()} key=$key held=${heldMs}ms" }
            autospace.cancel()
            // A command goes the shortcut row's way, so it says what it did on the spacebar;
            // anything else inserts as before. A misspelt `action:` is swallowed, never typed.
            val action = EditorAction.of(expansion)
            when {
                action != null -> commands.performShortcut(action)
                !commands.performIfAction(expansion) -> finalizeThenCommitText(expansion)
            }
            updateAutoShift()
        }

        override fun onChordMissed(trigger: ChordTrigger, key: Char, reason: String) {
            // The rollover measurement: a chord candidate that typed instead, and why.
            DecodeTrace.log { "  chord missed trigger=${trigger.name.lowercase()} key=$key reason=$reason" }
        }

        override fun onKeyPressFeedback(key: Key) {
            autospace.cancel()
            // Only a backspace tap may still take back the word the automatic space committed.
            if (key.type != KeyType.BACKSPACE) gestureUndo.forgetCommitted()
            // Any key ends the offer before it acts: the word around the cursor was never in
            // progress, and a letter, a delete or a space at the cursor is not a pick.
            if (wordState.midWordOffer != null) {
                DecodeTrace.log { "  midword drop src=key" }
                commit.abandonWord()
            }
            views.vibrateForKeyPress()
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
            commit.onSuggestionPicked(word)

        override fun onCorrectionPicked(replacement: String) =
            commit.onCorrectionPicked(replacement)

        override fun onSuggestionReinforced(word: String, delta: Int) =
            barContents.onSuggestionReinforced(word, delta)

        override fun onReinforceStep() {
            if (!barContents.expansionsOnBar()) views.vibrateForKeyPress()
        }

        override fun onSuggestionBlocked(word: String, source: BarAdjust.BlockSource) =
            barContents.onSuggestionBlocked(word, source)

        override fun onRetype() {
            views.vibrateForKeyPress()
            // Through the shared action: the bar button and a ?123 chord are two triggers for
            // one implementation.
            commands.performIfAction(EditorAction.RETYPE.output)
        }

        override fun onBarAction(index: Int) = commands.performBarAction(index)

        override fun onRecentPicked(back: Int, word: String) {
            views.vibrateForKeyPress()
            barContents.onRecentPicked(back, word)
        }
    }

    private fun finalizeThenCommitText(text: String) {
        autospace.eatBefore(text)
        commit.finalizePendingWord()
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
        barContents.clearCorrection()
        rememberWordBeforeGesture()
        if (wordState.tentativeLength == 0) wordState.wordShift = shift.state
        // The popup already applied the layout's case to its cells, so the glyph goes in
        // as chosen, not through shift.apply again.
        commitTracked(text)
        wordState.tentativeLength += text.length
        wordState.tentativeWord += text
        val now = SystemClock.uptimeMillis()
        comp.onToken(
            TapToken(
                StreamId.LEFT, code, g.centerX(code), g.centerY(code),
                longPress = false, tStart = now, tEnd = now + 1,
            ),
        )
        return true
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

    private fun onSpace() {
        if (commands.consumeCtrl("space")) return
        // A space tapped after the automatic one says the word was over.
        if (autospace.inserted) commitMarks.unspaceAt(cursorExpected() - 1)
        if (config.tidySpaces && composer?.hasPendingWord != true) {
            when (tidySpaceTap(ich.textBeforeCursor(2) ?: "", config.doubleSpacePeriod)) {
                SpaceTap.SENTENCE_END -> {
                    onDoubleSpace()
                    return
                }
                SpaceTap.SWALLOW -> {
                    // Never two spaces in a row: the one there already is the space.
                    // A pending word that finishes now still gets its own.
                    autospace.cancel()
                    if (commit.finalizePendingWord() && ich.textBeforeCursor(1)?.lastOrNull() != ' ') {
                        commitTracked(" ")
                    } else {
                        DecodeTrace.log { "  space swallowed" }
                    }
                    updateAutoShift()
                    barContents.refreshPredictions()
                    return
                }
                SpaceTap.WRITE -> Unit
            }
        }
        autospace.cancel()
        commit.finalizePendingWord()
        commitTracked(" ")
        updateAutoShift()
        barContents.refreshPredictions()
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
        autospace.cancel()
        ich.deleteBeforeCursor(1)
        expectAfter(1, 0)
        commitMarks.unspaceAt(cursorExpected())
        commitTracked(". ")
        autospace.inserted = true
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
        autospace.cancel()
        autospace.eatSpaceless()
        commit.finalizePendingWord()
        updateAutoShift()
    }

    private fun onPunctuation(text: String) {
        if (text.length == 1 && commands.consumeCtrl(text.lowercase())) return
        autospace.cancel()
        // Reserved action outputs never reach the editor as text.
        if (commands.performIfAction(text)) return
        // "Hi" + autospace + "!" should read "Hi!", not "Hi !".
        autospace.eatBefore(text)
        commit.finalizePendingWord()
        commitTracked(text)
        updateAutoShift()
        // No pair is read across punctuation: next words offered before it go, and beside the
        // correction the whole strip comes back.
        barContents.refreshPredictions()
    }

    internal fun onEnter() {
        if (commands.consumeCtrl("enter")) return
        autospace.cancel()
        commit.finalizePendingWord()
        // The symbol pages and the numpad have no enter hold, so their tap keeps the action.
        val holdSends = config.enterHoldSends && keyboardView?.showsLetters != false
        when (val r = EnterBehavior.tap(editorState, config.enterAction, holdSends)) {
            EnterBehavior.Result.Newline -> commitTracked("\n")
            is EnterBehavior.Result.Action -> {
                DecodeTrace.log { "  enter action=${r.id}" }
                ich.performEditorAction(r.id)
            }
        }
        updateAutoShift()
    }

    /** The resident languages in load order: active, second, third. */
    internal fun residentLanguages(): List<String> = residents.languages(config.language)

    /** Weights for the composer with no primary language, or null for the pairwise merge. */
    private fun equalWeights(): Map<String, Float>? =
        if (config.noPrimary && residents.secondaryPredictor != null) momentum.weightsFor(residentLanguages()) else null

    // ---------------------------------------------------------- word state

    /**
     * A path that rewrote or unlearned a written word has settled what it taught, so no reload may
     * take that lesson back again and no backspace may undo the word; an edit the marks cannot
     * follow (`expectAt`, `expectAny`, a replaced selection) forgets them the same way.
     */
    internal fun forgetCommitMarks() {
        commitMarks.clear()
        gestureUndo.forgetCommitted()
    }

    /** The predictor serving [lang]: the active one, the second or the third. */
    internal fun predictorFor(lang: String): WordPredictor? = residents.predictorFor(lang, config.language)

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype?) =
        languageSync.onSubtypeChanged(newSubtype)

    /**
     * The resident languages whose lexicon holds [word], each with its frequency byte there.
     * Empty with one language resident, which leaves every filing to provenance.
     */
    internal fun heldBy(word: String): Map<String, Int> = residents.heldBy(word, config.language)

    /**
     * The dictionary a word on offer came from, defaulting to the active language for anything
     * not in the current candidate list (a typed literal, a correction never a candidate).
     */
    internal fun languageOf(word: String): String {
        val w = word.lowercase()
        return wordState.candidateLanguages[w] ?: wordState.correctionLanguages[w] ?: config.language
    }

    internal fun casesFor(lang: String): ConcurrentHashMap<String, DictionaryLoader.LearnedCase> = residents.casesFor(lang)

    /** Live count map backing [lang]'s predictor; the active one by default. */
    internal fun countsFor(lang: String): ConcurrentHashMap<String, Int> = residents.countsFor(lang, config.language)

    /**
     * Records where an own edit leaves the cursor: [deleted] characters before it (or before the
     * selection, which a write replaces) gone and [inserted] written. Chained edits start from the
     * last position expected, since the editor's reports lag behind them.
     */
    internal fun expectAfter(deleted: Int, inserted: Int, rewritesWord: Boolean = false) {
        val start = cursorExpected()
        // A rewrite of a recorded word updates its record itself; any other edit moves the records
        // after it and drops one it cuts into.
        if (!rewritesWord) commitHistory.onEdit(start, deleted, inserted)
        commitMarks.onEdit(start, deleted, inserted)
        expectAt((start - deleted).coerceAtLeast(0) + inserted)
    }

    /** Where the cursor is once the edits made so far land. */
    internal fun cursorExpected(): Int = selectionLedger.lastExpected()?.first ?: selStart

    internal fun expectAt(start: Int, end: Int = start) {
        selectionLedger.expect(start, end, SystemClock.uptimeMillis())
    }

    internal fun replaceTentative(word: String) {
        ich.replaceBeforeCursor(wordState.tentativeLength, word)
        expectAfter(wordState.tentativeLength, word.length)
        wordState.tentativeLength = word.length
        wordState.tentativeWord = word
    }

    internal fun commitTracked(text: String) {
        // Text over a selection replaces it, which expectAfter does not count.
        if (selectionLength() > 0) forgetCommitMarks()
        ich.commitText(text)
        expectAfter(0, text.length)
        // Cleared here so the flags only describe the space written last. The callers that
        // write an automatic space set them again right after.
        autospace.inserted = false
        autospace.fromTaps = false
        gestureUndo.forgetCommitted()
    }

    /**
     * A decoded word as it should appear: the captured shift state applied,
     * then any language-mandated capitalization on top (idempotent, so a word
     * already upper-cased by SHIFT or CAPS_LOCK is unaffected).
     */
    internal fun displayWord(word: String): String {
        val shifted = when (wordState.wordShift) {
            ShiftState.State.NONE -> word
            ShiftState.State.SHIFT -> word.replaceFirstChar { it.uppercaseChar() }
            ShiftState.State.CAPS_LOCK -> word.uppercase()
        }
        return AutoCapitalization.forWord(shifted, config.language, languageOf(word))
    }

    internal fun updateAutoShift() {
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

    internal companion object {
        const val TAG = "KineticaIME"

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
    }
}
