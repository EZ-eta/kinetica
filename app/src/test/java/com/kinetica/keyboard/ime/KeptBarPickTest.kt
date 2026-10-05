package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A pick from a bar the stale timeout kept up.
 *
 * The timeout zeroes the word state but keeps a bar whose words came from the other language,
 * since the user is reading them. A pick then has no tentative length to trust, and counting
 * from a remembered one once ate text, so the span is re-proved against the editor or the pick is
 * refused.
 */
class KeptBarPickTest {

    @Test
    fun anUntouchedEditorGivesTheStaleWordsLength() {
        // A two-token buffer whose first decode put `impo` on screen, then went no-native.
        assertEquals(4, keptBarPickSpan("I said impo", "I said impo", "impo"))
    }

    @Test
    fun aGestureThatNeverDecodedReplacesNothing() {
        // One swipe, no earlier decode: nothing of it is on screen, so the pick inserts.
        assertEquals(0, keptBarPickSpan("I said ", "I said ", ""))
    }

    @Test
    fun anythingTypedOrDeletedSinceRefusesThePick() {
        assertEquals(-1, keptBarPickSpan("I said impo ", "I said impo", "impo"))
        assertEquals(-1, keptBarPickSpan("I said imp", "I said impo", "impo"))
        assertEquals(-1, keptBarPickSpan("I said ", "I said impo", "impo"))
    }

    @Test
    fun aStaleWordTheEditorDoesNotEndWithIsRefused() {
        // A word typed straight after the previous one with no space: trailingLetterRun
        // would read `helloimpo` and replace both. Only the stale word itself may go.
        assertEquals(4, keptBarPickSpan("helloimpo", "helloimpo", "impo"))
        assertEquals(-1, keptBarPickSpan("hello", "hello", "impo"))
    }
}
