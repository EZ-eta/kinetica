package com.kinetica.keyboard.keys

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoCapitalizationTest {

    @Test
    fun loneIIsCapitalizedInEnglish() {
        assertEquals("I", AutoCapitalization.forWord("i", "en"))
        // Already capital, e.g. at a sentence start where auto-shift got there
        // first: the rule must be idempotent, not a toggle.
        assertEquals("I", AutoCapitalization.forWord("I", "en"))
    }

    @Test
    fun loneIIsLeftAloneInLanguagesWhereItIsAWord() {
        // Italian "i" is the plural masculine article; capitalizing it would be
        // wrong mid-sentence, which is why the rule is language-gated at all.
        assertEquals("i", AutoCapitalization.forWord("i", "it"))
        assertEquals("i", AutoCapitalization.forWord("i", "es"))
        assertEquals("i", AutoCapitalization.forWord("i", "pl"))
        assertEquals("i", AutoCapitalization.forWord("i", ""))
    }

    @Test
    fun everyOtherWordIsUntouched() {
        for (lang in listOf("en", "it", "es", "pl")) {
            for (w in listOf("in", "if", "is", "it", "ii", "a", "o", "island", "iowa", "")) {
                assertEquals("$w changed under $lang", w, AutoCapitalization.forWord(w, lang))
            }
        }
    }

    @Test
    fun otherSingleLettersAreUntouchedInEnglish() {
        // "a" is the other English single-letter word and must stay lowercase;
        // the rule is about the pronoun, not about single letters.
        for (c in 'a'..'z') {
            if (c == 'i') continue
            assertEquals("$c changed", c.toString(), AutoCapitalization.forWord(c.toString(), "en"))
        }
    }
}
