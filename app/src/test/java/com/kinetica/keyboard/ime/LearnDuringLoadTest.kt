package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.KineticaConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A word learned while a dictionary load is parsed or held keeps its count through the install.
 *
 * On one capture a load was parsed, held under a word, and installed 3 ms after the first learn of
 * `uncharacteristically`: the install put back the counts read at parse time and the learn was
 * gone from memory. The second learn counted 0 to 1, so the word never crossed the merge floor and
 * never reloaded, while the database held 2.
 */
class LearnDuringLoadTest {

    private val floor = KineticaConstants.PERSONAL_MERGE_MIN_COUNT
    private val word = "uncharacteristically"

    @Test
    fun aLearnWhileTheInstallIsHeldKeepsItsCount() {
        val journal = LearnJournal()
        val load = journal.open()
        // The rows were read before the learn: the word is not among them.
        val counts = HashMap<String, Int>()
        journal.note("en", word, 1)
        val stale = replayLearns(counts, "en", journal.take(load)!!) { false }
        assertEquals(1, counts[word])
        assertTrue("one learn is below the floor", stale.isEmpty())
        // The next learn now crosses the floor and asks its reload.
        val before = counts.getValue(word)
        assertTrue(userDictNeedsReload(before, before + 1, trieHasWord = false))
    }

    @Test
    fun aLearnCrossingTheFloorDuringALoadAsksItsReload() {
        val journal = LearnJournal()
        val load = journal.open()
        val counts = hashMapOf(word to floor - 1)
        journal.note("en", word, 1)
        assertEquals(listOf(word), replayLearns(counts, "en", journal.take(load)!!) { false })
        assertEquals(floor, counts[word])
    }

    @Test
    fun aWordTheNewTrieHoldsAsksNoReload() {
        val journal = LearnJournal()
        val load = journal.open()
        val counts = hashMapOf(word to floor - 1)
        journal.note("en", word, 1)
        assertTrue(replayLearns(counts, "en", journal.take(load)!!) { it == word }.isEmpty())
        assertEquals(floor, counts[word])
    }

    @Test
    fun aLearnBeforeTheLoadIsInItsRowsAndNotAddedTwice() {
        val journal = LearnJournal()
        journal.note("en", word, 1)
        val load = journal.open()
        val counts = hashMapOf(word to 1)
        replayLearns(counts, "en", journal.take(load)!!) { false }
        assertEquals(1, counts[word])
    }

    @Test
    fun anUnlearnDuringTheLoadIsKeptAndStopsAtZero() {
        val journal = LearnJournal()
        val load = journal.open()
        val counts = hashMapOf(word to 1)
        journal.note("en", word, -1)
        journal.note("en", word, -1)
        journal.note("en", word, 1)
        replayLearns(counts, "en", journal.take(load)!!) { false }
        // As the live map and the database clamp each step: 1, 0, 0, then 1.
        assertEquals(1, counts[word])
    }

    @Test
    fun aLearnFiledInAnotherListLeavesThisOneAlone() {
        val journal = LearnJournal()
        val load = journal.open()
        val en = HashMap<String, Int>()
        val it = HashMap<String, Int>()
        journal.note("it", "appuntamento", 1)
        val learns = journal.take(load)!!
        replayLearns(en, "en", learns) { false }
        replayLearns(it, "it", learns) { false }
        assertTrue(en.isEmpty())
        assertEquals(1, it["appuntamento"])
    }

    @Test
    fun anInstallClosesItsLoadAndEveryOlderOne() {
        val journal = LearnJournal()
        val older = journal.open()
        val newer = journal.open()
        journal.note("en", word, 1)
        assertEquals(1, journal.take(newer)!!.size)
        // The older load can no longer install over the newer: closed, its install stops.
        assertNull(journal.take(older))
        journal.note("en", word, 1)
        assertNull("no load open, nothing kept", journal.take(newer))
    }

    @Test
    fun anOlderParsePostedAfterANewerOneNeverInstalls() {
        // Two parses race to the main thread: the newer one posts first.
        val journal = LearnJournal()
        val older = journal.open()
        val newer = journal.open()
        journal.note("en", word, 1)
        assertTrue(journal.supersede(newer))
        assertEquals(1, journal.take(newer)!!.size)
        // The older post lands after: refused before it touches the blocked set or the counts.
        assertFalse(journal.supersede(older))
        assertNull(journal.take(older))
    }

    @Test
    fun aNewerParseHeldUnderAWordStopsTheOlderOne() {
        val journal = LearnJournal()
        val held = HeldInstall()
        val ran = mutableListOf<String>()
        val older = journal.open()
        val newer = journal.open()
        // The newer parse posts first and is held under a word.
        if (journal.supersede(newer)) held.offer(wordPending = true) { journal.take(newer)?.let { ran += "new" } }
        // The older one posts while the word is still pending: it must not replace the held one.
        if (journal.supersede(older)) held.offer(wordPending = true) { journal.take(older)?.let { ran += "old" } }
        held.release(wordPending = false)
        assertEquals(listOf("new"), ran)
    }

    @Test
    fun anOlderParsePostedFirstLeavesTheNewerOneOpen() {
        val journal = LearnJournal()
        val older = journal.open()
        val newer = journal.open()
        journal.note("en", word, 1)
        assertTrue(journal.supersede(older))
        assertTrue("the newer load is still open", journal.supersede(newer))
        // The newer post closed the older one before it could install.
        assertNull(journal.take(older))
        assertEquals(1, journal.take(newer)!!.size)
    }

    @Test
    fun theInstallIsStoppedBeforeItTouchesAnything() {
        val src = ImeSources.declaring("internal class ResidentDictionaries").text
        val load = src.substring(src.indexOf("fun load("), src.indexOf("fun releaseInstall("))
        val guard = load.indexOf("if (!learnJournal.supersede(journal))")
        assertTrue("no guard on the install post", guard >= 0)
        assertTrue(guard < load.indexOf("blockedSpellings = allBlocked"))
        assertTrue(guard < load.indexOf("heldInstall.offer("))
        assertTrue("a closed load still installs", load.contains("learnJournal.take(journal) ?: return@install"))
    }

    @Test
    fun aNewerLoadKeepsItsLearnsWhenAnOlderOneInstalls() {
        val journal = LearnJournal()
        val older = journal.open()
        val newer = journal.open()
        journal.note("en", word, 1)
        assertEquals(1, journal.take(older)!!.size)
        assertEquals(1, journal.take(newer)!!.size)
    }

    @Test
    fun theLoadReadsItsRowsBehindTheLearnsAlreadyQueued() {
        // The rows are read on the database thread, queued when the journal opens: a learn
        // before it is written before the read, a learn after it is journaled. Read anywhere
        // else, a learn written between the open and the read would count twice.
        val src = ImeSources.declaring("internal class ResidentDictionaries").text
        val load = src.substring(src.indexOf("fun load("), src.indexOf("fun releaseInstall("))
        val open = load.indexOf("learnJournal.open()")
        val queued = load.indexOf("dbExecutor.execute(")
        assertTrue("the load opens no journal", open >= 0)
        assertTrue("the rows are not read on the database thread", queued > open)
        assertTrue("the install replays nothing", load.contains("learnJournal.take("))
        assertEquals("the active, second and third counts replayed", 3, Regex("""replayLearns\(""").findAll(load).count())
        val learner = ImeSources.declaring("internal class Learner(").text
        for (fn in listOf("fun learnWord(", "fun unlearnWord(")) {
            val body = learner.substring(learner.indexOf(fn)).let { it.substring(0, it.indexOf("\n    }\n")) }
            assertTrue("$fn does not journal its count", body.contains("residents.noteLearn("))
        }
    }
}
