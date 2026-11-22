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

import com.kinetica.keyboard.keys.EditorAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The language switch and peck-type as ordinary chords, so no key is reserved. */
class ChordDefaultsTest {

    private val lang = EditorAction.NEXT_LANGUAGE.output
    private val peck = EditorAction.TOGGLE_PECK_MODE.output

    private fun applied(rows: Map<String, String>, writes: List<Pair<String, String>>?): Map<String, String> =
        HashMap(rows).apply { writes?.forEach { (k, v) -> put(k, v) } }

    @Test
    fun aFreshInstallGetsLAndP() {
        assertEquals(listOf("l" to lang, "p" to peck), ChordDefaults.plan(null, null, seeded = false, emptyMap()))
    }

    @Test
    fun aStoredKeyKeepsWorkingWhereItWas() {
        assertEquals(listOf("k" to lang, "x" to peck), ChordDefaults.plan("k", "x", seeded = false, emptyMap()))
    }

    @Test
    fun aLanguageSwitchTurnedOffStaysOffButPeckTypeGetsItsDefault() {
        // `none` for the language was a choice; for peck-type it was the shipped default, and the
        // old screen wrote it whenever it was opened.
        assertEquals(listOf("p" to peck), ChordDefaults.plan("none", "none", seeded = false, emptyMap()))
    }

    @Test
    fun aTextChordTheReservedKeyShadowedMovesToTheSpacebar() {
        val rows = mapOf("l" to "hello")
        val writes = ChordDefaults.plan("l", null, seeded = false, rows)
        assertEquals(listOf("space:l" to "hello", "l" to lang, "p" to peck), writes)
    }

    @Test
    fun aChordOfTheUsersOwnKeepsItsKey() {
        // The old peck default was off, so a `p` chord always fired: the new default does not take it.
        val rows = mapOf("p" to "please")
        assertEquals(listOf("l" to lang), ChordDefaults.plan(null, null, seeded = false, rows))
    }

    @Test
    fun itRunsOnceAndAgainOnlyWhenOldPreferencesReturn() {
        val rows = mapOf("l" to "hello")
        val after = applied(rows, ChordDefaults.plan("l", null, seeded = false, rows))
        assertNull(ChordDefaults.plan(null, null, seeded = true, after))
        // An old backup brings the preferences back: nothing new to write.
        assertEquals(emptyList<Pair<String, String>>(), ChordDefaults.plan("l", "none", seeded = true, after))
        // A user who removed the defaults does not get them back.
        assertNull(ChordDefaults.plan(null, null, seeded = true, emptyMap()))
    }

    @Test
    fun anOldReservedKeyThatIsNotOneCharacterIsIgnored() {
        assertEquals(listOf("p" to peck), ChordDefaults.plan("ll", null, seeded = false, emptyMap()))
    }
}
