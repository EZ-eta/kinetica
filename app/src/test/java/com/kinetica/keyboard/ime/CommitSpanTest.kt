package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the last committed word actually is.
 *
 * Pure for the reason the rest of this package's rules are: the service has no JVM reach.
 * What is decided here is a delete count, and KNOWN_ISSUES item 69 is what a wrong one
 * does - the window started too far right and consumed the word it was meant to re-case,
 * so `be?` came back as `bBE` and `going...` as `goGOING.`. Every row below is the arithmetic
 * that produced one of those.
 */
class CommitSpanTest {

    private val max = 8

    @Test
    fun aMarkAfterTheWordIsPartOfTheSpan() {
        // The three reports, verbatim. The old arithmetic remembered no trailing at all
        // for these and returned 2, 5 and 6 against the 3, 6 and 8 the editor holds.
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
        // `half-hearted`: the hyphen is a boundary the walk stops at, so the span covers
        // `hearted` and the text before it is not touched. This one was already right and
        // the fix must not move it.
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
        // Only the LENGTH is used, so case cannot change the answer - and refusing on it
        // would kill the feature wherever auto-capitalization wrote a letter the caller
        // does not carry. `e.g.` becoming `e.G.` is item 70, a different bug, and this
        // must not corrupt the text while that one is open.
        assertEquals(2, commitSpan("e.g.", "G", max))
        assertEquals(2, commitSpan("e.g.", "g", max))
        assertEquals(3, commitSpan("BE?", "be", max))
    }

    @Test
    fun aWordTheEditorNoLongerHoldsIsRefused() {
        // The correction strip outlives the commit it names, and commitWordInternal writes
        // lastCommitWord inside its own learning guard, so a private field can leave the
        // cached word pointing at text that has moved on. Refusing is the whole fix: the
        // old arithmetic deleted five characters here.
        assertEquals(-1, commitSpan("hello world ", "hello", max))
        assertEquals(-1, commitSpan("", "hello", max))
        assertEquals(-1, commitSpan("hi", "hello", max))
    }

    @Test
    fun aRunOfMarksLongerThanTheBoundIsRefused() {
        // The bound on what one mis-tracked commit can delete. Nothing the keyboard writes
        // after a word reaches eight characters, so past it the word is not where the
        // caller believes and a guess would be the corruption all over again.
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
