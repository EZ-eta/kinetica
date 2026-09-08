package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.models.WordCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the retype button does to the word it just rejected.
 *
 * Priced before it was built (KNOWN_ISSUES item 56): over 78 retype presses in one capture,
 * 12 handed back the word just rejected, eleven of them one chain, and an alternate was
 * always available. But the word the user actually wanted was among those alternates in only
 * 5 of the 12. So this demotes rather than hides, and it is off by default.
 */
class RetypeDemoteTest {

    private fun cand(word: String, score: Float) = WordCandidate(
        word, score, 0.2f, 0.5f, 1f, 0, WordCandidate.Source.MERGED, "en",
    )

    private val list = listOf(cand("word", 0.9f), cand("world", 0.7f), cand("wold", 0.4f))

    @Test
    fun theRejectedWordGoesLastAndTheRunnerUpLeads() {
        val out = demoteRejected(list, "word")
        assertEquals(listOf("world", "wold", "word"), out.map { it.word })
    }

    @Test
    fun theRejectedWordIsStillReachable() {
        // The hazard item 56 names: a retype aimed at fixing a SPACE rather than a word
        // must not be denied the word it had. Demoted, never dropped.
        val out = demoteRejected(list, "word")
        assertEquals(list.size, out.size)
        assertTrue("word must survive the demotion", out.any { it.word == "word" })
    }

    @Test
    fun nothingArmedLeavesTheListUntouched() {
        // Identity, not equality: the caller reads `!==` to decide whether to trace.
        assertSame(list, demoteRejected(list, null))
    }

    @Test
    fun aWordThatIsNotInTheListLeavesItUntouched() {
        assertSame(list, demoteRejected(list, "elephant"))
    }

    @Test
    fun aSingleCandidateIsNeverDemoted() {
        // Demoting the only candidate would promote nothing and empty the lead.
        val one = listOf(cand("word", 0.9f))
        assertSame(one, demoteRejected(one, "word"))
    }

    @Test
    fun caseDoesNotHideTheRejectedWord() {
        val capitalised = listOf(cand("Word", 0.9f), cand("world", 0.7f))
        assertEquals(listOf("world", "Word"), demoteRejected(capitalised, "word").map { it.word })
    }

    @Test
    fun anEmptyListIsReturnedAsItIs() {
        val none = emptyList<WordCandidate>()
        assertSame(none, demoteRejected(none, "word"))
    }
}
