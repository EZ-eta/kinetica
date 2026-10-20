package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.DictionaryLoader.LearnedCase
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
    fun aTappedWordTakesItsSettledCapital() {
        assertEquals("Dennis", learnedCapital("dennis", LearnedCase("Dennis", 2)))
        assertNull(learnedCapital("dennis", LearnedCase("Dennis", 1)))
        assertNull(learnedCapital("Dennis", LearnedCase("Dennis", 3)))
        assertNull(learnedCapital("dennis", null))
    }
}
