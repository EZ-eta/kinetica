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

package com.kinetica.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The emoji search ranking, on small fixtures and on the real assets. */
class EmojiSearchTest {

    private fun fixture(vararg rows: Pair<String, String>): Pair<EmojiCatalog, List<EmojiCatalog.Words>> {
        val catalog = EmojiCatalog.parse((listOf("@G\t😀") + rows.map { "${it.first}\tx" }).asSequence())
        val words = EmojiCatalog.parseWords(rows.map { it.second }.asSequence())
        return catalog to words
    }

    private fun rank(
        query: String,
        f: Pair<EmojiCatalog, List<EmojiCatalog.Words>>,
        uses: Map<String, Int> = emptyMap(),
    ): List<String> = EmojiSearch.rank(query, f.first.entries, listOf(f.second), uses).map { it.ch }

    @Test
    fun aWholeWordBeatsAPrefix() {
        val f = fixture("🍭" to "\tlollipop\t", "😂" to "\ttears\tlol")
        assertEquals(listOf("😂", "🍭"), rank("lol", f))
    }

    @Test
    fun handBeatsNameBeatsKeyword() {
        val f = fixture("a" to "\t\tsun", "b" to "\tsun\t", "c" to "sun\t\t")
        assertEquals(listOf("c", "b", "a"), rank("sun", f))
    }

    @Test
    fun everyQueryWordMustMatch() {
        val f = fixture("❤️" to "\tred heart\t", "🫀" to "\tanatomical heart\torgan", "🔴" to "\tred circle\t")
        assertEquals(listOf("❤️"), rank("red heart", f))
    }

    @Test
    fun useCountThenShorterNameBreakATie() {
        val f = fixture("💘" to "\theart with arrow\t", "💔" to "\tbroken heart\t", "❤️" to "\tred heart\t")
        assertEquals(listOf("💔", "❤️", "💘"), rank("heart", f))
        assertEquals(listOf("❤️", "💔", "💘"), rank("heart", f, mapOf("❤️" to 3)))
    }

    @Test
    fun accentsAndCaseFold() {
        val f = fixture("☕" to "\tcaffè\t")
        assertEquals(listOf("☕"), rank("CAFFE", f))
        assertEquals(listOf("☕"), rank("caffè", f))
    }

    @Test
    fun anEmptyOrUnmatchedQueryFindsNothing() {
        val f = fixture("☕" to "\tcoffee\t")
        assertTrue(rank("", f).isEmpty())
        assertTrue(rank("  ,", f).isEmpty())
        assertTrue(rank("zzqx", f).isEmpty())
    }

    @Test
    fun theRowIsCapped() {
        val rows = (0 until 40).map { "e$it" to "\tsame\t" }.toTypedArray()
        assertEquals(EmojiSearch.RESULTS, rank("same", fixture(*rows)).size)
    }

    // The real assets. The first screen of the row shows eight cells.

    private fun firstScreen(query: String, vararg langs: String) = EmojiAssetFiles.top(query, *langs, n = 8)

    @Test
    fun theRedHeartIsOnTheFirstScreenInEveryTestedLanguage() {
        for ((q, lang) in listOf("heart" to "en", "cuore" to "it", "herz" to "de", "serce" to "pl", "сердце" to "ru")) {
            val top = firstScreen(q, lang, "en")
            assertTrue("$q ($lang): $top", top.contains("❤️"))
        }
        assertEquals("❤️", EmojiAssetFiles.top("red heart", "en").first())
    }

    @Test
    fun theHandWrittenWordsSurvive() {
        val top = EmojiAssetFiles.top("lol", "en")
        assertTrue("lol: $top", top.indexOf("😂") in 0..7)
        assertTrue("the lollipop only after the laughing faces: $top", top.indexOf("🍭") > top.indexOf("😂"))
    }

    @Test
    fun commonWordsLeadWithTheirEmoji() {
        assertEquals("☕", EmojiAssetFiles.top("caffe", "it", "en").first())
        assertEquals("🔥", EmojiAssetFiles.top("fire", "en").first())
        assertEquals("🔥", EmojiAssetFiles.top("fuoco", "it", "en").first())
        assertEquals("🍕", EmojiAssetFiles.top("pizza", "en").first())
        assertEquals("🇮🇹", EmojiAssetFiles.top("italy", "en").first())
        assertEquals("👍", EmojiAssetFiles.top("thumbs", "en").first())
        assertTrue(EmojiAssetFiles.top("zzqx", "en", "it").isEmpty())
    }

    @Test
    fun romanianFindsItsOwnWords() {
        // Romanian has its own board; without kw_ro.tsv its search fell back to English words only.
        assertTrue(firstScreen("inima", "ro", "en").contains("❤️"))
        assertEquals("🔥", EmojiAssetFiles.top("foc", "ro", "en").first())
        assertTrue(firstScreen("cafea", "ro", "en").contains("☕"))
    }
}
