package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.KineticaConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A learn reads its stored row back behind the write and corrects a live count that reads short.
 *
 * A load reads the top 5 000 rows and every row counted 2 or more, so a word at 1 past the top is
 * never read: in Italian, with more than 5 000 rows at 2 or more, every count-1 word. Its next learn
 * counted 0 to 1 live while the row went to 2, so it never crossed the merge floor and asked no
 * reload. On one gate every `learn count` line read `live=1 stored=2` (`tetto`, `improvvisamente`).
 */
class LearnCountTest {

    private val floor = KineticaConstants.PERSONAL_MERGE_MIN_COUNT
    private val word = "tetto"

    @Test
    fun aCountOneRowPastTheTopIsCorrectedAndAsksItsReload() {
        // The load left the row at 1 out: live 0 to 1, the row 1 to 2.
        val before = 0
        val after = 1
        val gap = learnedCountGap(after, stored = 2)
        assertEquals(1, gap)
        assertFalse("uncorrected, no reload", userDictNeedsReload(before, after, trieHasWord = false))
        assertTrue(userDictNeedsReload(before + gap, after + gap, trieHasWord = false))
        assertEquals(floor, after + gap)
    }

    @Test
    fun aRowThatAgreesOrReadsLowOrFailsCorrectsNothing() {
        assertEquals(0, learnedCountGap(3, stored = 3))
        assertEquals(0, learnedCountGap(3, stored = 2))
        assertEquals(0, learnedCountGap(1, stored = null))
    }

    @Test
    fun withNoLoadInFlightTheLiveCountTakesTheGap() {
        val journal = LearnJournal()
        val mark = journal.mark()
        assertTrue(journal.correct(mark, "it", word, 1))
    }

    @Test
    fun aLoadOpenedBeforeTheLearnReplaysTheGap() {
        val journal = LearnJournal()
        val load = journal.open()
        // The learn: its amount goes into the journal, then its mark.
        journal.note("it", word, 1)
        val mark = journal.mark()
        assertTrue("no newer install: the live map lacks it", journal.correct(mark, "it", word, 1))
        // The load read its rows before the write, without the row: the replay brings the row's count.
        val counts = HashMap<String, Int>()
        val stale = replayLearns(counts, "it", journal.take(load)!!) { false }
        assertEquals(floor, counts[word])
        assertEquals(listOf(word), stale)
    }

    @Test
    fun aLoadOpenedAfterTheLearnReadsTheRowAndGetsNoGap() {
        val journal = LearnJournal()
        val mark = journal.mark()
        val load = journal.open()
        assertTrue(journal.correct(mark, "it", word, 1))
        assertTrue("the load reads the row itself", journal.take(load)!!.isEmpty())
    }

    @Test
    fun aNewerInstallBeforeTheCorrectionLeavesTheLiveMapAlone() {
        val journal = LearnJournal()
        val mark = journal.mark()
        val load = journal.open()
        journal.take(load)!!
        journal.installed(load)
        // That install read the row at 2: adding the gap would count it twice.
        assertFalse(journal.correct(mark, "it", word, 1))
    }

    @Test
    fun anOlderInstallBeforeTheCorrectionStillNeedsIt() {
        val journal = LearnJournal()
        val load = journal.open()
        journal.note("it", word, 1)
        val mark = journal.mark()
        val learns = journal.take(load)!!
        journal.installed(load)
        assertEquals(1, learns.size)
        // Its rows lacked the row and its replay held the amount only: the live map is short.
        assertTrue(journal.correct(mark, "it", word, 1))
    }
}
