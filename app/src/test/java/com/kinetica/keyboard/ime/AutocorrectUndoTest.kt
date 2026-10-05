package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/** Backspace straight after an autocorrect puts the typed letters back. */
class AutocorrectUndoTest {

    @Test
    fun theWordAndTheSpaceAfterItGoBack() {
        assertEquals(4, autocorrectUndoSpan("I said and ", "and", typedAfter = 1))
        assertEquals(4, autocorrectUndoSpan("so and,", "and", typedAfter = 1))
        assertEquals(3, autocorrectUndoSpan("so and", "and", typedAfter = 0))
    }

    @Test
    fun anythingTypedSinceRefusesIt() {
        assertEquals(-1, autocorrectUndoSpan("and x", "and", typedAfter = 2))
        assertEquals(-1, autocorrectUndoSpan("andx", "and", typedAfter = 1))
        assertEquals(-1, autocorrectUndoSpan("anb ", "and", typedAfter = 1))
        assertEquals(-1, autocorrectUndoSpan("d ", "and", typedAfter = 1))
    }
}
