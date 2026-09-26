package com.kinetica.keyboard.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a commit puts the correction strip up.
 *
 * The defect, reported as "sometimes there are no suggestions while typing but after a
 * word is finished there is one and nothing happens after a tap": the strip was gated on
 * the same "teaches nothing" condition as the learning calls beside it, so a field
 * setting IME_FLAG_NO_PERSONALIZED_LEARNING lost it. DuckDuckGo, Molly and Firefox Focus
 * set that flag on ordinary text fields, which is the whole of R42 one layer down - the
 * bar filled while typing and then went blank at the commit with no way back.
 *
 * Both directions are pinned. A password field must still show nothing, because the
 * strip would put the password in the bar after it was typed.
 */
class CorrectionStripTest {

    @Test
    fun aNoLearningFieldStillOffersCorrections() {
        // The reported case, and the whole of the fix: the field forbids learning and
        // says nothing about the strip. Learning is refused separately, inside learnWord,
        // unlearnWord and learnPair, each of which reads teachesNothing itself.
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
        // Its only zone is the selected one, and a tap on the selected zone is a no-op
        // by design, so the strip would be a word offering nothing but itself.
        assertFalse(showsCorrectionStrip("hello", offersCorrections = true, optionCount = 1))
        assertFalse(showsCorrectionStrip("hello", offersCorrections = true, optionCount = 0))
    }

    @Test
    fun anEmptyCommitGetsNothing() {
        assertFalse(showsCorrectionStrip("", offersCorrections = true, optionCount = 4))
    }
}
