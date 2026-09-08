package com.kinetica.keyboard.ui

import com.kinetica.keyboard.settings.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When holding `?123` and tapping a letter fires a chord.
 *
 * A user reported the default 150 ms lead-in as a delay between the two presses, and they
 * were right: the window is a real wait, it is checked once at the letter's down, and a
 * letter inside it types normally instead. The window is a setting now, so what this pins
 * is that the ends of its range behave.
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
}
