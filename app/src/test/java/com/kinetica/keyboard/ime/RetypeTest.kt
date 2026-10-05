package com.kinetica.keyboard.ime

import com.kinetica.keyboard.keys.EditorAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a retype throws away.
 *
 * Pure, like this package's other rules, because the service has no JVM reach. The delete, the
 * abandon and the cursor are one call each and are checked on a device; the span is what can go
 * wrong silently, so it is decided here.
 */
class RetypeTest {

    @Test
    fun theWordInProgressIsWhatGoes() {
        // The reading the request suggests: "deletes the current word and starts again in
        // its place".
        assertEquals(5, retypeSpan(tentativeLength = 5, commitSpan = -1, wordUnderCursor = ""))
    }

    @Test
    fun theWordInProgressWinsOverTheOneBeforeIt() {
        // Both can be set at once, since the correction strip survives a commit while the next
        // word is written; the one under the thumb is the one meant.
        assertEquals(
            3,
            retypeSpan(tentativeLength = 3, commitSpan = commitSpan("hello ", "hello", 8), wordUnderCursor = ""),
        )
    }

    @Test
    fun withNothingInProgressTheLastCommittedWordGoesWithItsSpace() {
        // The common case: the autospace commits fast, so by the time a wrong word is noticed
        // there is usually no word in progress. The trailing space is the keyboard's own, and
        // leaving it would put the retyped word one space further along.
        assertEquals(
            6,
            retypeSpan(tentativeLength = 0, commitSpan = commitSpan("hello ", "hello", 8), wordUnderCursor = ""),
        )
    }

    @Test
    fun aCommittedWordWithNoTrailingTextIsJustTheWord() {
        // Punctuation eats the autospace, so the trailing text can be a mark or nothing. The
        // mark is read from the editor, so these supply the text the editor holds.
        assertEquals(
            5,
            retypeSpan(tentativeLength = 0, commitSpan = commitSpan("hello", "hello", 8), wordUnderCursor = ""),
        )
        assertEquals(
            6,
            retypeSpan(tentativeLength = 0, commitSpan = commitSpan("hello.", "hello", 8), wordUnderCursor = ""),
        )
    }

    @Test
    fun aCommittedWordTheEditorNoLongerHoldsFallsThroughToTheRun() {
        // The third case: a remembered length made a retype at `be?` delete `e?`
        // and leave `b`. A refused span hands the question to the run under the cursor, which
        // re-reads the text.
        assertEquals(
            4,
            retypeSpan(
                tentativeLength = 0, commitSpan = commitSpan("car pet pimn", "hello", 8),
                wordUnderCursor = "pimn",
            ),
        )
        assertEquals("cursor", retypeSource(tentativeLength = 0, commitSpan = -1))
    }

    @Test
    fun withNothingTheKeyboardKnowsItReadsTheRunUnderTheCursor() {
        // The case the button exists for. The stale-buffer timeout closes an undecodable
        // buffer, zeroing the tentative and nulling the last commit while the letters stay on
        // screen, so both cases above report nothing when the text is garbage such as `pimn`.
        assertEquals(
            4,
            retypeSpan(
                tentativeLength = 0, commitSpan = -1, wordUnderCursor = "pimn",
            ),
        )
    }

    @Test
    fun withNoRunEitherItStillDeletesNothing() {
        // A cursor at the start of a field, or after a space or a delimiter: there is no
        // word anywhere and a retype is a gesture the user will repeat.
        assertEquals(
            0,
            retypeSpan(
                tentativeLength = 0, commitSpan = -1, wordUnderCursor = "",
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
                tentativeLength = 3, commitSpan = -1, wordUnderCursor = "carpet",
            ),
        )
        assertEquals(
            6,
            retypeSpan(
                tentativeLength = 0, commitSpan = commitSpan("hello ", "hello", 8),
                wordUnderCursor = "hello",
            ),
        )
    }

    @Test
    fun theRunIsWalkedTheSameWayTheReloadWalksIt() {
        // One walk for three questions, so a retype cannot disagree with the reload about
        // where a word starts and delete the wrong thing.
        assertEquals("pimn", trailingLetterRun("car pet pimn"))
        // Apostrophes belong to the word, as the reload treats them.
        assertEquals("l'altro", trailingLetterRun("dico l'altro"))
        assertEquals("don't", trailingLetterRun("don't"))
        // Stops at a space, a delimiter and a digit.
        assertEquals("", trailingLetterRun("hello "))
        assertEquals("", trailingLetterRun("done."))
        assertEquals("", trailingLetterRun("v2"))
        // Start of the field.
        assertEquals("", trailingLetterRun(""))
        // The end offset is honoured, as wordBeforeAutospace needs.
        assertEquals("car", trailingLetterRun("car ", 3))
    }

    @Test
    fun theTraceNamesWhichCaseAnswered() {
        // A retype that emits nothing leaves a zero span invisible in the trace.
        assertEquals("tentative", retypeSource(tentativeLength = 4, commitSpan = -1))
        assertEquals("commit", retypeSource(tentativeLength = 0, commitSpan = 6))
        assertEquals("cursor", retypeSource(tentativeLength = 0, commitSpan = -1))
    }

    @Test
    fun theActionIsReachableByTheNameBothTriggersUse() {
        // The bar button and a ?123 chord both dispatch this string, so they share one
        // implementation.
        assertNotNull(EditorAction.of("action:retype"))
        assertEquals(EditorAction.RETYPE, EditorAction.of(EditorAction.RETYPE.output))
    }

    @Test
    fun onlyTheCommitCaseHasAWordToTakeBack() {
        // A retype says the last commit was wrong, so the word gives back the personal weight
        // that commit earned. Kept, it made a fought word stronger: `biologa` climbed
        // pb 1.10 -> 1.24 across one capture while being retyped over and over.
        //
        // Only the commit case names a word. The tentative case has nothing committed yet,
        // and the cursor case is a run of letters the keyboard has no record of deciding,
        // so neither may guess at what to unlearn.
        assertEquals("commit", retypeSource(tentativeLength = 0, commitSpan = 8))
        assertEquals("tentative", retypeSource(tentativeLength = 7, commitSpan = 8))
        assertEquals("cursor", retypeSource(tentativeLength = 0, commitSpan = -1))
    }

    @Test
    fun aCommitTheStripDidNotShowIsStillRetypeable() {
        // A lone candidate raises no correction strip, so a retype that read its word off the
        // strip found none, fell to the run under the cursor, met the autospace and deleted
        // nothing: eleven presses in a row on one capture.
        val memory = CommitMemory()
        memory.onCommit("provarne", stripShown = false)
        assertEquals(null, memory.stripWord)
        val before = "il proverbio provarne "
        val committed = memory.retypeWord
        assertNotNull("a lone-candidate commit must stay retypeable", committed)
        val span = retypeSpan(
            tentativeLength = 0,
            commitSpan = commitSpan(before, committed!!, 8),
            wordUnderCursor = trailingLetterRun(before),
        )
        assertEquals(9, span)
        // Without the remembered commit the press finds nothing to take back.
        assertEquals(0, retypeSpan(tentativeLength = 0, commitSpan = -1, wordUnderCursor = trailingLetterRun(before)))
    }

    @Test
    fun theStripAndTheRetypeFollowARewriteAndClearTogether() {
        val memory = CommitMemory()
        memory.onCommit("be", stripShown = true)
        memory.onReplaced("BE")
        assertEquals("BE", memory.stripWord)
        assertEquals("BE", memory.retypeWord)
        // A rewrite never raises a strip that was not up.
        memory.onCommit("provarne", stripShown = false)
        memory.onReplaced("PROVARNE")
        assertEquals(null, memory.stripWord)
        assertEquals("PROVARNE", memory.retypeWord)
        memory.clear()
        assertEquals(null, memory.retypeWord)
    }

    @Test
    fun aRetypeOffersTheGesturesOtherCandidatesNeverTheRejectedOne() {
        // After retype takes `liver` back, the bar offers
        // what else the gesture decoded, so a tap fixes it without re-swiping.
        val memory = CommitMemory()
        memory.onCommit(
            "liver", stripShown = true,
            alternatives = listOf("believe", "Liver", "lover", "believe", "lever"),
            languages = mapOf("believe" to "en", "lover" to "en"),
        )
        assertEquals(listOf("believe", "lover", "lever"), memory.offerAfterRetype("liver", 10))
        assertEquals(listOf("believe", "lover"), memory.offerAfterRetype("liver", 2))
        assertEquals("en", memory.languages["believe"])
        memory.clear()
        assertTrue(memory.offerAfterRetype("liver", 10).isEmpty())
    }

    @Test
    fun aCommitWithNoAlternativesOffersNothing() {
        val memory = CommitMemory()
        memory.onCommit("provarne", stripShown = false)
        assertTrue(memory.offerAfterRetype("provarne", 10).isEmpty())
    }

    @Test
    fun everyActionRoundTripsThroughItsOwnName() {
        // The chord picker builds its list from EditorAction.entries and dispatches the
        // selected entry's output, so an action whose name does not survive `of` would be
        // offered in settings and then inserted as text.
        for (a in EditorAction.entries) {
            assertEquals("$a does not survive its own name", a, EditorAction.of(a.output))
            assertTrue("${a.output} must start with the reserved prefix", a.output.startsWith(EditorAction.PREFIX))
        }
        assertEquals(EditorAction.entries.size, EditorAction.entries.map { it.output }.toSet().size)
    }
}
