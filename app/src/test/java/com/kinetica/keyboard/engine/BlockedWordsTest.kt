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
 * Blocking a word removes it from the dictionary itself.
 *
 * Sliding a suggestion down only lowers the personal count, which `UserWordDao`
 * clamps at zero, so a bundled word could leave the bar but never the trie. A user
 * could not lose "seok" and "heo" however often they lowered them: both are in the
 * bundled English list (1217 and 634), because subtitles are full of
 * transliterated names.
 */
class BlockedWordsTest {

    private fun reader(vararg lines: String): BufferedReader =
        BufferedReader(StringReader(lines.joinToString("\n")))

    private fun corpus(): BufferedReader = reader(
        "the\t12000",
        "seok\t1217",
        "heo\t634",
        "her\t9000",
        "hero\t3000",
        "these\t2500",
    )

    @Test
    fun aBlockedWordIsNotInTheTrieAtAll() {
        val open = DictionaryLoader.load(corpus()).trie
        assertTrue("precondition: the corpus really carries it", open.contains("seok"))

        val trie = DictionaryLoader.load(corpus(), blocked = setOf("seok")).trie
        assertFalse(trie.contains("seok"))
        // Gone, not down-weighted: no node, so nothing to decode, complete or
        // suggest.
        assertEquals(-1, trie.nodeFor("seok"))
    }

    @Test
    fun everythingElseSurvivesUntouched() {
        val trie = DictionaryLoader.load(corpus(), blocked = setOf("seok", "heo")).trie
        for (w in listOf("the", "her", "hero", "these")) {
            assertTrue("$w should be untouched", trie.contains(w))
        }
        assertFalse(trie.contains("seok"))
        assertFalse(trie.contains("heo"))
    }

    @Test
    fun blockingAWordThatIsAPrefixLeavesTheLongerWordAlone() {
        // "her" is a prefix of "hero"; removing the short word must not take the
        // subtree with it.
        val trie = DictionaryLoader.load(corpus(), blocked = setOf("her")).trie
        assertFalse(trie.contains("her"))
        assertTrue(trie.contains("hero"))
    }

    @Test
    fun aBlockedWordCannotReturnThroughThePersonalMerge() {
        // The filter applies to extraWords too: a word typed twice and then
        // blocked would otherwise merge straight back in at USER_FREQ_SCALE.
        val learned = DictionaryLoader.userWordsForMerge(listOf("seok" to 5))
        assertTrue("precondition: it would be merged", learned.isNotEmpty())

        val trie = DictionaryLoader.load(corpus(), learned, blocked = setOf("seok")).trie
        assertFalse(trie.contains("seok"))
    }

    @Test
    fun blockingIsCaseInsensitiveOnTheStoredSpelling() {
        // The IME lower-cases what it reads from the table; the loader lower-cases
        // the corpus word before testing it, so a capitalised corpus entry is
        // still caught.
        val trie = DictionaryLoader.load(
            reader("Seok\t1217", "the\t12000"),
            blocked = setOf("seok"),
        ).trie
        assertFalse(trie.contains("seok"))
        assertTrue(trie.contains("the"))
    }

    @Test
    fun blockingOneAccentedVariantLeavesItsSiblingsAlone() {
        // Variants fold onto one node, so this is the case where an over-broad
        // filter would take a real word out with the unwanted one.
        val src = reader("perche\t50", "perché\t9000", "per\t4000")
        val open = DictionaryLoader.load(src).trie
        assertTrue(open.contains("perche"))

        val out = DictionaryLoader.load(
            reader("perche\t50", "perché\t9000", "per\t4000"),
            blocked = setOf("perche"),
        )
        // The folded node survives because the accented spelling is still there,
        // and the forms table offers the surviving variant.
        assertTrue(out.trie.contains("perche"))
        val node = out.trie.nodeFor("perche")
        val displays = out.forms[node]?.map { it.display } ?: listOf("perche")
        assertFalse("the blocked spelling is still on offer", displays.contains("perche"))
        assertTrue(displays.contains("perché"))
        assertTrue(out.trie.contains("per"))
    }

    @Test
    fun anEmptyBlockListChangesNothing() {
        val plain = DictionaryLoader.load(corpus()).trie
        val empty = DictionaryLoader.load(corpus(), blocked = emptySet()).trie
        assertEquals(plain.wordCount, empty.wordCount)
        assertTrue(empty.contains("seok"))
    }

    @Test
    fun blockingEveryWordDoesNotCrashTheLoad() {
        // A degenerate case someone can reach through the settings screen.
        val out = DictionaryLoader.load(
            reader("the\t1"),
            blocked = setOf("the"),
        )
        assertEquals(0, out.trie.wordCount)
    }
}
