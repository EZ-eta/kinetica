package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How much of the last commit backspace has eaten, the reading behind the trace's
 * `backspace into commit` line. The fixture is a captured `provarne` that retype missed.
 */
class BackspaceLeftTest {

    private val max = 8

    @Test
    fun theWordIsWholeWhileOnlyItsSpaceGoes() {
        assertEquals(-1, backspaceLeft("il proverbio provarne ", "provarne", max))
        assertEquals(-1, backspaceLeft("il proverbio provarne", "provarne", max))
    }

    @Test
    fun eachLetterEatenCountsDownToZero() {
        assertEquals(7, backspaceLeft("il proverbio provarn", "provarne", max))
        assertEquals(1, backspaceLeft("il proverbio p", "provarne", max))
        assertEquals(0, backspaceLeft("il proverbio ", "provarne", max))
    }

    @Test
    fun theCursorLeavingTheWordEndsTheCount() {
        // The space before it has gone too: the run is now the previous word.
        assertNull(backspaceLeft("il proverbio", "provarne", max))
        // A different word typed in its place is not the commit being eaten.
        assertNull(backspaceLeft("il proverbio x", "provarne", max))
    }
}
