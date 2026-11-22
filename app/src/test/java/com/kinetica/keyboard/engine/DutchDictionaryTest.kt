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

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Dutch real-asset goldens (ADDING_A_LANGUAGE.md §6). Dutch accents are loans, not letters of
 * its alphabet, so the diaeresis words are its whole forms surface: "efficient" is not a Dutch
 * spelling and reaches "efficiënt" only by folding.
 */
class DutchDictionaryTest {

    private fun assetPath(name: String): Path {
        val direct = Paths.get("src/main/assets/dictionaries/$name")
        if (Files.exists(direct)) return direct
        return Paths.get("app/src/main/assets/dictionaries/$name")
    }

    private fun loadDict(): LoadedDictionary {
        val p = assetPath("nl_wordlist.txt")
        assumeTrue("nl wordlist asset not found", Files.exists(p))
        return Files.newBufferedReader(p).use { DictionaryLoader.load(it) }
    }

    @Test
    fun dutchDictionaryLoadsWithinMemoryBudget() {
        val dict = loadDict()
        assertTrue("word count ${dict.trie.wordCount}", dict.trie.wordCount >= 30_000)
        assertTrue("trie bytes ${dict.trie.sizeBytes()}", dict.trie.sizeBytes() < 4 * 1024 * 1024)
        for (w in listOf("bedankt", "morgen", "graag", "kunnen")) {
            assertTrue("missing $w", dict.trie.contains(AccentFolder.fold(w)))
        }
        assertTrue("forms table empty", dict.forms.isNotEmpty())
    }

    @Test
    fun dutchBigramsLoadAndBoost() {
        val dict = loadDict()
        val p = assetPath("nl_bigrams.txt")
        assumeTrue("nl bigram asset not found", Files.exists(p))
        val table = Files.newBufferedReader(p).use { DictionaryLoader.loadBigrams(it, dict.trie) }
        // 201k Tatoeba sentences yield 91.3k in-vocabulary pairs, under the 100k cap.
        assertTrue("bigram count ${table.size}", table.size > 85_000)
        assertTrue("table bytes ${table.sizeBytes()}", table.sizeBytes() < 4 * 1024 * 1024)
        val boost = table.multiplier(dict.trie.nodeFor("ik"), dict.trie.nodeFor("ben"))
        // Assert the asset's share of the available boost, independent of engine tuning.
        val share = (boost - 1f) / KineticaConstants.BIGRAM_BOOST_MAX
        assertTrue("ik->ben boost $boost, share $share of the cap", share > 0.5f)
    }

    @Test
    fun commonDutchQwertySwipesDecodeTop1() =
        assertCommonDutchSwipesDecodeTop1(TestData.qwertyGeometry())

    @Test
    fun commonDutchQwertzSwipesDecodeTop1() =
        assertCommonDutchSwipesDecodeTop1(TestData.qwertzGeometry())


    private fun assertCommonDutchSwipesDecodeTop1(g: KeyboardGeometry) {
        val dict = loadDict()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        val words = mapOf(
            "bedankt" to "bedankt",
            "morgen" to "morgen",
            "welkom" to "welkom",
            "kunnen" to "kunnen",
            "zonder" to "zonder",
            "graag" to "graag",
        )
        for ((folded, expected) in words) {
            for (overshoot in listOf(0f, 0.25f, 0.4f, 0.5f)) {
                val token = if (overshoot == 0f) {
                    TestData.swipe(folded, g, 0, 100L * folded.length)
                } else {
                    TestData.sloppySwipe(
                        folded, g, 0, 100L * folded.length, overshootKw = overshoot,
                    )
                }
                val result = predictor.decode(listOf(token), emptyList())
                assertTrue("'$expected' ($overshoot) produced no candidates", result.isNotEmpty())
                assertEquals(
                    "'$expected' ($overshoot) lost top-1 to ${result[0].word}",
                    expected,
                    result[0].word,
                )
            }
        }
    }

    @Test
    fun accentRestoredThroughDecodeForms() {
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        val result = predictor.decode(
            listOf(TestData.swipe("cafe", g, 0, 400)), emptyList(),
        )
        assertTrue(
            "'café' missing from ${result.map { it.word }}",
            result.map { it.word }.contains("café"),
        )
    }

    @Test
    fun accentRestoredThroughTapAutocorrect() {
        // "efficient" reaches the "efficiënt" node but is not itself a Dutch
        // spelling, so exact taps restore the diaeresis.
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        assertFalse(predictor.isWord("efficient"))
        val tokens = "efficient".mapIndexed { i, c -> TestData.tap(c, g, i * 100L) }
        val result = predictor.decode(tokens, emptyList())
        val target = predictor.autocorrectTarget(
            "efficient", result, KineticaConstants.AUTOCORRECT_CONF_NORMAL,
        )
        assertNotNull("autocorrect did not fire on ${result.map { it.word }}", target)
        assertEquals("efficiënt", target?.word)
    }

    @Test
    fun dutchDecodeLatencyIsBounded() {
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        val tokens = listOf(TestData.swipe("bedankt", g, 0, 600))
        predictor.decode(tokens, emptyList()) // warmup
        val t0 = System.nanoTime()
        repeat(20) { predictor.decode(tokens, emptyList()) }
        val perDecodeMs = (System.nanoTime() - t0) / 20 / 1_000_000.0
        assertTrue("nl decode took $perDecodeMs ms", perDecodeMs < 100.0)
    }
}
