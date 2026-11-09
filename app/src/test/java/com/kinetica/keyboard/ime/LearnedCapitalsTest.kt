package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.DictionaryLoader.LearnedCase
import com.kinetica.keyboard.engine.KineticaConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a commit says about a word's capital (#28): only a choice against what the keyboard
 * proposed, mid-sentence, and never a capital the dictionary holds as written.
 */
class LearnedCapitalsTest {

    private val mid = "I met "

    private fun vote(
        committed: String,
        proposal: String?,
        held: Boolean = false,
        before: CharSequence? = mid,
        learned: String? = null,
        lang: String = "en",
    ) = caseVote(committed, proposal, held, before, learned, lang)

    @Test
    fun aCapitalWrittenOverALowercaseProposalVotesForIt() {
        assertEquals(CaseVote(1, "Dennis"), vote("Dennis", "dennis"))
        assertEquals(CaseVote(1, "GitHub"), vote("GitHub", "github", before = "on "))
        // Nothing proposed (a pick from an offer): still the user's capital.
        assertEquals(CaseVote(1, "Dennis"), vote("Dennis", null))
    }

    @Test
    fun committingWhatTheKeyboardProposedSaysNothing() {
        // The ratchet: a learned capital, written because it leads, must not vote for itself.
        assertEquals(CaseVote.NONE, vote("Dennis", "Dennis", held = true, learned = "Dennis"))
        assertEquals(CaseVote.NONE, vote("dennis", "dennis"))
    }

    @Test
    fun aLowercaseWordWrittenOverALearnedCapitalVotesAgainstIt() {
        assertEquals(CaseVote(-1, null), vote("dennis", "Dennis", learned = "Dennis"))
        assertEquals(CaseVote.NONE, vote("mark", "mark"))
    }

    @Test
    fun aSentenceStartSaysNothing() {
        assertEquals(CaseVote.NONE, vote("Dennis", "dennis", before = ""))
        assertEquals(CaseVote.NONE, vote("Dennis", "dennis", before = "Hi. "))
        assertEquals(CaseVote.NONE, vote("dennis", "Dennis", before = "Hi. ", learned = "Dennis"))
        assertEquals(CaseVote.NONE, vote("Dennis", "dennis", before = null))
    }

    @Test
    fun aCamelCaseWordVotesAtASentenceStart() {
        // The first capital is the shift's, the inner ones are not: a company name written first
        // in a message, or in an empty field, is the user's spelling.
        assertEquals(CaseVote(1, "FakeCompanyName"), vote("FakeCompanyName", "fakecompanyname", before = ""))
        assertEquals(CaseVote(1, "GitHub"), vote("GitHub", "github", before = "Done. "))
        assertEquals(CaseVote(1, "iPhone"), vote("iPhone", "iphone", before = "\n"))
        // Still only against the keyboard: the settled capital proposed and written votes nothing.
        assertEquals(CaseVote.NONE, vote("FakeCompanyName", "FakeCompanyName", before = ""))
    }

    @Test
    fun aTitleCaseWordAtASentenceStartDoesNot() {
        assertEquals(CaseVote.NONE, vote("Fakecompanyname", "fakecompanyname", before = ""))
        assertEquals(CaseVote.NONE, vote("NASA", "nasa", before = ""))
        assertEquals(CaseVote.NONE, vote("I'm", "i'm", before = ""))
        assertFalse(hasInnerCapital("Hello"))
        assertFalse(hasInnerCapital("NASA"))
        assertTrue(hasInnerCapital("FakeCompanyName"))
        assertTrue(hasInnerCapital("iPhone"))
    }

    @Test
    fun aVoteOfNothingNamesItsRule() {
        assertEquals("sentence", vote("Dennis", "dennis", before = "").why)
        assertEquals("unread", vote("Dennis", "dennis", before = null).why)
        assertEquals("short", vote("A", "a").why)
        assertEquals("caps", vote("NASA", "nasa").why)
        assertEquals("auto", vote("I'm", "i'm", before = "so ").why)
        assertEquals("held", vote("Haus", null, held = true, before = "das ", lang = "de").why)
        assertEquals("proposed", vote("Dennis", "Dennis", learned = "Dennis").why)
        assertEquals("lower", vote("mark", "mark").why)
        // A vote carries no reason.
        assertEquals("", vote("Dennis", "dennis").why)
    }

    @Test
    fun capitalsThatAreNotTheUsersChoiceSayNothing() {
        // The dictionary's own spellings: a German noun, and `Sie` beside `sie`.
        assertEquals(CaseVote.NONE, vote("Haus", null, held = true, before = "das ", lang = "de"))
        assertEquals(CaseVote.NONE, vote("Sie", "sie", held = true, before = "dass ", lang = "de"))
        // Shouting, and English I.
        assertEquals(CaseVote.NONE, vote("NASA", "nasa"))
        assertEquals(CaseVote.NONE, vote("I'm", "i'm", before = "so "))
    }

    @Test
    fun aNewSpellingContestsASettledCapitalInsteadOfTakingItsPlace() {
        val mark = LearnedCase("Mark", 3)
        assertEquals(LearnedCase("Mark", 2), nextCase(mark, CaseVote(1, "MArk")))
        // An unsettled one is replaced.
        assertEquals(LearnedCase("GitHub", 1), nextCase(LearnedCase("Github", 1), CaseVote(1, "GitHub")))
        assertEquals(LearnedCase("Dennis", 1), nextCase(null, CaseVote(1, "Dennis")))
        assertEquals(LearnedCase("Dennis", 2), nextCase(LearnedCase("Dennis", 1), CaseVote(1, "Dennis")))
        // A vote against a word with no capital changes nothing.
        assertNull(nextCase(null, CaseVote(-1, null)))
    }

    @Test
    fun theLeadChangesOnlyWhenTheFloorIsCrossedOrTheSpellingMoves() {
        assertTrue(leadChanged(LearnedCase("Dennis", 1), LearnedCase("Dennis", 2)))
        assertTrue(leadChanged(LearnedCase("Dennis", 2), LearnedCase("Dennis", 1)))
        assertFalse(leadChanged(LearnedCase("Dennis", 2), LearnedCase("Dennis", 3)))
        assertFalse(leadChanged(null, LearnedCase("Dennis", 1)))
    }

    @Test
    fun aLowercaseCommitCountsAgainstAnUnsettledCapital() {
        // Not proposed, since it has not settled: the lowercase is still a use of the word.
        assertEquals(CaseVote(-1, null), vote("ma", "ma", before = "nulla, ", learned = "Ma", lang = "it"))
        assertEquals(LearnedCase("Ma", 0), nextCase(LearnedCase("Ma", 1), CaseVote(-1, null)))
        // A sentence start still says nothing either way.
        assertEquals(CaseVote.NONE, vote("ma", "ma", before = "Ok. ", learned = "Ma", lang = "it"))
    }

    @Test
    fun strayCapitalsAmongLowercaseUseNeverSettle() {
        // A common word: a stray capital now and then (a shift kept by a fragment), `ma` in between.
        var case: LearnedCase? = null
        fun commit(word: String) {
            val v = vote(word, "ma", before = "quel ", learned = case?.display, lang = "it")
            case = nextCase(case, v) ?: case
        }
        commit("Ma")
        commit("ma")
        commit("ma")
        commit("Ma")
        assertFalse(settled(case))
        assertNull(learnedCapital("ma", case))
        // A name written with its capital twice, nothing lowercase between, still settles.
        case = null
        commit("Ma")
        commit("Ma")
        assertTrue(settled(case))
    }

    @Test
    fun forgetDropsALearnedCapitalToTheFloor() {
        val floor = LearnedCase("Ma", KineticaConstants.CASE_VOTES_LOW)
        assertEquals(floor, nextCase(LearnedCase("Ma", KineticaConstants.CASE_VOTES_HIGH), CaseVote.FORGET))
        assertEquals(floor, nextCase(LearnedCase("Ma", 2), CaseVote.FORGET))
        // Already there, or nothing learned: nothing to write.
        assertNull(nextCase(floor, CaseVote.FORGET))
        assertNull(nextCase(null, CaseVote.FORGET))
    }

    @Test
    fun aVoteThatChangesNothingIsNoChange() {
        val top = LearnedCase("Dennis", KineticaConstants.CASE_VOTES_HIGH)
        assertNull(nextCase(top, CaseVote(1, "Dennis")))
        assertNull(nextCase(LearnedCase("Ma", KineticaConstants.CASE_VOTES_LOW), CaseVote(-1, null)))
    }

    @Test
    fun aRecasedWordKeepsItsCase() {
        assertNull(learnedCapital("ma", LearnedCase("Ma", 3), chosen = true))
        assertEquals("Ma", learnedCapital("ma", LearnedCase("Ma", 3), chosen = false))
    }

    @Test
    fun maStaysLowercaseAfterShiftDown() {
        // Italian `ma` with a settled `Ma`: shift-down on it, then `ma` tapped again.
        val forgotten = nextCase(LearnedCase("Ma", 2), CaseVote.FORGET)
        assertFalse(settled(forgotten))
        assertNull(learnedCapital("ma", forgotten))
        // Coming back takes more capital commits than lowercase ones, from the floor.
        var case = forgotten
        repeat(KineticaConstants.LEARNED_CAPITAL_MIN_VOTES - KineticaConstants.CASE_VOTES_LOW - 1) {
            case = nextCase(case, CaseVote(1, "Ma"))
        }
        assertFalse(settled(case))
    }

    @Test
    fun aTappedWordTakesItsSettledCapital() {
        assertEquals("Dennis", learnedCapital("dennis", LearnedCase("Dennis", 2)))
        assertNull(learnedCapital("dennis", LearnedCase("Dennis", 1)))
        assertNull(learnedCapital("Dennis", LearnedCase("Dennis", 3)))
        assertNull(learnedCapital("dennis", null))
    }
}
