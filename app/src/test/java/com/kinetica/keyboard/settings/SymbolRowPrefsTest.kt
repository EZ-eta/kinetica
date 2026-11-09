package com.kinetica.keyboard.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    /** A symbol's list is keyed by its code points, so a quote or a slash makes a plain key. */
    @Test
    fun aSymbolsListKeyNamesItAndReadsBack() {
        assertEquals("pref_alt_sym_24", Prefs.symbolAlternatesKey("$"))
        for (symbol in listOf("$", "€", ":-)", "\"", "/", "𝔞")) {
            val key = Prefs.symbolAlternatesKey(symbol)
            assertEquals(symbol, Prefs.symbolOfAlternatesKey(key))
            assertTrue(key, Backup.encodable(key))
        }
        assertNull(Prefs.symbolOfAlternatesKey(Prefs.letterAlternatesKey('a')))
        assertNull(Prefs.symbolOfAlternatesKey("pref_alt_sym_zz"))
    }

    @Test
    fun theConfigReadsEverySymbolsListAndLeavesBlankOnesOut() {
        val config = MapPrefs.config(
            mapOf(
                Prefs.symbolAlternatesKey("$") to "₿ €",
                Prefs.symbolAlternatesKey("!") to "  ",
                Prefs.letterAlternatesKey('a') to "à",
            ),
        )
        assertEquals(mapOf("$" to listOf("₿", "€")), config.symbolAlternates)
    }
}
