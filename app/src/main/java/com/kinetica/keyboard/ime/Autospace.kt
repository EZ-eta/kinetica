package com.kinetica.keyboard.ime

import android.os.SystemClock
import com.kinetica.keyboard.engine.DecodeTrace
import com.kinetica.keyboard.engine.KineticaConstants
import com.kinetica.keyboard.keys.StandaloneLetters

/**
 * The automatic space after a word: its timer, the flags that describe the space once written,
 * and the retract that takes it back when the word was not over.
 *
 * The stale-buffer timeout lives here too: it shares the timer's cancel, so every path that drops a
 * pending space drops it as well.
 */
internal class Autospace(private val ime: KineticaIME) {

    // True while the space directly before the cursor is one autospace put there,
    // not one the user typed. Only an automatic space is taken back by punctuation:
    // a deliberate space before a dash is the user's own and stays.
    var inserted = false

    // A buffer whose decode came back empty is closed after a pause instead of
    // being left open. See staleBufferRunnable.
    private var stalePending = false
    private val staleBufferRunnable = Runnable {
        stalePending = false
        if (ime.wordState.lastCandidates.isNotEmpty()) ime.commit.closeBufferKeepBar() else ime.commit.abandonWord()
    }

    // True while the space before the cursor is an autospace that followed a tapped word.
    // Only that kind is provisional: a swipe's space follows a finished gesture, so a letter
    // after it starts a new word.
    var fromTaps = false
    private var writtenAt = 0L
    // The shift press that stopped that space's retract clock, or -1; see stopRetractClockAtShift.
    private var shiftAt = -1L

    private var pending = false
    private val timer = Runnable {
        pending = false
        ime.suggestionBar?.clearAutospaceTimer()
        val comp = ime.composer
        val hasSwipe = comp?.hasSwipeToken() == true
        // The text preceding the word being written, i.e. the word's own letters dropped
        // off the end of what the editor holds. Read once: both questions below are about
        // its last character.
        val wokeJoiner =
            ime.ich.textBeforeCursor(ime.wordState.tentativeLength + 1)?.dropLast(ime.wordState.tentativeLength) ?: ""
        val taps = autospacesTappedWord(
            enabled = ime.config.autospaceTappedWords,
            hasSwipeToken = hasSwipe,
            literal = ime.wordState.lastLiteral,
            literalIsWord = ime.residents.predictor?.isWord(ime.wordState.lastLiteral) == true,
            literalIsStandaloneLetter = standaloneLetter(ime.wordState.lastLiteral),
            addressField = ime.editorState.addressField,
            // Re-read when the timer fires, not at scheduling: this site inserts the space,
            // and the text can have moved since.
            joinedToWhatPrecedes = joinsPrecedingToken(wokeJoiner),
            joinedTokenIsWord = joinedTokenIsWord(),
            joinedByApostrophe = joinedByApostrophe(wokeJoiner),
            carriesNoToken = ime.wordState.seededWithoutTokens,
        )
        val swipes = autospacesSwipedWord(
            hasSwipeToken = hasSwipe,
            addressField = ime.editorState.addressField,
            carriesNoToken = ime.wordState.seededWithoutTokens,
        )
        DecodeTrace.log {
            "  autospace wake lit=${ime.wordState.lastLiteral} pending=${comp?.hasPendingWord} " +
                "taps=$taps swipes=$swipes"
        }
        if (comp?.hasPendingWord == true && (swipes || taps)) {
            // Read before the word is finalized, which can rewrite it but never what follows.
            val after = if (ime.config.tidySpaces) ime.ich.textAfterCursor(2) else null
            if (!autospaceWanted(ime.config.tidySpaces, after, ime.config.noSpaceBefore)) {
                // The word still ends; the editor already has what should follow it.
                if (ime.commit.finalizePendingWord()) {
                    DecodeTrace.log { "  autospace held before=${after?.firstOrNull()?.code}" }
                    ime.updateAutoShift()
                    ime.barContents.refreshPredictions()
                }
                return@Runnable
            }
            // Read before the commit clears them: one backspace tap can still take the last gesture
            // back, and the space with it.
            val gestures = ime.gestureUndo.snapshot()
            val tokens = comp.tokensSnapshot()
            if (ime.commit.finalizePendingWord()) {
                ime.commitMarks.spaceAt(ime.cursorExpected())
                ime.commitTracked(" ")
                inserted = true
                // Recorded after commitTracked, which clears the flag it sets.
                fromTaps = taps
                writtenAt = SystemClock.uptimeMillis()
                shiftAt = -1L
                val word = ime.lastCommit.retypeWord
                if (ime.config.backspaceUndo && word != null && gestures.isNotEmpty()) {
                    ime.gestureUndo.keep(GestureUndo.Committed(word, ime.cursorExpected(), tokens, gestures))
                }
                DecodeTrace.log { "  autospace fire" }
                ime.updateAutoShift()
                ime.barContents.refreshPredictions()
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
     * word. A bar that still has candidates keeps them: see [CommitPath.closeBufferKeepBar].
     *
     * The pause ends it, not the emptiness: a long word typed in pieces decodes to nothing in
     * between. Working decodes have a median inter-token gap of 0 ms and a p90 of 415 ms, so
     * twice the autospace delay (600 ms by default) sits above continuations and below retries.
     */
    fun scheduleStaleTimeout() {
        if (ime.editorState.privateMode || ime.config.wordEndsOnSpace) return
        cancelStaleTimeout()
        stalePending = true
        // Follows the swipe delay: the buffer this ends is a swipe buffer.
        ime.mainHandler.postDelayed(staleBufferRunnable, staleTimeoutMs(ime.config.autospaceDelayMs))
    }

    private fun cancelStaleTimeout() {
        if (stalePending) {
            ime.mainHandler.removeCallbacks(staleBufferRunnable)
            stalePending = false
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
        val p = ime.residents.predictor ?: return false
        if (base.isEmpty()) return false
        if (p.isLivePrefix(base + next)) return true
        val tail = tailAfterLastApostrophe(base)
        return tail.isNotEmpty() && p.isLivePrefix(tail + next)
    }

    /** Whether [literal] is one letter that is a word by itself in the active language. */
    private fun standaloneLetter(literal: String): Boolean =
        literal.length == 1 && StandaloneLetters.isWord(literal[0], ime.config.language)

    /**
     * Whether the whole editor token this word belongs to is a word, which lets `don't`
     * autospace although the composer only saw `t`. `isWord` folds accents and checks
     * spellings, so `perche` does not pass as `perché`.
     */
    private fun joinedTokenIsWord(): Boolean {
        val p = ime.residents.predictor ?: return false
        val before = ime.ich.textBeforeCursor(2 * KineticaConstants.MAX_WORD_LEN) ?: return false
        val token = joinedTokenForAutospace(before)
        return token.isNotEmpty() && p.isWord(token)
    }

    /** The shift press that stopped the retract clock, spent by the gesture that reads it. */
    fun takeShiftAt(): Long {
        val at = shiftAt
        shiftAt = -1L
        return at
    }

    /**
     * Takes the space back before [letter], typed at [tStart], when [retractsAutospace] says the
     * tapped word before it was not over. [shiftPressAt] is a shift press since the space, or -1;
     * after one, a learned camel-case word decides, within [shiftRetractMs].
     */
    fun retractBeforeLetter(letter: Char, tStart: Long, shiftPressAt: Long) {
        val fusedBase = wordBeforeAutospace(
            ime.ich.textBeforeCursor(KineticaConstants.MAX_WORD_LEN + 2) ?: "",
        )
        val shifted = shiftPressAt >= 0
        val elapsed = retractClockMs(SystemClock.uptimeMillis(), writtenAt, shiftPressAt)
        val window = if (shifted) shiftRetractMs(ime.config.autospaceRetractMs) else ime.config.autospaceRetractMs
        // The word this letter would rejoin, asked of the dictionary, not the clock. Read from
        // the editor, not lastLiteral, so it agrees with what reloadWordUnderCursor puts back.
        // After a shift press, of the learned spellings instead.
        val prefix = if (shifted) {
            learnedCamelPrefix(fusedBase, ime.shift.apply(letter), learnedDisplays())
        } else {
            fuseIsLivePrefix(fusedBase, letter)
        }
        val held = ime.engine.swipeHeldSince(tStart)
        val why = retractRefusal(
            fromTappedWord = fromTaps && inserted,
            tentativeLength = ime.wordState.tentativeLength,
            elapsedMs = elapsed,
            windowMs = window,
            fusedIsPrefix = prefix,
            swipeStartedFirst = held,
            shifted = shifted,
        )
        if (why == null) {
            // The letter that caused this: the reopened word must land before it.
            retract(tStart)
        } else if (fromTaps && inserted && ime.wordState.tentativeLength == 0) {
            // Once per kept space: the letter committed next clears the flags. With a shift
            // press, elapsed runs to the press.
            DecodeTrace.log {
                "  autospace keep why=$why elapsed=$elapsed window=$window " +
                    "prefix=$prefix held=$held shift=$shifted"
            }
        }
    }

    /** Every learned spelling in the resident languages, any votes: what a camel-case capital can rejoin. */
    private fun learnedDisplays(): List<String> =
        ime.residents.languages(ime.config.language).flatMap { lang -> ime.casesFor(lang).values.map { it.display } }

    /**
     * After a decode of an all-tap word, whose [literal] the editor already holds.
     *
     * Every tapped letter looks like the middle of a longer word, so an all-tap word
     * gets a timer only behind its own setting and only when its letters spell a
     * dictionary word. See autospacesTappedWord for the cost and why the space is
     * retractable.
     */
    fun armForTappedWord(literal: String) {
        val joiner =
            ime.ich.textBeforeCursor(ime.wordState.tentativeLength + 1)?.dropLast(ime.wordState.tentativeLength) ?: ""
        if (autospacesTappedWord(
                enabled = ime.config.autospaceTappedWords,
                hasSwipeToken = false,
                literal = literal,
                literalIsWord = ime.residents.predictor?.isWord(literal) == true,
                literalIsStandaloneLetter = standaloneLetter(literal),
                addressField = ime.editorState.addressField,
                // The word's own letters are already committed, so the character
                // before it sits one further back than the word itself.
                joinedToWhatPrecedes = joinsPrecedingToken(joiner),
                joinedTokenIsWord = joinedTokenIsWord(),
                joinedByApostrophe = joinedByApostrophe(joiner),
                carriesNoToken = ime.wordState.seededWithoutTokens,
            ) && !ime.engine.hasActivePointers()
        ) {
            schedule()
        }
    }

    /**
     * After a decode of a swiped word that put its lead in the editor.
     *
     * Autospace arms only once both thumbs are up; any new touch
     * cancels it via onKeyPressFeedback. What else arms it is
     * autospacesSwipedWord's decision, read here and again at the wake.
     */
    fun armForSwipedWord() {
        if (!ime.engine.hasActivePointers() &&
            autospacesSwipedWord(
                hasSwipeToken = true,
                addressField = ime.editorState.addressField,
                carriesNoToken = ime.wordState.seededWithoutTokens,
            )
        ) {
            schedule()
        }
    }

    /**
     * Arms the automatic space, on the delay belonging to the kind of word in hand.
     *
     * Swipe and tap delays are separate because their mid-word silences differ: the gap
     * between tokens runs to p99 878 ms while swiping against 569 ms while tapping. Both
     * default to 300 ms, since the middles barely differ; the sliders let a thumb find the rest.
     */
    private fun schedule() {
        if (!ime.config.autospace || ime.editorState.privateMode || ime.config.wordEndsOnSpace) {
            // Traced so a capture can say why no space came.
            DecodeTrace.log {
                "  autospace off autospace=${ime.config.autospace} wordEnds=${ime.config.wordEndsOnSpace} " +
                    "private=${ime.editorState.privateMode}"
            }
            return
        }
        cancel()
        pending = true
        val swipe = ime.composer?.hasSwipeToken() == true
        val letter = !swipe && ime.wordState.lastLiteral.length == 1
        val delay = when {
            swipe -> ime.config.autospaceDelayMs
            letter -> ime.config.autospaceSingleLetterDelayMs
            else -> ime.config.autospaceTapDelayMs
        }
        val kind = if (swipe) "swipe" else if (letter) "letter" else "tap"
        val now = SystemClock.uptimeMillis()
        val wait = autospaceWait(delay, ime.composer?.lastTokenEnd ?: -1L, now)
        DecodeTrace.log { "  autospace arm kind=$kind delay=$delay wait=$wait lit=${ime.wordState.lastLiteral}" }
        ime.mainHandler.postDelayed(timer, wait)
        if (ime.config.autospaceTimer) ime.suggestionBar?.showAutospaceTimer(now - (delay - wait), delay)
    }

    /**
     * A shift press while a tapped word's automatic space can still be taken back: in camel case
     * the capital is the word's next letter, and the press alone outlasted the window. Only the
     * space in place: a press before it lands cancels the timer at key-down, so the word goes on.
     * Traced every time, so a capture shows how long the press took.
     */
    fun stopRetractClockAtShift() {
        if (!inserted || !fromTaps) return
        val now = SystemClock.uptimeMillis()
        DecodeTrace.log { "  autospace clock src=shift elapsed=${now - writtenAt} first=${shiftAt < 0}" }
        if (shiftAt < 0) shiftAt = now
    }

    fun cancel() {
        cancelStaleTimeout()
        if (pending) {
            ime.mainHandler.removeCallbacks(timer)
            pending = false
        }
        ime.suggestionBar?.clearAutospaceTimer()
    }

    /**
     * Removes an automatically inserted space when [text] is punctuation that hugs the word
     * before it. No-op for a space the user typed, and once anything else has been committed:
     * every other path through commitTracked clears the flag.
     */
    fun eatBefore(text: String) {
        if (!inserted || !hugsPreviousWord(text, ime.config.noSpaceBefore)) return
        if (ime.ich.textBeforeCursor(1)?.toString() != " ") return
        ime.ich.deleteBeforeCursor(1)
        ime.expectAfter(1, 0)
        inserted = false
        fromTaps = false
        // Punctuation against the word says it was over.
        ime.commitMarks.unspaceAt(ime.cursorExpected())
        DecodeTrace.log { "  autospace eat punct=$text" }
    }

    /**
     * Removes an automatic space that followed a tapped word, and reopens that word.
     *
     * The edit [eatBefore] makes for punctuation, then [WordReopen.reloadWordUnderCursor]
     * re-seeds the word from the editor, as after a deleted space. So `car`, pause, space,
     * `pet` reaches the composer as one word, as tap anchors like the taps that wrote it.
     */
    fun retract(beforeTime: Long) {
        if (ime.ich.textBeforeCursor(1)?.toString() != " ") return
        ime.ich.deleteBeforeCursor(1)
        ime.expectAfter(1, 0)
        inserted = false
        fromTaps = false
        DecodeTrace.log { "  autospace retract" }
        ime.reopen.reloadWordUnderCursor(beforeTime)
    }

    /**
     * Forgets the automatic space this keyboard put before the cursor, because a backspace
     * or a slide has just deleted it.
     *
     * Only the two flags that describe that space. Nothing records what the user meant by
     * deleting it: such a flag outlives its word and silences correct spaces on later ones.
     */
    fun forget() {
        inserted = false
        fromTaps = false
    }

    /**
     * The [eatBefore] edit with no punctuation to judge, for the spaceless zone.
     *
     * Separate because the reasons differ: punctuation eats a space it would look wrong
     * beside, and this eats one the user has just asked not to have.
     */
    fun eatSpaceless() {
        if (!inserted) return
        if (ime.ich.textBeforeCursor(1)?.toString() != " ") return
        ime.ich.deleteBeforeCursor(1)
        ime.expectAfter(1, 0)
        inserted = false
        fromTaps = false
        ime.commitMarks.unspaceAt(ime.cursorExpected())
        DecodeTrace.log { "  autospace eat spaceless" }
    }
}
