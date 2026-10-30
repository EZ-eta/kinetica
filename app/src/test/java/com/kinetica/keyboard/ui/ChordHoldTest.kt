package com.kinetica.keyboard.ui

import com.kinetica.keyboard.engine.KineticaConstants
import com.kinetica.keyboard.settings.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A held trigger whose chord labels show keeps them through a small drift: a thumb resting on
 * `?123` or the spacebar moves a little, and lost the labels and the arm at the tap slop.
 */
class ChordHoldTest {

    private val tap = KineticaConstants.TAP_MAX_DISP_DP
    private val wiggle = KineticaConstants.CHORD_HOLD_WIGGLE_DP

    /** Whether a drift of [dp] latches the trigger as moved. */
    private fun moved(dp: Float, labelsUp: Boolean, wiggleOn: Boolean): Boolean =
        dp > triggerSlopPx(labelsUp, wiggleOn, tap, wiggle)

    @Test
    fun aDriftWithTheLabelsUpKeepsThem() {
        // 18 dp: past the tap slop, inside the wiggle. The reported case.
        assertFalse(moved(18f, labelsUp = true, wiggleOn = true))
        assertFalse(moved(wiggle, labelsUp = true, wiggleOn = true))
    }

    @Test
    fun aDriftPastTheWiggleStillMoves() {
        assertTrue(moved(wiggle + 1f, labelsUp = true, wiggleOn = true))
    }

    @Test
    fun beforeTheLabelsTheTapSlopHolds() {
        // A quick slide or page tap reads as before: the wiggle starts with the labels.
        assertTrue(moved(18f, labelsUp = false, wiggleOn = true))
        assertFalse(moved(tap, labelsUp = false, wiggleOn = true))
    }

    @Test
    fun theSwitchOffKeepsTheTapSlop() {
        assertTrue(moved(18f, labelsUp = true, wiggleOn = false))
    }

    @Test
    fun theWiggleStaysUnderTheModeSlide() {
        // The ?123 slide to the numpad is 30 dp (detectModeSlide); a wiggle that reached it
        // would hold the labels through a slide.
        assertTrue(wiggle > tap)
        assertTrue(wiggle < 30f)
    }

    @Test
    fun theSpacebarCursorWaitsWhileItsLabelsShow() {
        assertFalse(spaceSlideAllowed(labelsUp = true, wiggleOn = true))
        assertTrue(spaceSlideAllowed(labelsUp = false, wiggleOn = true))
        assertTrue(spaceSlideAllowed(labelsUp = true, wiggleOn = false))
        assertTrue(spaceSlideAllowed(labelsUp = false, wiggleOn = false))
    }

    @Test
    fun theSpacebarWiggleIsOnByDefault() {
        assertEquals(true, Prefs.DEFAULT_SPACE_CHORD_WIGGLE)
    }
}
