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

package com.kinetica.keyboard.engine

import java.io.BufferedReader
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The personal-word merge floor (KineticaConstants.PERSONAL_MERGE_MIN_COUNT): one stray commit
 * must not become a decodable trie word, or the decode re-commits it and every auto-commit
 * re-learns it ("sonore" and "imposte" entered the es personal dictionary this way). Rows
 * de-reinforced to zero must not come back as freqByte-1 ghosts through the max(1, ..)
 * quantizer either ("quinndi").
 */
class PersonalMergeFloorTest {

    private fun reader(text: String): BufferedReader = BufferedReader(StringReader(text))

    private val base = "cuando\t931329\nsiempre\t424363\n"

    @Test
    fun singleStrayCommitDoesNotEnterTheTrie() {
        val extra = DictionaryLoader.userWordsForMerge(listOf("sonore" to 1))
        val dict = DictionaryLoader.load(reader(base), extra)
        assertFalse("count-1 word must stay out of the trie", dict.trie.contains("sonore"))
    }

    @Test
    fun zeroCountRowDoesNotResurrectAsGhostWord() {
        val extra = DictionaryLoader.userWordsForMerge(listOf("quinndi" to 0))
        val dict = DictionaryLoader.load(reader(base), extra)
        assertFalse("count-0 row must stay out of the trie", dict.trie.contains("quinndi"))
    }

    @Test
    fun floorCountMergesIntoTheTrie() {
        val extra = DictionaryLoader.userWordsForMerge(
            listOf("sonore" to KineticaConstants.PERSONAL_MERGE_MIN_COUNT),
        )
        val dict = DictionaryLoader.load(reader(base), extra)
        assertTrue("floor-count word must merge", dict.trie.contains("sonore"))
    }

    @Test
    fun mergedRowsKeepTheFrequencyScale() {
        val extra = DictionaryLoader.userWordsForMerge(listOf("dinero" to 6))
        assertEquals(
            listOf("dinero" to 6 * KineticaConstants.USER_FREQ_SCALE),
            extra,
        )
    }
}
