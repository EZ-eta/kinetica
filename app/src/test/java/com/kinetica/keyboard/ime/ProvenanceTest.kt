package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.models.WordCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The language a committed word is learned into, read off the candidate it came from.
 *
 * `languageOf` lowercases the word it is asked about, and the map used to be keyed by the
 * candidate's display form. German nouns are capitalized in the display form, so with German
 * as the second enabled language every noun missed the map and was learned into the active
 * language instead: R56's defect by a second route.
 */
class ProvenanceTest {

    private fun candidate(word: String, language: String) =
        WordCandidate(word, 1f, 0f, 1f, 1f, 0, WordCandidate.Source.SWIPE, language)

    @Test
    fun aCapitalizedCandidateIsFoundByItsLowercaseWord() {
        val map = provenanceOf(listOf(candidate("Haus", "de"), candidate("house", "en")))
        assertEquals("de", map["haus"])
        assertEquals("en", map["house"])
    }

    @Test
    fun theHigherRankedSpellingKeepsTheWord() {
        // `Leben` and `leben` are both German; the first one listed decides, as it did before.
        val map = provenanceOf(listOf(candidate("Leben", "de"), candidate("leben", "xx")))
        assertEquals("de", map["leben"])
    }

    @Test
    fun aCandidateWithNoLanguageSaysNothing() {
        assertNull(provenanceOf(listOf(candidate("word", "")))["word"])
    }
}
