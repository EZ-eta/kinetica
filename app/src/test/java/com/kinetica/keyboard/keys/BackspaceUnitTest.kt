package com.kinetica.keyboard.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A backspace slide's unit: a held shift swaps it until the first unit, then it holds. */
class BackspaceUnitTest {

    @Test
    fun aShiftHeldBeforeTheFirstUnitSwapsIt() {
        val u = BackspaceUnit()
        assertFalse(u.chars)
        u.flipped = true
        assertTrue(u.chars)
        u.charMode = true
        assertFalse("chars by setting, words under shift", u.chars)
    }

    @Test
    fun theModeIsFixedAtTheFirstUnit() {
        val u = BackspaceUnit()
        u.fix()
        u.flipped = true
        assertFalse("a flip after the first unit would change what the count means", u.chars)
        assertEquals(DeleteSpan.Granularity.WORDS, u.granularity)
        u.release()
        assertTrue("the next touch reads shift again", u.chars)
    }

    @Test
    fun aFixedFlipHoldsAfterShiftIsLifted() {
        val u = BackspaceUnit()
        u.flipped = true
        u.fix()
        u.flipped = false
        assertEquals(DeleteSpan.Granularity.CHARS, u.granularity)
    }

    @Test
    fun punctuationStopsFollowTheWordMode() {
        val u = BackspaceUnit()
        u.stopsAtPunctuation = true
        assertEquals(DeleteSpan.Granularity.PIECES, u.granularity)
        u.flipped = true
        assertEquals(DeleteSpan.Granularity.CHARS, u.granularity)
        u.charMode = true
        assertEquals("words under shift keep the stops", DeleteSpan.Granularity.PIECES, u.granularity)
    }
}
