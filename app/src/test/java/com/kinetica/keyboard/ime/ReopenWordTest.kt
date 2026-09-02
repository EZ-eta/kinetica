package com.kinetica.keyboard.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape half of reopening a word when the cursor is moved into it.
 *
 * Whether a word is actually there is reloadWordUnderCursor's question and it already
 * answers it; what is decided here is only whether this selection change is the kind
 * that could reopen one. It is pure for the same reason hugsPreviousWord and
 * startsNewSentence are: the service around it has no JVM reach at all.
 */
class ReopenWordTest {

    @Test
    fun aCollapsedCursorAfterSomeTextCanReopen() {
        assertTrue(reopensWordUnderCursor(selectionLength = 0, selStart = 5, selEnd = 5))
        assertTrue(reopensWordUnderCursor(selectionLength = 0, selStart = 1, selEnd = 1))
    }

    @Test
    fun aSelectionIsNeverAReopen() {
        // The user is acting on a range, not appending to a word. Reopening here would
        // seed a composer whose text the next edit is about to replace.
        assertFalse(reopensWordUnderCursor(selectionLength = 4, selStart = 2, selEnd = 6))
    }

    @Test
    fun theStartOfTheFieldIsNeverAReopen() {
        // Nothing precedes offset 0, so there is no word to come back to.
        assertFalse(reopensWordUnderCursor(selectionLength = 0, selStart = 0, selEnd = 0))
    }

    @Test
    fun aDisagreeingSelectionIsNotCollapsed() {
        // selectionLength is computed from the cached, normalized offsets while the
        // callback carries the editor's raw ones; if they disagree the cursor is not
        // where this rule assumes, so it declines rather than guessing.
        assertFalse(reopensWordUnderCursor(selectionLength = 0, selStart = 3, selEnd = 7))
    }
}
