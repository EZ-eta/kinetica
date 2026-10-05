package com.kinetica.keyboard.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A letter's Default: every enabled language's built-in list, to trim again. */
class LetterDefaultsTest {

    // The shape of the shipped lists for `a`, active language first.
    private val italian = listOf("à", "á", "â", "ä", "@")
    private val english = listOf("à", "á", "â", "ä", "ã", "å", "æ", "ā", "@")
    private val polish = listOf("ą", "à", "á", "@")

    @Test
    fun anAccentTrimmedAwayComesBack() {
        // An accent trimmed from a's list can be restored, and the Default draws on every
        // enabled language: Polish's `ą` comes from Polish.
        val trimmed = listOf("à", "á", "â", "@")
        val back = LetterDefaults.union(listOf(italian, english, polish), KeyboardConfig.MAX_LETTER_ALTERNATES)
        assertTrue("ä" in back)
        assertTrue("ą" in back)
        assertTrue(back.containsAll(trimmed))
    }

    @Test
    fun theActiveLanguageComesFirstAndEachCharacterOnce() {
        val u = LetterDefaults.union(listOf(italian, english, polish), KeyboardConfig.MAX_LETTER_ALTERNATES)
        assertEquals(listOf("à", "á", "â", "ä", "@", "ã", "å", "æ", "ā", "ą"), u)
    }

    @Test
    fun oneLanguageGivesItsOwnList() {
        assertEquals(italian, LetterDefaults.union(listOf(italian), KeyboardConfig.MAX_LETTER_ALTERNATES))
    }

    @Test
    fun theCapHolds() {
        val long = (1..20).map { "x$it" }
        assertEquals(long.take(14), LetterDefaults.union(listOf(long), 14))
        assertEquals(KeyboardConfig.MAX_LETTER_ALTERNATES, LetterDefaults.union(listOf(english, long), KeyboardConfig.MAX_LETTER_ALTERNATES).size)
    }

    @Test
    fun everyLetterOfEveryLanguageIsMerged() {
        val m = LetterDefaults.unionPerLetter(
            listOf(mapOf('a' to italian), mapOf('a' to english, 'n' to listOf("ñ"))),
            KeyboardConfig.MAX_LETTER_ALTERNATES,
        )
        assertEquals(setOf('a', 'n'), m.keys)
        assertEquals(listOf("ñ"), m['n'])
        assertEquals(LetterDefaults.union(listOf(italian, english), 14), m['a'])
    }
}
