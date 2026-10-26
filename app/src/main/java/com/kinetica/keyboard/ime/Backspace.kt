package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.DecodeTrace
import com.kinetica.keyboard.engine.WordComposer
import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.keys.DeleteSpan
import com.kinetica.keyboard.keys.ShiftState

/**
 * The backspace key and its slide.
 *
 * A tap first takes back what the keyboard just did, in order: an autocorrect, the last gesture of
 * the word in hand, the word the automatic space committed with its space. Otherwise it deletes and
 * reopens the word before the cursor. The slide stages a span, shown struck through, and deletes
 * it on lift.
 */
internal class Backspace(private val ime: KineticaIME) {

    // The last autocorrect, until anything else is typed: see undoAutocorrect.
    var autocorrectUndo: AutocorrectUndo? = null

    // The last commit while backspace eats into it, for the trace only. Kept apart
    // from lastCommit because the first backspace's abandonWord clears that.
    var target: String? = null

    /**
     * Takes the word's last tap or swipe back (#38): the editor shows the word as it stood before
     * it and the composer decodes the tokens before it again, so the bar comes back as it was.
     * Until the next gesture the word stays open: no automatic space arms on the decode.
     */
    private fun undoLastGesture(): Boolean {
        val comp = ime.composer ?: return false
        val state = ime.gestureUndo.pop() ?: return false
        val from = comp.tokenCount
        if (state.tokens >= from) {
            ime.gestureUndo.clear()
            return false
        }
        DecodeTrace.log { "  backspace undo tokens=$from->${state.tokens} text=${state.text}" }
        backToGesture(comp, state, kept = null)
        return true
    }

    /**
     * Takes back the last tap or swipe of the word the automatic space committed, and the
     * space: the commit was not the word, so what it taught goes back and it leaves the context.
     * The word then stands as before that gesture, and the next tap goes on as inside a word.
     */
    private fun undoSpacedGesture(): Boolean {
        val comp = ime.composer ?: return false
        val kept = ime.gestureUndo.takeCommitted() ?: return false
        val before = ime.ich.textBeforeCursor(kept.word.length + 1) ?: return false
        if (!committedStillThere(before, ime.cursorExpected(), kept)) return false
        val state = kept.states.last()
        ime.ich.deleteBeforeCursor(1)
        ime.expectAfter(1, 0)
        ime.autospace.forget()
        ime.commitMarks.newest?.takeIf { it.end == ime.cursorExpected() && it.word == kept.word.lowercase() }?.let { mark ->
            mark.lesson?.let { ime.learner.takeBack(it) }
            ime.commitMarks.drop(mark)
        }
        // Its answer is not needed: a userdict reload since the commit starts an empty context.
        comp.reopenLastCommit(kept.word)
        ime.barContents.recentWords.dropNewest()
        ime.barContents.clearCorrection()
        ime.wordState.tentativeLength = kept.word.length
        ime.wordState.tentativeWord = kept.word
        DecodeTrace.log { "  backspace undo tokens=${kept.tokens.size}->${state.tokens} text=${state.text} space=true" }
        ime.gestureUndo.restore(kept.states.dropLast(1))
        backToGesture(comp, state, kept.tokens)
        return true
    }

    /**
     * The word as it stood before one gesture: its text and flags, and the tokens before that
     * gesture decoded again, cut from the composer's own or from the [kept] ones put back.
     */
    private fun backToGesture(comp: WordComposer, state: GestureUndo.State, kept: List<InputToken>?) {
        ime.replaceTentative(state.text)
        if (state.tokens == 0) {
            ime.commit.abandonWord()
        } else {
            ime.wordState.wordShift = state.wordShift
            ime.wordState.swipeDecodeEmpty = state.decodeEmpty
            ime.wordState.seededWithoutTokens = true
            ime.wordState.lastCandidates = emptyList()
            ime.wordState.lastTentative = null
            ime.wordState.lastLiteral = ""
            ime.suggestionBar?.clearSuggestions()
            if (kept == null) comp.truncate(state.tokens) else comp.seed(kept.take(state.tokens))
            ime.barContents.updateBarWordPending()
        }
        // A capital the undone letter spent comes back with it.
        if (state.shift == ShiftState.State.SHIFT && ime.shift.state == ShiftState.State.NONE) {
            ime.shift.autoShift(true)
            ime.keyboardView?.setShiftUppercase(ime.shift.isShifted)
        }
    }

    /**
     * Puts back the letters an autocorrect replaced, when the backspace comes right after it: the
     * corrected word and at most the space or mark that followed. The corrected word's +1 is
     * taken back, as a retype does.
     */
    private fun undoAutocorrect(): Boolean {
        val undo = autocorrectUndo ?: return false
        autocorrectUndo = null
        if (ime.selectionLength() != 0) return false
        val after = ime.cursorExpected() - undo.end
        val before = ime.ich.textBeforeCursor(undo.corrected.length + 1) ?: return false
        val span = autocorrectUndoSpan(before, undo.corrected, after)
        if (span < 0) return false
        ime.commit.abandonWord()
        ime.ich.replaceBeforeCursor(span, undo.typed)
        ime.expectAfter(span, undo.typed.length)
        ime.autospace.inserted = false
        ime.forgetCommitMarks()
        ime.learner.unlearnWord(undo.corrected)
        // Backspace taking a learned capital off the letters typed says the lowercase was meant.
        if (undo.corrected != undo.typed && undo.corrected.equals(undo.typed, ignoreCase = true)) {
            ime.learner.voteCase(undo.typed, ime.languageOf(undo.typed), CaseVote(-1, null))
        }
        DecodeTrace.log { "  autocorrect undo word=${undo.corrected} typed=${undo.typed}" }
        ime.updateAutoShift()
        return true
    }

    /**
     * Traces how often a commit is backspaced away and how far, to price taking a word's weight
     * back on backspace as a retype does: `left=0` is the whole word gone.
     */
    private fun traceInto(word: String, src: String) {
        val before = ime.ich.textBeforeCursor(word.length + KineticaIME.COMMIT_TAIL_CHARS) ?: ""
        val left = backspaceLeft(before, word, KineticaIME.COMMIT_TAIL_CHARS)
        target = if (left == null || left == 0) null else word
        if (left != null && left >= 0) DecodeTrace.log { "  backspace into commit word=$word left=$left src=$src" }
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

    fun stage(units: Int, granularity: DeleteSpan.Granularity) {
        if (units <= 0) {
            if (stagedDeletionLength > 0) clearStaged(restoreCursor = true)
            return
        }
        ime.autospace.cancel()
        // One tick per unit: BackspaceController reports only a changed count, so this fires
        // on each threshold crossing and never while the finger sits still. Retractions tick
        // too, or a slide deletes less than intended unnoticed. Honours the vibration setting.
        ime.views.vibrateForKeyPress()
        if (ime.composer?.hasPendingWord == true || ime.wordState.tentativeLength > 0) ime.commit.abandonWord()

        // Snapshot the editor once, when staging starts. From the first highlight on, the
        // live selection is one this method made, and reading it as the user's would grow the
        // span by a unit per crossing, retractions included.
        if (stagedDeletionLength == 0) {
            stageAnchor = ime.selEnd
            // A selection the user had when the slide began is the first staged unit
            // (DeleteSpan.staged); its text is captured for the chip while still readable.
            stageSelected = ime.selectionLength()
            stageBefore = ime.ich.textBeforeCursor(STAGE_FETCH_CHARS)?.toString() ?: ""
            stageSelectedText = if (stageSelected > 0) {
                ime.ich.selectedText()?.toString()?.takeIf { it.length == stageSelected }
                    ?: "\u00b7".repeat(stageSelected)
            } else {
                ""
            }
        }
        val before = stageBefore
        val span = DeleteSpan.staged(stageSelected, before, units, granularity)
        stagedDeletionLength = span
        val tail = (span - stageSelected).coerceIn(0, before.length)
        stagedDeletion =
            before.substring(before.length - tail) + stageSelectedText
        // Highlight what would go in the text itself. The chip stays, because the text may
        // have scrolled out of view or be a password field. Presentation only: the span
        // deleted on lift is the same either way.
        if (stageAnchor >= span) {
            ime.ich.setSelection(stageAnchor - span, stageAnchor)
            ime.expectAt(stageAnchor - span, stageAnchor)
        }
        ime.keyboardView?.setDeletePreview(
            when {
                span <= 0 -> null
                // Never echo password characters into the preview chip.
                ime.editorState.privateMode -> "\u2022".repeat(span.coerceAtMost(24))
                else -> stagedDeletion
            },
        )
    }

    /** Drops the staged span, optionally putting the cursor back where it was. */
    private fun clearStaged(restoreCursor: Boolean) {
        if (restoreCursor && stageAnchor >= 0) {
            ime.ich.setSelection(stageAnchor, stageAnchor)
            ime.expectAt(stageAnchor)
        }
        stagedDeletion = ""
        stagedDeletionLength = 0
        stageAnchor = -1
        stageSelected = 0
        stageBefore = ""
        stageSelectedText = ""
        ime.keyboardView?.setDeletePreview(null)
    }

    fun commitStaged() {
        val span = stagedDeletionLength
        val anchor = stageAnchor
        val staged = stagedDeletion
        // No cursor restore here: the span is about to go, and collapsing the selection
        // first would make the delete flicker.
        clearStaged(restoreCursor = false)
        if (span <= 0) return
        // The span is highlighted by now, so delete back from the anchor, or relative to the
        // cursor when no anchor was captured.
        // Whether the span holds the keyboard's own space is decided before the delete, as
        // onBackspace does: afterwards the evidence is gone.
        val deletedAutospace = ime.autospace.inserted && staged.endsWith(" ")
        val eating = if (DecodeTrace.enabled) ime.lastCommit.retypeWord ?: target else null
        if (anchor >= span) {
            ime.ich.deleteEndingAt(anchor, span)
            ime.expectAt(anchor - span)
            ime.forgetCommitMarks()
        } else {
            ime.ich.deleteBeforeCursor(span)
            ime.expectAfter(span, 0)
        }
        if (eating != null) traceInto(eating, "slide")
        ime.commit.abandonWord()
        if (deletedAutospace) {
            ime.autospace.forget()
            DecodeTrace.log { "  autospace refuse src=slide" }
        }
        ime.updateAutoShift()
    }

    /** [tap]: a tap on the key, which may take a gesture back; a repeat or an action may not. */
    fun onKey(tap: Boolean = false) {
        if (ime.commands.consumeCtrl("backspace")) return
        ime.autospace.cancel()
        if (undoAutocorrect()) return
        if (undoesGesture(tap, ime.config.backspaceUndo, ime.wordState.tentativeLength, ime.selectionLength(), ime.gestureUndo.size) &&
            undoLastGesture()
        ) {
            return
        }
        if (undoesSpacedGesture(tap, ime.config.backspaceUndo, ime.wordState.tentativeLength, ime.selectionLength(), ime.gestureUndo.committed != null) &&
            undoSpacedGesture()
        ) {
            return
        }
        val eating = if (DecodeTrace.enabled) ime.lastCommit.retypeWord ?: target else null
        // Whether this delete is aimed at the keyboard's own space, decided before the
        // deletion because afterwards the evidence is gone. Deleting the space itself makes
        // the flags describing it stale; deleting a letter says nothing about it.
        val deletedAutospace = ime.autospace.inserted && ime.ich.textBeforeCursor(1)?.toString() == " "
        // Selected text is what backspace deletes, and only the whole of it: the
        // standard editing contract, and the one case where deleting a single
        // character would destroy text the user did not point at.
        val selected = ime.selectionLength()
        val span = if (ime.config.tidySpaces && selected == 0) {
            backspaceSpan(ime.ich.textBeforeCursor(SPACE_RUN_READ_CHARS) ?: "")
        } else {
            1
        }
        if (selected > 0) {
            ime.ich.deleteEndingAt(ime.selEnd, selected)
            ime.expectAt(ime.selEnd - selected)
            ime.forgetCommitMarks()
        } else {
            ime.ich.deleteBeforeCursor(span)
            ime.expectAfter(span, 0)
        }
        if (span > 1) DecodeTrace.log { "  backspace collapsed spaces=${span + 1}" }
        if (eating != null) traceInto(eating, "key")
        // Editing inside a decoded word invalidates gesture tracking; the
        // remaining text becomes plain committed text, then reloads as exact
        // anchors so continued typing corrects the word instead of starting a
        // disconnected fragment.
        ime.commit.abandonWord()
        if (deletedAutospace && selected == 0) {
            ime.autospace.forget()
            DecodeTrace.log { "  autospace refuse src=backspace" }
        }
        ime.reopen.reloadWordUnderCursor(reopens = selected == 0)
    }

    private companion object {
        // Backspace slide: how much text to fetch for word-span staging.
        const val STAGE_FETCH_CHARS = 256

        /** Spaces read back for a backspace to collapse; a longer run collapses in steps. */
        const val SPACE_RUN_READ_CHARS = 32
    }
}

/** The last autocorrect, until anything else is typed: a backspace puts the letters back. */
internal class AutocorrectUndo(val typed: String, val corrected: String, val end: Int)
