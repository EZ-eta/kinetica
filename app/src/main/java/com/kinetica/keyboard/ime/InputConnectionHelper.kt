package com.kinetica.keyboard.ime

import android.view.inputmethod.InputConnection

/**
 * All editor mutations go through here. The InputConnection is re-fetched per
 * call and every operation no-ops on null: FLAG_SECURE windows, multi-window
 * focus loss, and rotation races must never crash the service.
 */
class InputConnectionHelper(private val connection: () -> InputConnection?) {

    fun commitText(text: CharSequence): Boolean =
        connection()?.commitText(text, 1) ?: false

    fun deleteBeforeCursor(count: Int): Boolean =
        connection()?.deleteSurroundingText(count, 0) ?: false

    fun textBeforeCursor(count: Int): CharSequence? =
        connection()?.getTextBeforeCursor(count, 0)

    fun textAfterCursor(count: Int): CharSequence? =
        connection()?.getTextAfterCursor(count, 0)

    fun selectedText(): CharSequence? = connection()?.getSelectedText(0)

    /**
     * Batch-edit deletion of [count] characters ending at absolute offset [end],
     * used to remove a selection.
     *
     * [InputConnection.deleteSurroundingText] is specified relative to the
     * selection BOUNDARIES and leaves the selection itself in place, so it can
     * never delete one; collapsing the cursor to [end] first is what turns it
     * into an ordinary backward delete. One batch edit, so the editor reports a
     * single selection change like every other mutation here.
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

    fun performEditorAction(actionId: Int): Boolean =
        connection()?.performEditorAction(actionId) ?: false

    /** Context-menu editor actions (android.R.id.paste / selectAll / ...). */
    fun performContextMenuAction(id: Int): Boolean =
        connection()?.performContextMenuAction(id) ?: false

    fun cursorCapsMode(inputType: Int): Int =
        connection()?.getCursorCapsMode(inputType) ?: 0
}
