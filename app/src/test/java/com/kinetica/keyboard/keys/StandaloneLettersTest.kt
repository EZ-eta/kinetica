package com.kinetica.keyboard.keys

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-language single-letter word sets.
 *
 * The first test is the whole reason this object exists rather than a dictionary call, so
 * it is the one to read first if the design is ever questioned.
 */
class StandaloneLettersTest {

    @Test
    fun aDictionaryTestCouldNotDoThis() {
        // Every letter a-z is an entry in every bundled wordlist, with frequencies that
        // look deliberate: en holds l at 126 518, s at 110 199, t at 72 881, d at 64 304.
        // They are OpenSubtitles artefacts. So isWord() answers yes for all of them and
        // the only thing that separates a lone `a` from a lone `t` is a curated list.
        for (c in "lstdmcehnbfgjrpxuwkvzq") {
            assertFalse("$c is not an English word on its own", StandaloneLetters.isWord(c, "en"))
        }
        assertTrue(StandaloneLetters.isWord('a', "en"))
        assertTrue(StandaloneLetters.isWord('i', "en"))
    }

    @Test
    fun theLanguageDecidesAndNotThePosition() {
        // The same argument AutoCapitalization makes: `e` is a word in Italian and not in
        // English, so the sets cannot be merged.
        assertTrue("e is Italian for and", StandaloneLetters.isWord('e', "it"))
        assertFalse("e alone is not English", StandaloneLetters.isWord('e', "en"))
        assertTrue("y is Spanish for and", StandaloneLetters.isWord('y', "es"))
        assertFalse("y alone is not Italian", StandaloneLetters.isWord('y', "it"))
        assertTrue("w is Polish for in", StandaloneLetters.isWord('w', "pl"))
        assertFalse("w alone is not Spanish", StandaloneLetters.isWord('w', "es"))
    }

    @Test
    fun eachSetIsTheClosedListOfThatLanguagesOneLetterWords() {
        assertEqualsSet("ai", "en")
        assertEqualsSet("aeio", "it")
        assertEqualsSet("aeoy", "es")
        assertEqualsSet("aiouwz", "pl")
    }

    @Test
    fun accentsAndCaseArriveFolded() {
        // The composer's literal is already folded to a-z, so Italian `è` reaches this as
        // `e`; the case fold is a belt for a caller that passes the shifted glyph.
        assertTrue(StandaloneLetters.isWord('E', "it"))
        assertTrue(StandaloneLetters.isWord('I', "en"))
    }

    @Test
    fun anUnknownLanguageFallsBackToEnglish() {
        // Registration order is ADDING_A_LANGUAGE's business; an unregistered code must
        // still behave, and the smallest set is the safe default.
        assertTrue(StandaloneLetters.isWord('a', "de"))
        assertFalse(StandaloneLetters.isWord('e', "de"))
    }

    private fun assertEqualsSet(expected: String, lang: String) {
        for (c in 'a'..'z') {
            val want = c in expected
            assertTrue(
                "$lang: $c should be ${if (want) "a word" else "no word"}",
                StandaloneLetters.isWord(c, lang) == want,
            )
        }
    }
}
