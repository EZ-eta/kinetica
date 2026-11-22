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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A spelling with the accents left off is not a word unless written text uses it. The subtitle
 * lists carried `pojsc` 232 and `sie` 87 050 beside `pójść` and `się`, and Italian `perche` led
 * `perché`; `generate_assets.py --clean-diacritics` removes them by Tatoeba's written use, and
 * the loader keeps learned copies out.
 */
class LeftOffAccentsTest {

    private fun assetPath(name: String): Path {
        val direct = Paths.get("src/main/assets/dictionaries/$name")
        return if (Files.exists(direct)) direct else Paths.get("app/src/main/assets/dictionaries/$name")
    }

    private fun predictor(lang: String): WordPredictor {
        val p = assetPath("${lang}_wordlist.txt")
        assumeTrue("$lang wordlist asset not found", Files.exists(p))
        val dict = Files.newBufferedReader(p).use { DictionaryLoader.load(it) }
        return WordPredictor(dict.trie, BigramTable.EMPTY, TestData.qwertyGeometry(), dict.forms)
    }

    private fun tapsCorrectTo(p: WordPredictor, typed: String): String? {
        val g = TestData.qwertyGeometry()
        val tokens = typed.mapIndexed { i, c -> TestData.tap(c, g, i * 100L) }
        val result = p.decode(tokens, emptyList())
        return p.autocorrectTarget(typed, result, KineticaConstants.AUTOCORRECT_CONF_NORMAL)?.word
    }

    @Test
    fun polishLettersWithoutAccentsCorrectToTheWord() {
        val p = predictor("pl")
        for ((typed, word) in listOf(
            "pojsc" to "pójść", "pijac" to "pijąc", "poszedlem" to "poszedłem",
            "sie" to "się", "moze" to "może", "juz" to "już", "byc" to "być",
        )) {
            assertFalse("$typed is still a word", p.isWord(typed))
            assertTrue("$typed is not seen as $word without accents", p.leavesAccentsOff(typed))
            assertEquals(word, tapsCorrectTo(p, typed))
        }
    }

    @Test
    fun bothSpellingsOfARealPairStay() {
        val pl = predictor("pl")
        for ((plain, accented) in listOf("ze" to "że", "te" to "tę", "ja" to "ją", "piec" to "pięć")) {
            assertTrue("$plain was dropped", pl.holdsSpelling(plain))
            assertTrue("$accented was dropped", pl.holdsSpelling(accented))
            assertFalse("$plain is a word of its own", pl.leavesAccentsOff(plain))
        }
        val it = predictor("it")
        for (plain in listOf("e", "da", "si", "papa", "tento")) {
            assertTrue("$plain was dropped", it.holdsSpelling(plain))
        }
    }

    @Test
    fun italianPercheSwipesToPerche() {
        // Subtitles wrote `perche` 489 341 times against 417 508 for `perché`, so it led.
        val p = predictor("it")
        val g = TestData.qwertyGeometry()
        val result = p.decode(listOf(TestData.swipe("perche", g, 0, 600)), emptyList())
        assertEquals("perché", result.firstOrNull()?.word)
        assertTrue(result.none { it.word == "perche" })
    }

    @Test
    fun otherAccentedLanguagesAreCleanedToo() {
        for ((lang, typed, word) in listOf(
            Triple("es", "tambien", "también"), Triple("fr", "etait", "était"),
            Triple("cs", "neni", "není"), Triple("de", "uber", "über"), Triple("no", "ogsa", "også"),
            Triple("ro", "fara", "fără"),
        )) {
            val p = predictor(lang)
            assertFalse("$lang $typed is still a word", p.isWord(typed))
            assertEquals("$lang $typed", word, tapsCorrectTo(p, typed))
        }
    }

    @Test
    fun aLearnedSpellingWithoutAccentsIsNotMerged() {
        // Learned before the clean, or from the typed-letters zone with autocorrect off.
        val list = "pójść\t300\nże\t900\nze\t200\nnowy\t50\n"
        val dict = DictionaryLoader.load(
            BufferedReader(StringReader(list)),
            extraWords = listOf("pojsc" to 50_000, "ze" to 2_000, "kinetica" to 2_000),
        )
        val p = WordPredictor(dict.trie, BigramTable.EMPTY, TestData.qwertyGeometry(), dict.forms)
        assertFalse(p.holdsSpelling("pojsc"))
        assertTrue(p.leavesAccentsOff("pojsc"))
        assertTrue("a real pair's plain spelling still learns", p.holdsSpelling("ze"))
        assertTrue("a new word still learns", p.holdsSpelling("kinetica"))
    }
}
