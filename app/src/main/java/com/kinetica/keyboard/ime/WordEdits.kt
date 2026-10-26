package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.DecodeTrace
import com.kinetica.keyboard.engine.KineticaConstants
import com.kinetica.keyboard.keys.ShiftState
import com.kinetica.keyboard.keys.WordCase
import com.kinetica.keyboard.layout.LayoutMutations

/**
 * Edits to a word already written, from shift's popup, a shortcut or the retype button: re-casing the
 * word in hand, the one just finished, the one under the cursor or a selection, and the retype that
 * deletes a wrong word and offers its alternatives.
 */
internal class WordEdits(private val ime: KineticaIME) {

    // The word the last retype rejected, armed for one decode. Behind its own setting, and
    // it demotes, not drops: the wanted word was among the alternates in only 5 of 12 chains,
    // and a retype aimed at a space must still reach the word it had.
    var retypeRejected: String? = null

    /**
     * Deletes the word in progress, or else the word just committed with what the keyboard put
     * after it, or else the letters before the cursor, and leaves the cursor in place so the
     * word can be gestured again.
     *
     * The commit case is the common one: autospace commits fast, so a wrong word is usually
     * finished by the time it is noticed. `retypeSpan` decides which, purely.
     */
    fun retypeCurrentWord() {
        ime.autospace.cancel()
        // The bar's button, which no key touch precedes: the offer goes first, so the retype
        // sees the cursor as it would have without one.
        if (ime.wordState.midWordOffer != null) ime.commit.abandonWord()
        // The button is the user saying the last commit was wrong, the only correctness
        // signal this keyboard gets. A pair learned from that commit comes back out.
        ime.learner.unlearnLastPair()
        ime.forgetCommitMarks()
        // The same guard the reload uses: with a letter after the cursor the user is parked
        // inside a word, and nothing here should guess which half they meant.
        val after = ime.ich.textAfterCursor(1)
        val midWord = after != null && after.isNotEmpty() && after[0].isLetter()
        val run = if (midWord) {
            ""
        } else {
            trailingLetterRun(ime.ich.textBeforeCursor(KineticaConstants.MAX_WORD_LEN + 1) ?: "")
        }
        // Same read as the recase and for the same reason: a remembered length deleted
        // into the word instead of past it.
        val committed = ime.lastCommit.retypeWord
        val offer = if (committed == null) emptyList() else ime.lastCommit.offerAfterRetype(committed, KineticaConstants.TOP_K)
        val offerLanguages = ime.lastCommit.languages
        val committedSpan = if (committed == null) {
            -1
        } else {
            val before = ime.ich.textBeforeCursor(committed.length + KineticaIME.COMMIT_TAIL_CHARS) ?: ""
            commitSpan(before, committed, KineticaIME.COMMIT_TAIL_CHARS)
        }
        val span = retypeSpan(ime.wordState.tentativeLength, committedSpan, run)
        val src = retypeSource(ime.wordState.tentativeLength, committedSpan)
        DecodeTrace.log {
            "  retype span=$span src=$src" + (if (midWord) " midword" else "")
        }
        // ...and the same for the word. A rejected commit that keeps its weight unit makes the
        // word the user fights stronger with each attempt: `biologa` climbed pb 1.10 -> 1.24
        // while retyped, against `biologia`, thirteen times more frequent.
        // Only the commit case, 37 of 41 retypes in that capture: the other cases have no
        // committed word, and 85% of retypes reject the word itself.
        if (src == "commit") committed?.let { ime.learner.unlearnWord(it) }
        // Armed only for a commit retype: the tentative and cursor cases have no committed
        // word the user can be said to have rejected.
        retypeRejected = if (ime.config.retypeAvoidsRejected && src == "commit") {
            committed
        } else {
            null
        }
        if (span <= 0) {
            ime.commit.abandonWord()
            return
        }
        ime.ich.deleteBeforeCursor(span)
        ime.expectAfter(span, 0)
        if (src == "commit") ime.barContents.recentWords.dropNewest()
        ime.commit.abandonWord()
        // The word is gone, so the space that followed it is not the keyboard's any more
        // and nothing is left for punctuation to eat or a letter to retract.
        ime.autospace.forget()
        ime.updateAutoShift()
        if (src == "commit" && offer.isNotEmpty() && !ime.editorState.privateMode) {
            ime.barContents.showRetypeOffer(offer, offerLanguages)
        }
    }

    /** Shift's popup cell, by its place in [LayoutMutations.SHIFT_CASE_CELLS]. */
    fun recaseWordInHand(cell: String) {
        val idx = LayoutMutations.SHIFT_CASE_CELLS.indexOf(cell)
        recaseWordInHand(WordCase.entries.getOrNull(idx) ?: return)
    }

    /**
     * Re-cases the word in hand, or the one just finished, from shift's popup or a shortcut.
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
    fun recaseWordInHand(want: WordCase) {
        if (ime.selectionLength() != 0 && recaseSelection(want)) return
        if (ime.wordState.tentativeLength == 0 && recaseAroundCursor(want)) return
        // The guard the retype and the reload share. Inside a word was answered above; a
        // letter after the cursor here starts the next word.
        val after = ime.ich.textAfterCursor(1)
        if (after != null && after.isNotEmpty() && after[0].isLetter()) return

        // The last commit's span is read from the editor, not a remembered length: a stale
        // length ate the word it meant to re-case.
        val committed = ime.lastCommit.retypeWord
        val before = if (committed == null) {
            ""
        } else {
            ime.ich.textBeforeCursor(committed.length + KineticaIME.COMMIT_TAIL_CHARS) ?: ""
        }
        val span = if (committed == null) -1 else commitSpan(before, committed, KineticaIME.COMMIT_TAIL_CHARS)
        DecodeTrace.log {
            "  recase to=$want src=${retypeSource(ime.wordState.tentativeLength, span)} span=$span"
        }
        when {
            ime.wordState.tentativeLength > 0 -> {
                val recased = want.applyTo(ime.wordState.tentativeWord)
                if (recased == ime.wordState.tentativeWord) return
                ime.replaceTentative(recased)
                // Written as well as the text, or the next decode's displayWord re-applies
                // the old wordShift over the top and the re-case silently undoes itself.
                ime.wordState.wordShift = when (want) {
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
                ime.ich.replaceBeforeCursor(span, recased + tail)
                ime.expectAfter(span, recased.length + tail.length, rewritesWord = true)
                ime.lastCommit.onReplaced(recased)
            }
            else -> {
                val run = trailingLetterRun(
                    ime.ich.textBeforeCursor(KineticaConstants.MAX_WORD_LEN + 1) ?: "",
                )
                if (run.isEmpty()) return
                val recased = want.applyTo(run)
                if (recased == run) return
                ime.ich.replaceBeforeCursor(run.length, recased)
                ime.expectAfter(run.length, recased.length, rewritesWord = true)
            }
        }
        ime.updateAutoShift()
    }

    /**
     * Re-cases the selected text and leaves it selected. True when a selection was there
     * to act on, changed or not.
     */
    private fun recaseSelection(want: WordCase): Boolean {
        val text = ime.ich.selectedText()?.toString() ?: return false
        if (text.isEmpty()) return false
        if (text.length > SELECTION_RECASE_MAX_CHARS) {
            DecodeTrace.log { "  recase selection refused len=${text.length}" }
            return true
        }
        val recased = want.applyToText(text)
        DecodeTrace.log { "  recase selection to=$want len=${text.length}" }
        if (recased == text) return true
        val at = minOf(ime.selStart, ime.selEnd)
        ime.ich.replaceSelection(at, recased)
        ime.expectAt(at, at + recased.length)
        ime.forgetCommitMarks()
        return true
    }

    /**
     * Re-cases the whole word the cursor is parked inside and leaves the cursor where it was.
     * True when the cursor was inside a word, changed or not, so the caller never
     * falls through and re-cases the half before the cursor.
     */
    private fun recaseAroundCursor(want: WordCase): Boolean {
        val read = KineticaConstants.MAX_WORD_LEN + 1
        val before = ime.ich.textBeforeCursor(read) ?: return false
        val after = ime.ich.textAfterCursor(read) ?: return false
        if (!cursorInsideWord(before, after)) return false
        // A selection is a range the user chose; re-casing that is its own request.
        if (ime.selectionLength() > 0) return true
        val word = wordAroundCursor(before, after, KineticaConstants.MAX_WORD_LEN)
        DecodeTrace.log {
            val span = word?.let { "${it.head.length}+${it.tail.length}" } ?: "refused"
            "  recase to=$want src=inside span=$span"
        }
        if (word == null) return true
        val (head, tail) = want.applyAround(word.head, word.tail)
        if (head == word.head && tail == word.tail) return true
        ime.ich.replaceAroundCursor(word.head.length, word.tail.length, head, tail)
        ime.expectAfter(word.head.length, head.length, rewritesWord = true)
        ime.updateAutoShift()
        return true
    }

    private companion object {
        /** Longest selection the shift popup re-cases: a long document is not one tap's work. */
        const val SELECTION_RECASE_MAX_CHARS = 5000
    }
}
