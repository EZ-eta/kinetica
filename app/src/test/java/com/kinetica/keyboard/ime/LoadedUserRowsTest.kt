package com.kinetica.keyboard.ime

import com.kinetica.keyboard.data.UserWord
import com.kinetica.keyboard.engine.DictionaryLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Paths

/**
 * A load keeps every learned capital, not only those among the most used rows.
 *
 * `pippo` and `lorro` settled at votes=2 in a large Italian list; the next reload read the top
 * rows only, rebuilt the capital mirror without them, and the next tapped `pippo` stayed
 * lowercase with no vote. The read needs a database, so the reads are guarded by a source scan.
 */
class LoadedUserRowsTest {

    private fun row(word: String, count: Int, display: String? = null, votes: Int = 0) =
        UserWord(word, "it", count, 0L, display, votes)

    @Test
    fun aSettledCapitalPastTheLimitStillLoads() {
        // The limit is full of older words at the same count: the newly settled one is not among them.
        val top = (0 until 5000).map { row("parola$it", 2) }
        val pippo = row("pippo", 2, "Pippo", 2)
        val rows = loadedUserRows(top, listOf(pippo))
        val cases = DictionaryLoader.learnedCases(rows.map { Triple(it.word, it.display, it.caseVotes) })
        assertEquals("Pippo", learnedCapital("pippo", cases["pippo"]))
        assertTrue(rows.any { it.word == "pippo" && it.frequency == 2 })
    }

    @Test
    fun aWordOverTheMergeFloorPastTheLimitReachesTheTrie() {
        // `lorro`, count 2, no capital, behind 5 000 older rows at its count: never read, never
        // merged, undecodable until its count beat the 5 000th row.
        val top = (0 until 5000).map { row("parola$it", 2) }
        val lorro = row("lorro", 2)
        val rows = loadedUserRows(top, emptyList(), top + lorro)
        assertEquals(5001, rows.size)
        val extra = DictionaryLoader.userWordsForMerge(rows.map { it.word to it.frequency })
        val dict = DictionaryLoader.load(BufferedReader(StringReader("ciao\t900000\n")), extra)
        assertTrue("lorro must merge into the trie", dict.trie.contains("lorro"))
    }

    @Test
    fun aWordPastTheLimitKeepsItsCountAndAsksNoReload() {
        // The install rebuilds the live counts from the rows read. Read as none, the word's next
        // learn went 0 to 1, the one after crossed the floor again, and every second commit asked
        // a full reload that still left it out.
        val top = (0 until 5000).map { row("parola$it", 3) }
        val rows = loadedUserRows(top, emptyList(), top + row("lorro", 2))
        val counts = rows.associate { it.word to it.frequency }
        val before = counts["lorro"] ?: 0
        assertEquals(2, before)
        assertFalse(userDictNeedsReload(before, before + 1, trieHasWord = true))
    }

    @Test
    fun aRowInEveryReadLoadsOnce() {
        val lorro = row("lorro", 3, "Lorro", 2)
        val rows = loadedUserRows(listOf(row("ma", 40), lorro), listOf(lorro), listOf(row("ma", 40), lorro, row("pippo", 2)))
        assertEquals(listOf("ma", "lorro", "pippo"), rows.map { it.word })
    }

    @Test
    fun aRowInBothReadsLoadsOnce() {
        val lorro = row("lorro", 3, "Lorro", 2)
        val rows = loadedUserRows(listOf(row("ma", 40), lorro), listOf(lorro))
        assertEquals(listOf("ma", "lorro"), rows.map { it.word })
    }

    @Test
    fun noCapitalsLeavesTheTopRowsAsTheyAre() {
        val top = listOf(row("ma", 40), row("ciao", 9))
        assertSame(top, loadedUserRows(top, emptyList()))
        assertFalse(loadedUserRows(top, emptyList()).any { it.display != null })
    }

    @Test
    fun everyResidentLoadReadsTheCapitalsToo() {
        // Read off disk: a fail-first check here needs `--rerun-tasks`.
        val src = ImeSources.declaring("internal class ResidentDictionaries(").text
        assertFalse("a load reads the top rows alone", src.contains("userWords().topN("))
        // One read for the active, second and third lists alike, on the database thread.
        assertTrue(src.contains("listOfNotNull(lang, detectLang, extraLang).associateWith { readUserRows(it, phrases) }"))
        assertEquals("one place reads a list's rows", 1, Regex("""userRowsFor\(lang\)""").findAll(src).count())
        assertTrue(src.contains("dao.withCapitals(lang, USER_DICT_LIMIT)"))
        assertTrue(src.contains("dao.mergeable(lang, KineticaConstants.PERSONAL_MERGE_MIN_COUNT, USER_MERGE_LIMIT)"))
        assertTrue(src.contains("loadedUserRows(top, capitals, mergeable)"))
        // Each list's row counts reach the DEV trace at install.
        assertTrue(src.contains("\"  userdict rows lang=${'$'}l ${'$'}{r.tally}\""))
    }

    @Test
    fun theNewestWordWinsATieAtTheLimit() {
        // Room has no JVM reach: the three reads are guarded in the DAO's source. At an equal
        // count an unordered tie dropped whichever word SQLite listed last.
        val rel = "src/main/java/com/kinetica/keyboard/data/UserWordDao.kt"
        val f = listOf(Paths.get(rel), Paths.get("app/$rel")).firstOrNull { Files.exists(it) }
        assumeTrue("UserWordDao.kt not found", f != null)
        val src = String(Files.readAllBytes(f!!))
        for (fn in listOf("topN", "withCapitals", "mergeable")) {
            val query = Regex("""@Query\(\s*("[^)]*")\s*,?\s*\)\s*fun $fn\(""").find(src)?.groupValues?.get(1)
            assertTrue("$fn has no query", query != null)
            assertTrue("$fn must break ties by the newest row: $query", query!!.contains("frequency DESC, updatedAt DESC"))
        }
        assertTrue(src.contains("frequency >= :floor"))
    }
}
