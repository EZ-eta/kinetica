package com.kinetica.keyboard.ime

import com.kinetica.keyboard.keys.EditorAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * What a retype throws away.
 *
 * Pure for the reason the rest of this package's rules are: the service has no JVM reach.
 * The behaviour around it - the delete, the abandon, the cursor - is one call each and is
 * on the device checklist; what is decided here is the span, which is the part that can be
 * got wrong quietly.
 */
class RetypeTest {

    @Test
    fun theWordInProgressIsWhatGoes() {
        // The reading the request suggests: "deletes the current word and starts again in
        // its place".
        assertEquals(5, retypeSpan(tentativeLength = 5, lastCommitWord = null, lastCommitTrailing = "", wordUnderCursor = ""))
    }

    @Test
    fun theWordInProgressWinsOverTheOneBeforeIt() {
        // Both can be set at once - the correction strip survives a commit while the next
        // word is being written - and the one under the thumb is the one meant.
        assertEquals(
            3,
            retypeSpan(tentativeLength = 3, lastCommitWord = "hello", lastCommitTrailing = " ", wordUnderCursor = ""),
        )
    }

    @Test
    fun withNothingInProgressTheLastCommittedWordGoesWithItsSpace() {
        // The case that actually gets used. The autospace commits fast, so by the time a
        // wrong word is noticed there is usually no word in progress - and the trailing
        // space is the keyboard's own, so leaving it would put the retyped word one space
        // further along.
        assertEquals(
            6,
            retypeSpan(tentativeLength = 0, lastCommitWord = "hello", lastCommitTrailing = " ", wordUnderCursor = ""),
        )
    }

    @Test
    fun aCommittedWordWithNoTrailingTextIsJustTheWord() {
        // Punctuation eats the autospace, so the trailing text can be a mark or nothing.
        assertEquals(
            5,
            retypeSpan(tentativeLength = 0, lastCommitWord = "hello", lastCommitTrailing = "", wordUnderCursor = ""),
        )
        assertEquals(
            6,
            retypeSpan(tentativeLength = 0, lastCommitWord = "hello", lastCommitTrailing = ".", wordUnderCursor = ""),
        )
    }

    @Test
    fun withNothingTheKeyboardKnowsItReadsTheRunUnderTheCursor() {
        // The case the button was asked for, and the one the first version refused. An
        // undecodable buffer is closed by the stale-buffer timeout, which zeroes the
        // tentative AND nulls the last commit while the letters stay on screen - so both
        // of the cases above report nothing exactly when the text is garbage. `pimn` was
        // what the developer was looking at when the button did not work.
        assertEquals(
            4,
            retypeSpan(
                tentativeLength = 0, lastCommitWord = null,
                lastCommitTrailing = "", wordUnderCursor = "pimn",
            ),
        )
    }

    @Test
    fun withNoRunEitherItStillDeletesNothing() {
        // A cursor at the start of a field, or after a space or a delimiter: there is no
        // word anywhere and a retype is a gesture the user will simply repeat.
        assertEquals(
            0,
            retypeSpan(
                tentativeLength = 0, lastCommitWord = null,
                lastCommitTrailing = "", wordUnderCursor = "",
            ),
        )
    }

    @Test
    fun theRunIsTheLastResortAndNotTheFirst() {
        // Ordering, because the run is always readable and would otherwise mask the two
        // cases that know more than it does. A word in progress wins over it, and so does
        // the word just committed together with its space.
        assertEquals(
            3,
            retypeSpan(
                tentativeLength = 3, lastCommitWord = null,
                lastCommitTrailing = "", wordUnderCursor = "carpet",
            ),
        )
        assertEquals(
            6,
            retypeSpan(
                tentativeLength = 0, lastCommitWord = "hello",
                lastCommitTrailing = " ", wordUnderCursor = "hello",
            ),
        )
    }

    @Test
    fun theRunIsWalkedTheSameWayTheReloadWalksIt() {
        // One walk for three questions, so a retype cannot disagree with the reload about
        // where a word starts and delete the wrong thing.
        assertEquals("pimn", trailingLetterRun("car pet pimn"))
        // Apostrophes belong to the word, exactly as the reload treats them.
        assertEquals("l'altro", trailingLetterRun("dico l'altro"))
        assertEquals("don't", trailingLetterRun("don't"))
        // Stops at a space, a delimiter and a digit.
        assertEquals("", trailingLetterRun("hello "))
        assertEquals("", trailingLetterRun("done."))
        assertEquals("", trailingLetterRun("v2"))
        // Start of the field.
        assertEquals("", trailingLetterRun(""))
        // And the end offset is honoured, which is what wordBeforeAutospace needs.
        assertEquals("car", trailingLetterRun("car ", 3))
    }

    @Test
    fun theTraceNamesWhichCaseAnswered() {
        // The reason this defect was invisible in the capture: the retype emitted nothing
        // at all, so the trace could not say that the span had been zero.
        assertEquals("tentative", retypeSource(tentativeLength = 4, lastCommitWord = null))
        assertEquals("commit", retypeSource(tentativeLength = 0, lastCommitWord = "hello"))
        assertEquals("cursor", retypeSource(tentativeLength = 0, lastCommitWord = null))
    }

    @Test
    fun theActionIsReachableByTheNameBothTriggersUse() {
        // The bar button and a ?123 chord both dispatch this string, which is what keeps
        // them one implementation.
        assertNotNull(EditorAction.of("action:retype"))
        assertEquals(EditorAction.RETYPE, EditorAction.of(EditorAction.RETYPE.output))
    }
}
