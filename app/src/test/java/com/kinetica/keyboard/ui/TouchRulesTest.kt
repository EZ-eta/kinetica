package com.kinetica.keyboard.ui

import com.kinetica.keyboard.engine.KineticaConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Touches that are not the word. The touch path has no JVM reach, so the rules are tested here
 * and the view's use of them is a device step.
 */
class TouchRulesTest {

    private val rest = KineticaConstants.RESTING_FINGER_MIN_MS

    @Test
    fun aDigitBrushedMidSwipeIsDropped() {
        assertTrue(TouchRules.dropsDigitTap(fromNumberRow = true, otherSwiping = true, plainTap = true))
        // Typed on its own, or beside a tap, a digit is a digit.
        assertFalse(TouchRules.dropsDigitTap(fromNumberRow = true, otherSwiping = false, plainTap = true))
        assertFalse(TouchRules.dropsDigitTap(fromNumberRow = false, otherSwiping = true, plainTap = true))
    }

    @Test
    fun aWordStartedOnTheDigitsBecomesASwipeOnceItReachesALetter() {
        assertTrue(TouchRules.handsOverToSwipe(true, travelSq = 900f, slopSq = 400f, onLetter = true))
        assertFalse(TouchRules.handsOverToSwipe(true, travelSq = 300f, slopSq = 400f, onLetter = true))
        assertFalse(TouchRules.handsOverToSwipe(true, travelSq = 900f, slopSq = 400f, onLetter = false))
        assertFalse(TouchRules.handsOverToSwipe(false, travelSq = 900f, slopSq = 400f, onLetter = true))
    }

    @Test
    fun aFingerHeldStillBesideTheOtherThumbIsAtRest() {
        assertTrue(TouchRules.isRestingFinger(stationary = true, heldMs = rest, overlapped = true))
        // A cross-thumb tap inside a swipe is short: it is a letter.
        assertFalse(TouchRules.isRestingFinger(stationary = true, heldMs = 120L, overlapped = true))
        // A held swipe, as in a pause and resume, moved.
        assertFalse(TouchRules.isRestingFinger(stationary = false, heldMs = 900L, overlapped = true))
        // A slow press alone is a deliberate letter.
        assertFalse(TouchRules.isRestingFinger(stationary = true, heldMs = 900L, overlapped = false))
    }

    @Test
    fun aThirdFingerOnALetterIsIgnoredOnlyWhenBothThumbsAreTaken() {
        assertTrue(TouchRules.ignoresExtraFinger(onLetter = true, engineFull = true))
        assertFalse(TouchRules.ignoresExtraFinger(onLetter = true, engineFull = false))
        assertFalse(TouchRules.ignoresExtraFinger(onLetter = false, engineFull = true))
    }

    @Test
    fun aMenuWaitsForTheOtherThumbToLeave() {
        assertFalse(TouchRules.holdOpensMenu(otherOnLetters = true))
        assertTrue(TouchRules.holdOpensMenu(otherOnLetters = false))
    }
}
