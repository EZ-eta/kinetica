package com.kinetica.keyboard.ime

import android.os.SystemClock
import com.kinetica.keyboard.engine.DecodeTrace
import com.kinetica.keyboard.engine.KineticaConstants

/**
 * The word under the cursor taken up again: reloaded as tap anchors when the cursor ends it, so typing
 * goes on and the bar offers its neighbours, or offered whole when the cursor sits inside it, with the
 * pick that replaces it. A reload at a recent commit's place reopens that commit (CommitMarks).
 */
internal class WordReopen(private val ime: KineticaIME) {

    /** Whether a resident lexicon holds [word], case aside: English `don't` with Italian active. */
    private fun isResidentWord(word: String): Boolean =
        ime.residentLanguages().any { ime.predictorFor(it)?.isWordIgnoringCase(word) == true }

    /**
     * If the cursor now sits at the end of a word (letters immediately before
     * it, none after, no trailing delimiter), reload that word into the
     * composer as synthetic tap anchors at the key centers. Letters are
     * accent-folded onto their base keys, so an Italian "perch" continues to
     * "perché"; apostrophes are skipped (the trie search re-inserts
     * dictionary apostrophes for free).
     *
     * [reopens]: the word may be one of the last commits taken up again, by its place
     * ([CommitMarks]); a deleted selection is not one. See takeBack.
     */
    fun reloadWordUnderCursor(
        beforeTime: Long = SystemClock.uptimeMillis(),
        reopens: Boolean = true,
    ) {
        if (ime.editorState.privateMode) return
        // Peck mode has no prediction to re-seed; reloading would resurrect
        // the suggestion pipeline through the backspace path.
        if (ime.config.peckMode) return
        val comp = ime.composer ?: return
        val g = ime.currentGeometry ?: return
        val after = ime.ich.textAfterCursor(1)
        if (after != null && after.isNotEmpty() && after[0].isLetter()) return
        val before = ime.ich.textBeforeCursor(KineticaConstants.MAX_WORD_LEN + 1) ?: return
        val fragment = reloadFragment(trailingLetterRun(before), ::isResidentWord)
        if (fragment.isEmpty() || fragment.length > KineticaConstants.MAX_WORD_LEN) return
        val taps = tapAnchors(fragment, g, beforeTime) ?: return
        ime.wordState.wordShift = shiftOf(fragment)
        ime.wordState.tentativeLength = fragment.length
        ime.wordState.tentativeWord = fragment
        ime.wordState.reloadedWord = fragment
        ime.gestureUndo.clear()
        val mark = if (reopens) ime.commitMarks.reopened(ime.cursorExpected() - fragment.length, fragment) else null
        if (mark != null) {
            // Not the previous word of its own decode, nor of the pair its commit learns.
            if (comp.reopenLastCommit(mark.word)) ime.wordState.reopenedContext = mark.word
            ime.wordState.reopenedLesson = mark.lesson?.takeIf { armsLesson(mark, fragment) }
            DecodeTrace.log { "  reopen commit word=${mark.word} left=${fragment.length} armed=${ime.wordState.reopenedLesson != null}" }
        }
        // Whatever the timer was armed for, it is not what the composer holds now.
        ime.autospace.cancel()
        comp.seed(taps)
        // After seed, not before: the decode runs on decodeExecutor and its callback
        // cannot reach onCandidates until this method returns, so the flag is up before
        // either arm site reads it.
        ime.wordState.seededWithoutTokens = true
        // In the same message as the abandon that emptied the bar, so no frame draws the
        // shortcut row between the delete and the decode.
        ime.barContents.updateBarWordPending()
    }

    /**
     * Fills the bar for the word the cursor is parked inside, as the end-of-word reload does,
     * without reopening it. The word is seeded as taps, so the alternatives are its spelling
     * neighbours, through the ordinary candidates path.
     *
     * True when the cursor was inside a word at all, offered or not, so the caller never
     * reloads the half before the cursor.
     */
    fun offerMidWord(): Boolean {
        val read = KineticaConstants.MAX_WORD_LEN + 1
        val before = ime.ich.textBeforeCursor(read) ?: return false
        val after = ime.ich.textAfterCursor(read) ?: return false
        if (!cursorInsideWord(before, after)) return false
        if (ime.editorState.privateMode || ime.config.peckMode) return true
        val comp = ime.composer ?: return true
        val g = ime.currentGeometry ?: return true
        val around = wordAroundCursor(before, after, KineticaConstants.MAX_WORD_LEN) ?: return true
        // An elision is offered as tapping wrote it, the piece after its apostrophe, as the reload
        // reopens it: whole, `l'altro` would seed `laltro`.
        val word = midWordPiece(around, ::isResidentWord) ?: return true
        val whole = word.head + word.tail
        val taps = tapAnchors(whole, g, SystemClock.uptimeMillis()) ?: return true
        ime.wordState.wordShift = shiftOf(whole)
        ime.wordState.midWordOffer = word
        // A word this keyboard wrote here offers what its bar had then.
        ime.wordState.midWordHistory = ime.commitHistory.at(ime.selStart - word.head.length, whole)
        ime.autospace.cancel()
        DecodeTrace.log {
            "  midword offer head=${word.head} tail=${word.tail} from=${if (ime.wordState.midWordHistory != null) "history" else "letters"}"
        }
        // The decode follows the word before this one, not the commits made since, which can
        // include the word itself.
        comp.reset()
        ime.gestureUndo.clear()
        wordBefore(before, word.head)?.let { comp.anchorContext(it) }
        comp.seed(taps)
        ime.wordState.seededWithoutTokens = true
        ime.barContents.updateBarWordPending()
        return true
    }

    /**
     * Puts [word] in place of the whole word the offer was made for, and the cursor after it.
     * Both halves are read again first: a pick against text that changed is refused, never
     * guessed.
     */
    fun pickMidWord(offer: WordAround, word: String) {
        val read = KineticaConstants.MAX_WORD_LEN + 1
        val before = ime.ich.textBeforeCursor(read) ?: ""
        val after = ime.ich.textAfterCursor(read) ?: ""
        val old = offer.head + offer.tail
        val history = ime.wordState.midWordHistory
        ime.commit.abandonWord()
        if (!midWordStillThere(before, after, offer, ::isResidentWord)) {
            DecodeTrace.log { "  midword refused word=$word old=$old" }
            return
        }
        DecodeTrace.log { "  midword pick word=$word old=$old" }
        val start = ime.cursorExpected() - offer.head.length
        if (!ime.commitHistory.replaced(start, old, word)) ime.commitHistory.onEdit(start + old.length, old.length, word.length)
        ime.forgetCommitMarks()
        ime.ich.replaceAroundCursor(offer.head.length, offer.tail.length, word, "")
        ime.expectAfter(offer.head.length, word.length, rewritesWord = true)
        // The next word follows the one picked.
        ime.composer?.anchorContext(word)
        // Learned as a pick is, the old word left alone: nothing says this keyboard wrote it.
        if (!word.equals(old, ignoreCase = true)) {
            val from = history?.languages?.get(word.lowercase()) ?: ime.languageOf(word)
            ime.learner.learnWord(word, lang = ime.sharedFiling.languageFor(ime.heldBy(word), from))
        }
        ime.updateAutoShift()
        ime.barContents.refreshPredictions()
    }
}
