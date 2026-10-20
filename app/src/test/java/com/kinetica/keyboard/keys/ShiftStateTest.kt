package com.kinetica.keyboard.keys

import com.kinetica.keyboard.keys.ShiftState.State
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Shift taps on an injected clock. The 300 ms window is the one the spacebar's double tap uses;
 * 120 ms is a quick double tap, 600 ms two separate taps.
 */
class ShiftStateTest {

    private var now = 10_000L
    private val shift = ShiftState { now }

    private fun tapAfter(ms: Long) {
        now += ms
        shift.onShiftKey()
    }

    @Test
    fun aDoubleTapLocksCaps() {
        tapAfter(0)
        assertEquals(State.SHIFT, shift.state)
        tapAfter(120)
        assertEquals(State.CAPS_LOCK, shift.state)
        tapAfter(600)
        assertEquals(State.NONE, shift.state)
    }

    @Test
    fun twoSlowTapsTurnShiftOnAndOff() {
        tapAfter(0)
        tapAfter(600)
        assertEquals(State.NONE, shift.state)
    }

    @Test
    fun aDoubleTapAtASentenceStartLocksCaps() {
        // #39: the sentence start is already SHIFT. The first tap clears it, and the second
        // used to set it again, so the pair went SHIFT, NONE, SHIFT and never locked.
        shift.autoShift(true)
        tapAfter(600)
        assertEquals(State.NONE, shift.state)
        tapAfter(120)
        assertEquals(State.CAPS_LOCK, shift.state)
    }

    @Test
    fun aSingleTapAtASentenceStartStillClearsTheShift() {
        shift.autoShift(true)
        tapAfter(600)
        assertEquals(State.NONE, shift.state)
        now += 600
        assertEquals(State.NONE, shift.state)
    }

    @Test
    fun aQuickTapAfterLeavingCapsLockIsAShiftNotALock() {
        tapAfter(0)
        tapAfter(120)
        assertEquals(State.CAPS_LOCK, shift.state)
        tapAfter(600)
        assertEquals(State.NONE, shift.state)
        tapAfter(120)
        assertEquals(State.SHIFT, shift.state)
    }

    @Test
    fun aLetterBetweenTwoTapsMakesTwoCapitals() {
        tapAfter(0)
        now += 100
        shift.onLetterCommitted()
        assertEquals(State.NONE, shift.state)
        tapAfter(100)
        assertEquals(State.SHIFT, shift.state)
    }

    @Test
    fun aKeyBetweenTwoTapsIsNoDoubleTapAfterClearingTheShift() {
        // The letter typed after clearing a sentence start's capital commits with shift off, so
        // onLetterCommitted is not called; the key itself must still disarm the pair.
        shift.autoShift(true)
        tapAfter(600)
        now += 100
        shift.onOtherKey()
        tapAfter(100)
        assertEquals(State.SHIFT, shift.state)
    }

    @Test
    fun capsLockSurvivesAnAutoShift() {
        tapAfter(0)
        tapAfter(120)
        shift.autoShift(false)
        assertEquals(State.CAPS_LOCK, shift.state)
        shift.autoShift(true)
        assertEquals(State.CAPS_LOCK, shift.state)
    }

    @Test
    fun anUndoneCapitalComesBackAsAOneShotShift() {
        // The gesture undo puts a spent capital back through autoShift(true).
        tapAfter(0)
        shift.onLetterCommitted()
        shift.autoShift(true)
        assertEquals(State.SHIFT, shift.state)
        assertEquals("Word", shift.apply("word"))
        shift.onLetterCommitted()
        assertEquals(State.NONE, shift.state)
    }
}
