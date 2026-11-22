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

package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The field's commits and the words their bars offered, for an offer inside one of them. */
class CommitHistoryTest {

    private fun history() = CommitHistory().apply {
        // "plus key ": `key` swiped, its bar showing key, let, meet, met, jet.
        record(0, "plus", emptyList(), emptyMap())
        record(5, "key", listOf("let", "meet", "met", "jet"), mapOf("let" to "en"))
    }

    @Test
    fun aWordWrittenHereKeepsItsBar() {
        val r = history().at(5, "key")!!
        assertEquals(listOf("let", "meet", "met", "jet"), r.alternatives)
        assertEquals("en", r.languages["let"])
        assertNull(history().at(6, "key"))
        assertNull(history().at(5, "kez"))
    }

    @Test
    fun aPickedAlternativeStillFindsItsBar() {
        val h = history()
        h.replaced(5, "key", "let")
        val r = h.at(5, "let")!!
        assertEquals("let", r.word)
        assertEquals(listOf("key", "meet", "met", "jet"), r.alternatives)
    }

    @Test
    fun typingEarlierMovesTheRecordsAfterIt() {
        val h = history()
        h.onEdit(at = 0, deleted = 0, inserted = 3) // "so " written before `plus`
        assertEquals("key", h.at(8, "key")!!.word)
        assertNull(h.at(5, "key"))
    }

    @Test
    fun anEditIntoAWordDropsItsRecord() {
        val h = history()
        h.onEdit(at = 8, deleted = 1, inserted = 0) // backspace into `key`
        assertNull(h.at(5, "key"))
        assertEquals("plus", h.at(0, "plus")!!.word)
    }

    @Test
    fun aLongerReplacementMovesWhatFollows() {
        val h = history()
        h.record(9, "now", emptyList(), emptyMap())
        h.replaced(5, "key", "meet")
        assertEquals("now", h.at(10, "now")!!.word)
        h.clear()
        assertEquals(0, h.size)
    }
}
