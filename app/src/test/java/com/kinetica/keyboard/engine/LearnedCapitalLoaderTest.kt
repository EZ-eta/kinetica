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

import com.kinetica.keyboard.engine.DictionaryLoader.LearnedCase
import java.io.BufferedReader
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Learned capitals at load (#28), and learned lowercase spellings of capitalized words.
 */
class LearnedCapitalLoaderTest {

    private val list = "dennis\t11751\nmark\t30000\nHaus\t70976\nMitwirkung\t62\n"

    private fun load(extra: List<Pair<String, Int>>, cases: Map<String, LearnedCase> = emptyMap()) =
        DictionaryLoader.load(BufferedReader(StringReader(list)), extra, cases = cases)

    private fun spellings(d: LoadedDictionary, key: String): List<String> =
        d.forms[d.trie.nodeFor(key)]?.map { it.display } ?: listOf(key)

    @Test
    fun aSettledCapitalLeadsItsWord() {
        val d = load(listOf("dennis" to 2000), mapOf("dennis" to LearnedCase("Dennis", 2)))
        assertEquals("Dennis", spellings(d, "dennis").first())
    }

    @Test
    fun theLowercaseWordStaysOfferedBehindIt() {
        // `mark` is a word as well as a name: the user's capital leads it and does not replace it.
        val d = load(listOf("mark" to 3000), mapOf("mark" to LearnedCase("Mark", 2)))
        assertEquals(listOf("Mark", "mark"), spellings(d, "mark"))
    }

    @Test
    fun anUnsettledCapitalChangesNothing() {
        val d = load(listOf("dennis" to 2000), mapOf("dennis" to LearnedCase("Dennis", 1)))
        assertEquals(listOf("dennis"), spellings(d, "dennis"))
    }

    @Test
    fun aNounLearnedLowercaseFeedsItsCapital() {
        // Committing `Mitwirkung` stores `mitwirkung`; as a form of its own, two commits put it
        // ahead of the noun.
        val d = load(listOf("mitwirkung" to 2000))
        assertEquals(listOf("Mitwirkung"), spellings(d, "mitwirkung"))
        val p = WordPredictor(d.trie, BigramTable.EMPTY, TestData.qwertyGeometry(), d.forms)
        assertFalse(p.isWord("mitwirkung"))
        assertTrue(p.isWordIgnoringCase("mitwirkung"))
        assertTrue(p.frequencyByte("haus") > 0)
    }
}
