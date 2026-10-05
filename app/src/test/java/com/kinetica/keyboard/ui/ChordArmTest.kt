package com.kinetica.keyboard.ui

import com.kinetica.keyboard.keys.ChordTrigger
import com.kinetica.keyboard.settings.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When holding `?123` and tapping a letter fires a chord.
 *
 * The lead-in window is a real wait: it is checked once at the letter's down, and a letter
 * inside it types normally. Users felt the 150 ms default as a delay, so the window is a
 * setting and this pins the ends of its range.
 */
class ChordArmTest {

    private val default = CHORD_ARM_MS_DEFAULT

    @Test
    fun aLetterInsideTheWindowDoesNotArm() {
        assertFalse(chordArms(modeHeld = true, modeMoved = false, heldMs = 0, armMs = default))
        assertFalse(chordArms(modeHeld = true, modeMoved = false, heldMs = 149, armMs = default))
    }

    @Test
    fun aLetterAtTheWindowArms() {
        assertTrue(chordArms(modeHeld = true, modeMoved = false, heldMs = 150, armMs = default))
        assertTrue(chordArms(modeHeld = true, modeMoved = false, heldMs = 900, armMs = default))
    }

    @Test
    fun aZeroWindowArmsAtOnce() {
        // The bottom of the user-settable range: a simultaneous press is a chord.
        assertTrue(chordArms(modeHeld = true, modeMoved = false, heldMs = 0, armMs = 0))
    }

    @Test
    fun theWidestWindowStillArms() {
        assertFalse(chordArms(modeHeld = true, modeMoved = false, heldMs = 299, armMs = 300))
        assertTrue(chordArms(modeHeld = true, modeMoved = false, heldMs = 300, armMs = 300))
    }

    @Test
    fun travelDisarmsForTheWholeHold() {
        // A slide to the numpad must not also fire a chord, however long the hold lasts.
        assertFalse(chordArms(modeHeld = true, modeMoved = true, heldMs = 5_000, armMs = default))
        assertFalse(chordArms(modeHeld = true, modeMoved = true, heldMs = 5_000, armMs = 0))
    }

    @Test
    fun theViewDefaultAndThePreferenceDefaultAgree() {
        // Two places carry 150: the view's own fallback and what a fresh install reads.
        assertEquals(Prefs.DEFAULT_CHORD_ARM_MS.toLong(), CHORD_ARM_MS_DEFAULT)
    }

    @Test
    fun aLetterWithNoModeKeyDownIsJustALetter() {
        assertFalse(chordArms(modeHeld = false, modeMoved = false, heldMs = 5_000, armMs = 0))
    }

    // ---- the spacebar trigger and the decision at the lift ---------------------------------

    @Test
    fun theSpacebarDefaultAndThePreferenceDefaultAgree() {
        assertEquals(Prefs.DEFAULT_SPACE_CHORD_ARM_MS.toLong(), SPACE_CHORD_ARM_MS_DEFAULT)
        // 50 ms, the default. The lift rule guards against rollover, so a short wait
        // is not the only thing between a typed space and a chord.
        assertEquals(50L, SPACE_CHORD_ARM_MS_DEFAULT)
    }

    @Test
    fun questionMarkWinsWhenBothTriggersAreHeld() {
        assertEquals(listOf(ChordTrigger.MODE, ChordTrigger.SPACE), armedChordTriggers(modeArmed = true, spaceArmed = true))
        assertEquals(listOf(ChordTrigger.SPACE), armedChordTriggers(modeArmed = false, spaceArmed = true))
        assertEquals(emptyList<ChordTrigger>(), armedChordTriggers(modeArmed = false, spaceArmed = false))
    }

    @Test
    fun aChordFiresOnlyWhileItsTriggerIsStillHeldStill() {
        val slop = 12f * 12f
        assertEquals(ChordLift.FIRE, chordAtLift(triggerHeld = true, triggerMoved = false, travelSq = 4f, slopSq = slop))
        // The thumb left the spacebar first: that was typing, and the key types.
        assertEquals(ChordLift.LIFTED, chordAtLift(triggerHeld = false, triggerMoved = false, travelSq = 4f, slopSq = slop))
        assertEquals(ChordLift.MOVED, chordAtLift(triggerHeld = true, triggerMoved = true, travelSq = 4f, slopSq = slop))
        // A swipe from a chord key is a word, not a chord.
        assertEquals(ChordLift.SWIPED, chordAtLift(triggerHeld = true, triggerMoved = false, travelSq = 400f, slopSq = slop))
    }
}
