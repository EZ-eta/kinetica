package com.kinetica.keyboard.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a commit puts the correction strip up.
 *
 * The strip is not gated on "teaches nothing" like the learning calls beside it: DuckDuckGo,
 * Molly and Firefox Focus set IME_FLAG_NO_PERSONALIZED_LEARNING on ordinary text fields, and
 * gating on it blanked the bar at every commit there. A password field
 * still shows nothing, because the strip would put the password in the bar.
 */
class CorrectionStripTest {

    @Test
    fun aNoLearningFieldStillOffersCorrections() {
        // The field forbids learning and says nothing about the strip. Learning is refused
        // separately: learnWord, unlearnWord and learnPair each read teachesNothing.
        val field = EditorState.DEFAULT.copy(noLearning = true)
        assertTrue(field.teachesNothing)
        assertTrue(field.offersCorrections)
        assertTrue(showsCorrectionStrip("hello", field.offersCorrections, optionCount = 4))
    }

    @Test
    fun aPasswordFieldGetsNothing() {
        // pushSuggestions already offers nothing while typing in one; a strip afterwards
        // would hand the password back on screen.
        val field = EditorState.DEFAULT.copy(privateMode = true)
        assertFalse(field.offersCorrections)
        assertFalse(showsCorrectionStrip("hunter2", field.offersCorrections, optionCount = 4))
    }

    @Test
    fun anOrdinaryFieldGetsTheStrip() {
        assertTrue(showsCorrectionStrip("hello", EditorState.DEFAULT.offersCorrections, 4))
    }

    @Test
    fun aStripOfOneIsSuppressed() {
        // Its only zone is the selected one, and a tap there is a no-op, so the strip would
        // offer nothing but the word itself.
        assertFalse(showsCorrectionStrip("hello", offersCorrections = true, optionCount = 1))
        assertFalse(showsCorrectionStrip("hello", offersCorrections = true, optionCount = 0))
    }

    @Test
    fun anEmptyCommitGetsNothing() {
        assertFalse(showsCorrectionStrip("", offersCorrections = true, optionCount = 4))
    }
}
