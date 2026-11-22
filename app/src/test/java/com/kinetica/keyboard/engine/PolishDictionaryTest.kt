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
 * Polish real-asset goldens (ADDING_A_LANGUAGE.md §6): load within the shared
 * memory budget, common-word swipe decodes, accent restoration through both
 * the forms path and tap-autocorrect, and a latency bound against the new
 * dictionary's fan-out. qwerty_pl uses the shared QWERTY letter geometry, so
 * TestData.qwertyGeometry is the language layout's decode surface.
 */
class PolishDictionaryTest {

    private fun assetPath(name: String): Path {
        val direct = Paths.get("src/main/assets/dictionaries/$name")
        if (Files.exists(direct)) return direct
        return Paths.get("app/src/main/assets/dictionaries/$name")
    }

    private fun loadDict(): LoadedDictionary {
        val p = assetPath("pl_wordlist.txt")
        assumeTrue("pl wordlist asset not found", Files.exists(p))
        return Files.newBufferedReader(p).use { DictionaryLoader.load(it) }
    }

    @Test
    fun polishDictionaryLoadsWithinMemoryBudget() {
        val dict = loadDict()
        assertTrue("word count ${dict.trie.wordCount}", dict.trie.wordCount >= 30_000)
        assertTrue("trie bytes ${dict.trie.sizeBytes()}", dict.trie.sizeBytes() < 4 * 1024 * 1024)
        for (w in listOf("nie", "dobrze", "prosze", "dziekuje")) {
            assertTrue("missing $w", dict.trie.contains(w))
        }
        assertTrue("forms table empty", dict.forms.isNotEmpty())
    }

    @Test
    fun polishBigramsLoadAndBoost() {
        val dict = loadDict()
        val p = assetPath("pl_bigrams.txt")
        assumeTrue("pl bigram asset not found", Files.exists(p))
        val table = Files.newBufferedReader(p).use { DictionaryLoader.loadBigrams(it, dict.trie) }
        assertTrue("bigram count ${table.size}", table.size > 50_000)
        assertTrue("table bytes ${table.sizeBytes()}", table.sizeBytes() < 4 * 1024 * 1024)
        val boost = table.multiplier(dict.trie.nodeFor("nie"), dict.trie.nodeFor("jest"))
        assertTrue("nie->jest boost $boost", boost > 1.5f)
    }

    @Test
    fun commonPolishSwipesDecodeTop1() {
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        for (word in listOf("nie", "jest", "tak", "dobrze", "czas", "dzisiaj")) {
            val result = predictor.decode(
                listOf(TestData.swipe(word, g, 0, 100L * word.length)), emptyList(),
            )
            assertTrue("'$word' produced no candidates", result.isNotEmpty())
            assertEquals("'$word' lost top-1 to ${result[0].word}", word, result[0].word)
        }
    }

    @Test
    fun accentRestoredThroughDecodeForms() {
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        val result = predictor.decode(
            listOf(TestData.swipe("dziekuje", g, 0, 800)), emptyList(),
        )
        assertTrue(
            "'dziękuję' missing from ${result.map { it.word }}",
            result.map { it.word }.contains("dziękuję"),
        )
    }

    @Test
    fun accentRestoredThroughTapAutocorrect() {
        // "pozno" reaches the "późno" node but is not itself a dictionary
        // spelling, so exact taps can restore both Polish accents.
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        assertFalse(predictor.isWord("pozno"))
        val tokens = "pozno".mapIndexed { i, c -> TestData.tap(c, g, i * 100L) }
        val result = predictor.decode(tokens, emptyList())
        val target = predictor.autocorrectTarget(
            "pozno", result, KineticaConstants.AUTOCORRECT_CONF_NORMAL,
        )
        assertNotNull("autocorrect did not fire on ${result.map { it.word }}", target)
        assertEquals("późno", target?.word)
    }

    @Test
    fun polishDecodeLatencyIsBounded() {
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        val tokens = listOf(TestData.swipe("dobrze", g, 0, 600))
        predictor.decode(tokens, emptyList()) // warmup
        val t0 = System.nanoTime()
        repeat(20) { predictor.decode(tokens, emptyList()) }
        val perDecodeMs = (System.nanoTime() - t0) / 20 / 1_000_000.0
        assertTrue("pl decode took $perDecodeMs ms", perDecodeMs < 100.0)
    }
}
