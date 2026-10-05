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
        // Italian "i" is the plural masculine article and stays lowercase mid-sentence, so the
        // rule is gated by language.
        assertEquals("i", AutoCapitalization.forWord("i", "it"))
        assertEquals("i", AutoCapitalization.forWord("i", "es"))
        assertEquals("i", AutoCapitalization.forWord("i", "pl"))
        assertEquals("i", AutoCapitalization.forWord("i", "cs"))
        assertEquals("i", AutoCapitalization.forWord("i", "nl"))
        assertEquals("i", AutoCapitalization.forWord("i", "de"))
        assertEquals("i", AutoCapitalization.forWord("i", "fr"))
        // Norwegian "i" is the preposition "in", and StandaloneLetters treats
        // it as a word, so the capitalization rule must still leave it alone.
        assertEquals("i", AutoCapitalization.forWord("i", "no"))
        assertEquals("i", AutoCapitalization.forWord("i", ""))
    }

    @Test
    fun everyOtherWordIsUntouched() {
        for (lang in listOf("en", "it", "es", "pl", "cs", "nl", "de", "fr", "no", "ru", "he", "ar")) {
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

    @Test
    fun thePronounsContractionsAreCapitalizedInEnglish() {
        // A swiped `im` decodes to `i'm`, which must not stay lowercase mid-sentence.
        assertEquals("I'm", AutoCapitalization.forWord("i'm", "en"))
        assertEquals("I've", AutoCapitalization.forWord("i've", "en"))
        assertEquals("I'd", AutoCapitalization.forWord("i'd", "en"))
        assertEquals("I'll", AutoCapitalization.forWord("i'll", "en"))
        assertEquals("I'd've", AutoCapitalization.forWord("i'd've", "en"))
        assertEquals("I'm", AutoCapitalization.forWord("I'm", "en"))
    }

    @Test
    fun anEnglishContractionIsCapitalizedWhateverLanguageIsActive() {
        // Only the English list holds `i'm`: with Italian active and English mixed in, the
        // word is still English, so its language decides, not the active one.
        assertEquals("I'm", AutoCapitalization.forWord("i'm", "it", wordLang = "en"))
        assertEquals("I've", AutoCapitalization.forWord("i've", "pl", wordLang = "en"))
        // The lone article stays the active language's call.
        assertEquals("i", AutoCapitalization.forWord("i", "it", wordLang = "en"))
        assertEquals("i'm", AutoCapitalization.forWord("i'm", "it", wordLang = "it"))
    }

    @Test
    fun wordsThatOnlyLookLikeItAreUntouched() {
        for (w in listOf("im", "ive", "ill", "id", "it's", "i'", "i''", "i'2", "in'", "island's")) {
            assertEquals("$w changed", w, AutoCapitalization.forWord(w, "en"))
        }
    }
}
