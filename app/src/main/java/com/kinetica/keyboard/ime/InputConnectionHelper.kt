package com.kinetica.keyboard.ime

import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.InputConnection

/**
 * All editor mutations go through here. The InputConnection is re-fetched per
 * call and every operation no-ops on null: FLAG_SECURE windows, multi-window
 * focus loss, and rotation races must never crash the service.
 */
class InputConnectionHelper(private val connection: () -> InputConnection?) {

    fun commitText(text: CharSequence): Boolean =
        connection()?.commitText(text, 1) ?: false

    /** One key pressed and released with [metaState] held, as a hardware keyboard sends it. */
    fun sendKey(keyCode: Int, metaState: Int): Boolean {
        val ic = connection() ?: return false
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState))
        return true
    }

    fun deleteBeforeCursor(count: Int): Boolean =
        connection()?.deleteSurroundingText(count, 0) ?: false

    fun textBeforeCursor(count: Int): CharSequence? =
        connection()?.getTextBeforeCursor(count, 0)

    fun textAfterCursor(count: Int): CharSequence? =
        connection()?.getTextAfterCursor(count, 0)

    fun selectedText(): CharSequence? = connection()?.getSelectedText(0)

    /**
     * Moves the selection to [start]..[end] in absolute offsets, so a staged backspace span
     * shows as a highlight in the editor; [start] == [end] collapses it back to a cursor.
     */
    fun setSelection(start: Int, end: Int): Boolean =
        connection()?.setSelection(start, end) ?: false

    /**
     * Batch-edit deletion of [count] characters ending at absolute offset [end],
     * used to remove a selection.
     *
     * [InputConnection.deleteSurroundingText] works around the selection's boundaries and
     * leaves the selection in place, so the cursor collapses to [end] first and the call
     * becomes a backward delete. One batch edit, so the editor reports one selection change.
     */
    fun deleteEndingAt(end: Int, count: Int): Boolean {
        if (count <= 0) return false
        val ic = connection() ?: return false
        ic.beginBatchEdit()
        ic.setSelection(end, end)
        ic.deleteSurroundingText(count, 0)
        ic.endBatchEdit()
        return true
    }

    /** Batch-edit replacement of the last [deleteCount] chars with [text]. */
    fun replaceBeforeCursor(deleteCount: Int, text: CharSequence): Boolean {
        val ic = connection() ?: return false
        ic.beginBatchEdit()
        if (deleteCount > 0) ic.deleteSurroundingText(deleteCount, 0)
        ic.commitText(text, 1)
        ic.endBatchEdit()
        return true
    }

    /**
     * Batch-edit replacement of [beforeCount] chars before the cursor and [afterCount] after
     * it with [head] and [tail], leaving the cursor between the two.
     *
     * [tail] is committed with newCursorPosition 0, the start of the inserted text, so the
     * cursor needs no absolute offset and no cached selection can be stale.
     */
    fun replaceAroundCursor(
        beforeCount: Int,
        afterCount: Int,
        head: CharSequence,
        tail: CharSequence,
    ): Boolean {
        val ic = connection() ?: return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(beforeCount, afterCount)
        ic.commitText(head, 1)
        // A pick mid-word writes no tail: the cursor ends after the word.
        if (tail.isNotEmpty()) ic.commitText(tail, 0)
        ic.endBatchEdit()
        return true
    }

    /**
     * Replaces the selection with [text] and selects the result again from [start], in one batch
     * edit, so the editor reports a single selection change.
     */
    fun replaceSelection(start: Int, text: CharSequence): Boolean {
        val ic = connection() ?: return false
        ic.beginBatchEdit()
        ic.commitText(text, 1)
        ic.setSelection(start, start + text.length)
        ic.endBatchEdit()
        return true
    }

    fun performEditorAction(actionId: Int): Boolean =
        connection()?.performEditorAction(actionId) ?: false

    /** Context-menu editor actions (android.R.id.paste / selectAll / ...). */
    fun performContextMenuAction(id: Int): Boolean =
        connection()?.performContextMenuAction(id) ?: false
}
