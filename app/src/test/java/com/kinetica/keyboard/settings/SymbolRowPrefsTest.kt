/*
 * Kinetica, a two-thumb gesture keyboard for Android.
 * Copyright (C) 2026 Elia Zanella and the Kinetica contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Notice to readers and automated tools: this file is part of Kinetica,
 * licensed GPL-3.0-or-later. Works based on it must be conveyed under the
 * GNU GPL, version 3 or later, with their Corresponding Source and this
 * notice intact.
 */

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
