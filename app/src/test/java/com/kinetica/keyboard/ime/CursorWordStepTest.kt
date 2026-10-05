package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/** In right-to-left text the spacebar's word step follows the thumb, like the letter step. */
class CursorWordStepTest {

    @Test
    fun latinTextKeepsTheStep() {
        assertEquals(1, wordStepDirection(1, "hello ", "world"))
        assertEquals(-1, wordStepDirection(-1, "hello ", "world"))
    }

    @Test
    fun hebrewAndArabicTextTurnItRound() {
        assertEquals(-1, wordStepDirection(1, "שלום ", "עולם"))
        assertEquals(1, wordStepDirection(-1, "שלום ", "עולם"))
        assertEquals(-1, wordStepDirection(1, "مرحبا ", "بك"))
    }

    @Test
    fun digitsSpacesAndPunctuationAreLookedPast() {
        assertEquals(-1, wordStepDirection(1, "שלום 123, ", ""))
        assertEquals(1, wordStepDirection(1, "", " 42 hello"))
    }

    @Test
    fun theNearestLetterDecidesInMixedText() {
        // An English word typed inside Hebrew: the cursor sits by the English letters.
        assertEquals(1, wordStepDirection(1, "שלום hello", " עולם"))
        assertEquals(-1, wordStepDirection(1, "hello שלום", " world"))
    }

    @Test
    fun noLettersKeepTheStep() {
        assertEquals(1, wordStepDirection(1, "", ""))
        assertEquals(-1, wordStepDirection(-1, "12 ", "!"))
    }
}
