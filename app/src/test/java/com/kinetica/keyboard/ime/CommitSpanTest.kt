package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the last committed word is.
 *
 * Pure, like this package's other rules, because the service has no JVM reach. It decides a
 * delete count, and a wrong one once ate text: the window started too far right and
 * ate the word it was meant to re-case, so `be?` came back as `bBE` and `going...` as
 * `goGOING.`.
 */
class CommitSpanTest {

    private val max = 8

    @Test
    fun aMarkAfterTheWordIsPartOfTheSpan() {
        // The three reported cases. Without the trailing marks the span was 2, 5 and 6 against
        // the 3, 6 and 8 the editor holds.
        assertEquals(3, commitSpan("be?", "be", max))
        assertEquals(6, commitSpan("going.", "going", max))
        assertEquals(8, commitSpan("going...", "going", max))
    }

    @Test
    fun theOrdinaryAutospaceCaseIsUnchanged() {
        // The common case by a long way, and the one the remembered length got right.
        assertEquals(6, commitSpan("hello ", "hello", max))
        assertEquals(5, commitSpan("hello", "hello", max))
    }

    @Test
    fun aWordAfterAHyphenTakesOnlyItsOwnHalf() {
        // `half-hearted`: the walk stops at the hyphen, so the span covers `hearted` and
        // nothing before it.
        assertEquals(7, commitSpan("half-hearted", "hearted", max))
    }

    @Test
    fun anApostropheBelongsToTheWordOnBothSides() {
        // The same reading trailingLetterRun takes, so a recase and a reload cannot
        // disagree about where a word ends.
        assertEquals(6, commitSpan("don't?", "don't", max))
        // Italian elision: the committed word is the piece after the apostrophe, and the
        // apostrophe is not a boundary, so the span is that piece alone.
        assertEquals(4, commitSpan("dell'anno", "anno", max))
        assertEquals(8, commitSpan("dico l'altro ", "l'altro", max))
    }

    @Test
    fun aCaseDifferenceIsNotARefusal() {
        // Only the length is used, so case cannot change the answer; refusing on it would break
        // the feature wherever auto-capitalization wrote a letter the caller does not carry.
        // The `e.g.` case.
        assertEquals(2, commitSpan("e.g.", "G", max))
        assertEquals(2, commitSpan("e.g.", "g", max))
        assertEquals(3, commitSpan("BE?", "be", max))
    }

    @Test
    fun aWordTheEditorNoLongerHoldsIsRefused() {
        // The correction strip outlives the commit it names, and commitWordInternal records
        // the commit inside its learning guard, so a private field can leave the cached word
        // pointing at text that has moved on. Counting from it would delete five characters here.
        assertEquals(-1, commitSpan("hello world ", "hello", max))
        assertEquals(-1, commitSpan("", "hello", max))
        assertEquals(-1, commitSpan("hi", "hello", max))
    }

    @Test
    fun aRunOfMarksLongerThanTheBoundIsRefused() {
        // The bound on what one mis-tracked commit can delete. Nothing the keyboard writes
        // after a word reaches eight characters, so past it the word is not where the caller
        // believes and a guess would corrupt the text.
        assertEquals(12, commitSpan("word!!!!!!!!", "word", max))
        assertEquals(-1, commitSpan("word!!!!!!!!!", "word", max))
    }

    @Test
    fun anEmptyWordHasNoSpan() {
        // commitWordInternal can be reached with an empty word; nothing may be deleted for
        // it, and 0 would read as a successful span at the call sites.
        assertEquals(-1, commitSpan("hello ", "", max))
    }
}
