package com.kinetica.keyboard.keys

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The three-way case map and its inverse.
 *
 * Pure because the popup's whole job is to pick one of these and the service's is to apply
 * it: the edit around them is one `replaceBeforeCursor` and is on the device checklist.
 * The inverse is here for the popup's pre-selection, which has to open on the case the word
 * is already in.
 */
class WordCaseTest {

    @Test
    fun eachCaseIsWhatItSays() {
        assertEquals("world", WordCase.LOWER.applyTo("world"))
        assertEquals("World", WordCase.TITLE.applyTo("world"))
        assertEquals("WORLD", WordCase.UPPER.applyTo("world"))
    }

    @Test
    fun everyCaseIsReachableFromEveryOther() {
        // Applying a case must not depend on the case it starts from, or the popup would
        // be a cycle instead of a choice - Abc from ABC is the one that would break.
        for (start in listOf("world", "World", "WORLD", "wORLD")) {
            assertEquals(start, "world", WordCase.LOWER.applyTo(start))
            assertEquals(start, "World", WordCase.TITLE.applyTo(start))
            assertEquals(start, "WORLD", WordCase.UPPER.applyTo(start))
        }
    }

    @Test
    fun theInverseNamesTheCaseAWordIsIn() {
        assertEquals(WordCase.LOWER, WordCase.of("world"))
        assertEquals(WordCase.TITLE, WordCase.of("World"))
        assertEquals(WordCase.UPPER, WordCase.of("WORLD"))
    }

    @Test
    fun aSingleCapitalIsTitleAndNotShouting() {
        // The same `length > 1` guard reloadWordUnderCursor uses: one capital is a
        // sentence start far more often than an abbreviation, and without this the popup
        // would open on ABC for every `I`.
        assertEquals(WordCase.TITLE, WordCase.of("I"))
        assertEquals(WordCase.LOWER, WordCase.of("i"))
    }

    @Test
    fun anApostropheDoesNotStopAWordReadingAsShouted() {
        // `DON'T` is upper; the apostrophe has no case and must not vote.
        assertEquals(WordCase.UPPER, WordCase.of("DON'T"))
        assertEquals(WordCase.TITLE, WordCase.of("Don't"))
        assertEquals(WordCase.LOWER, WordCase.of("don't"))
    }

    @Test
    fun aWordWithNoLettersAtAllReadsAsLower() {
        // Nothing to shout: "---" is all-non-letter and must not report UPPER, or the
        // popup opens on a case the word cannot be in.
        assertEquals(WordCase.LOWER, WordCase.of("---"))
        assertEquals(WordCase.LOWER, WordCase.of(""))
    }

    @Test
    fun titleCaseFlattensTheTailRatherThanOnlyRaisingTheHead() {
        // Abc from ABC is the case that proves it: replaceFirstChar alone would give
        // "WORLD" back unchanged and the cell would look broken.
        assertEquals("World", WordCase.TITLE.applyTo("WORLD"))
    }

    @Test
    fun theCellOrderMatchesTheEnumOrder() {
        // The popup passes a cell and the service maps it by index, so a reordering of
        // either list silently re-labels the other.
        assertEquals(3, WordCase.entries.size)
        assertEquals(WordCase.LOWER, WordCase.entries[0])
        assertEquals(WordCase.TITLE, WordCase.entries[1])
        assertEquals(WordCase.UPPER, WordCase.entries[2])
    }
}
