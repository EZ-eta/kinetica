package com.kinetica.keyboard.settings

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A backup import that a closed screen cannot cut short: closing the app mid-import used to abort
 * it. The run and its dialog are device-gate steps; these are the rules under them.
 */
class BackupRunTest {

    private fun data(words: Int, expansions: Int) = Backup.Data(
        prefs = listOf(Backup.Pref("pref_autospace", Backup.PrefType.BOOL, "true")),
        words = List(words) { Backup.Word("en", "w$it", 3) },
        blocked = listOf(Backup.Blocked("en", "teh")),
        chords = listOf(Backup.Chord("v", "action:paste")),
        expansions = List(expansions) { Backup.Expand("vv", it, "t$it") },
        phrases = listOf(Backup.Phrase("en", "i", "am", 4)),
        importedBase = emptyList(),
    )

    @Test
    fun theDenominatorCountsEveryRecordWritten() {
        // Settings are written in one commit and are not records; expansions count per target.
        assertEquals(5_600 + 1 + 1 + 3 + 1, BackupProgress.recordCount(data(5_600, 3)))
    }

    @Test
    fun oneRunAtATime() {
        val p = BackupProgress()
        assertFalse(p.running)
        assertTrue(p.begin())
        assertTrue(p.running)
        assertFalse("a second import started over the first", p.begin())
        p.end()
        assertFalse(p.running)
        assertEquals(1, p.ended)
        assertTrue(p.begin())
    }

    @Test
    fun theCountNeverPassesItsTotal() {
        val p = BackupProgress()
        p.begin()
        assertEquals(0, p.percent())
        p.writing(4)
        repeat(3) { p.advance() }
        assertEquals(75, p.percent())
        p.advance(5)
        assertEquals(4, p.done)
        assertEquals(100, p.percent())
        p.writing(0)
        assertEquals(0, p.percent())
    }

    @Test
    fun aNewRunStartsFromNothing() {
        val p = BackupProgress()
        p.begin()
        p.writing(10)
        p.advance(10)
        p.end()
        p.begin()
        assertEquals(BackupProgress.Phase.READING, p.phase)
        assertEquals(0, p.done)
        assertEquals(0, p.total)
    }

    @Test
    fun theTablesAreOneTransactionAndTheScreenWritesNothing() {
        // The old shape: the screen decoded, wrote ~5 600 separate commits, and posted the
        // settings behind an isDestroyed check that a closed screen failed.
        val run = read("BackupRun.kt")
        assertTrue("no transaction around the import", "runInTransaction" in run)
        assertFalse("settings applied asynchronously", Regex("""\.apply\(\)""").containsMatchIn(run))
        // Both screens that ever held the backup; it now has its own.
        for (name in listOf("DictionarySettingsActivity.kt", "BackupSettingsActivity.kt")) {
            val screen = read(name)
            assertFalse("$name decodes a backup itself", "Backup.decode(" in screen)
            assertFalse("$name writes a backup's settings itself", "ok.data.prefs" in screen)
        }
    }

    private fun read(name: String): String {
        val rel = "src/main/java/com/kinetica/keyboard/settings/$name"
        val p: Path = listOf(Paths.get(rel), Paths.get("app/$rel")).first { Files.exists(it) }
        return Files.newBufferedReader(p).use { it.readText() }
    }
}
