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

import com.kinetica.keyboard.engine.BigramTable
import com.kinetica.keyboard.engine.DictionaryLoader
import com.kinetica.keyboard.engine.TestData
import com.kinetica.keyboard.engine.WordPredictor
import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.TapToken
import com.kinetica.keyboard.keys.ShiftState
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A popup letter that folds to two letters joins the word as two taps (#54): `Grü` + `ß` + `e`
 * stays one word, `grüße`, instead of committing `Grü` and inserting `ß` as a symbol.
 */
class AccentComposeTest {

    private val g = TestData.qwertyGeometry()

    private fun letters(tokens: List<InputToken>): String =
        tokens.sortedBy { it.tStart }.joinToString("") { g.alphabet.charOf((it as TapToken).code).toString() }

    private fun typed(word: String, t0: Long): List<InputToken> =
        word.mapIndexed { i, c -> TestData.tap(c, g, t0 + 120L * i, StreamId.LEFT) }

    @Test
    fun sharpSJoinsTheWordAsTwoTaps() {
        val taps = accentTaps("ß", g, 1_000L) ?: error("ß did not compose")
        assertEquals("ss", letters(taps))
        // Ordered after each other: the literal is rebuilt by touch time.
        assertTrue(taps[0].tStart < taps[1].tStart)
        val tokens = typed("gru", 0L) + taps + typed("e", 1_200L)
        assertEquals("grusse", letters(tokens))
        // The literal zone offers the word as written, in either case.
        assertTrue(writtenAsTyped("grüße", letters(tokens)))
        assertTrue(writtenAsTyped("Grüße", letters(tokens)))
        assertFalse(writtenAsTyped("grüse", letters(tokens)))
        // One tap for a one-letter accent, none for a symbol or the popup's base cell.
        assertEquals("e", letters(accentTaps("ə", g, 0L)!!))
        assertNull(accentTaps("s", g, 0L))
        assertNull(accentTaps("§", g, 0L))
    }

    @Test
    fun backspaceAfterSharpSTakesBothTapsBack() {
        // The tap path: `ß` is one gesture, so one backspace undoes its glyph and both taps.
        val undo = GestureUndo()
        undo.push(GestureUndo.State("grü", 3, ShiftState.State.NONE, ShiftState.State.NONE, false))
        val tokens = typed("gru", 0L) + accentTaps("ß", g, 1_000L)!!
        val back = undo.pop() ?: error("nothing to undo")
        assertEquals("grü", back.text)
        assertEquals("gru", letters(tokens.take(back.tokens)))
        // The hold's path deletes a letter and reloads the rest: the glyph's taps come back whole.
        assertEquals("gruss", letters(tapAnchors("grüß", g, 10_000L)!!))
        assertEquals("gru", letters(tapAnchors("grü", g, 10_000L)!!))
    }

    @Test
    fun theComposeFeedsEveryTapAndCountsThemAll() {
        // The service has no JVM reach: its compose must take accentTaps' list whole, and the
        // word's length cap must count both of `ß`'s taps.
        val src = ImeSources.declaring("private fun composeAccentedLetter(")
        val body = src.text.substringAfter("private fun composeAccentedLetter(").substringBefore("\n    }\n")
        assertTrue(body.contains("accentTaps(text, g, "))
        assertTrue(body.contains("comp.tokenCount + taps.size > KineticaConstants.MAX_WORD_LEN"))
        assertTrue(body.contains("taps.forEach(comp::onToken)"))
    }

    @Test
    fun grusseTypedWithSharpSDecodesToGruesse() {
        val p = Paths.get("src/main/assets/dictionaries/de_wordlist.txt").takeIf { Files.exists(it) }
            ?: Paths.get("app/src/main/assets/dictionaries/de_wordlist.txt")
        assumeTrue("de_wordlist asset not found", Files.exists(p))
        val dict = Files.newBufferedReader(p).use { DictionaryLoader.load(it) }
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms, language = "de")
        val tokens = typed("gru", 0L) + accentTaps("ß", g, 1_000L)!! + typed("e", 1_200L)
        val words = predictor.decode(tokens, listOf("viele")).map { it.word }
        // The list writes the noun capitalized.
        assertEquals("Grüße", words.firstOrNull())
    }
}
