package com.kinetica.keyboard.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The symbol pages' rows, a pref a row, reach the config by page; absent keeps a built-in row. */
class SymbolRowPrefsTest {

    @Test
    fun eachPageReadsItsOwnThreeRows() {
        val config = MapPrefs.config(
            mapOf(
                Prefs.symbolRowKey(1, 2) to "! ?",
                Prefs.symbolRowKey(2, 3) to "~ ^",
            ),
        )
        assertEquals(listOf(null, "! ?", null), config.symbolRows(1))
        assertEquals(listOf(null, null, "~ ^"), config.symbolRows(2))
    }

    @Test
    fun noRowKeyHoldsWhatTheBackupCannotCarry() {
        // One line a key: Backup.encodable drops a value or key with a newline or a tab.
        for (page in 1..2) {
            for (row in 1..3) assertTrue(Backup.encodable(Prefs.symbolRowKey(page, row)))
        }
    }
}
