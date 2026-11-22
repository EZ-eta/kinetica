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

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.TapToken
import com.kinetica.keyboard.engine.models.WordCandidate
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
 * A tapped apostrophe joins the word as an anchor of its own (#55): `I` `'` `d` decodes `i'd`. No key
 * in the letter geometry holds it, so it reads as itself and never as a letter near the tap.
 */
class ApostropheTapTest {

    private val g = TestData.qwertyGeometry()

    /** Offsets inside each key, kw: a clean tap, a loose one, one near the key's edge. */
    private val sloppiness = listOf(0f, 0.25f, 0.45f)

    private fun assetPath(name: String): Path {
        val direct = Paths.get("src/main/assets/dictionaries/$name")
        if (Files.exists(direct)) return direct
        return Paths.get("app/src/main/assets/dictionaries/$name")
    }

    private fun english(): WordPredictor {
        val p = assetPath("en_wordlist.txt")
        assumeTrue("en_wordlist asset not found", Files.exists(p))
        val dict = Files.newBufferedReader(p).use { DictionaryLoader.load(it) }
        return WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms, language = "en")
    }

    /** A tap [dx], [dy] kw off [c]'s centre, carrying the key it landed on, as the engine records it. */
    private fun sloppyTap(c: Char, t: Long, dx: Float, dy: Float, stream: StreamId = StreamId.LEFT): TapToken {
        val code = g.alphabet.codeOf(c)
        val x = g.centerX(code) + dx
        val y = g.centerY(code) + dy
        return TapToken(stream, g.keyAt(x, y), x, y, false, t, t + 60)
    }

    private fun apostrophe(t: Long): TapToken = TapToken.apostrophe(StreamId.RIGHT, g.alphabet.apostrophe, t)

    /** Taps spelling [word] at [s] kw off each key, alternating sides, the apostrophe as itself. */
    private fun tapped(word: String, s: Float, t0: Long = 0L): List<InputToken> =
        word.mapIndexed { i, c ->
            val t = t0 + 180L * i
            val sign = if (i % 2 == 0) 1f else -1f
            if (c == '\'') apostrophe(t) else sloppyTap(c, t, sign * s, -sign * s / 2f)
        }

    private fun literal(tokens: List<InputToken>): String =
        tokens.sortedBy { it.tStart }.joinToString("") { g.alphabet.charOf((it as TapToken).code).toString() }

    @Test
    fun aTappedApostropheIsAnAnchorOfItsOwn() {
        val tokens = listOf(TestData.tap('i', g, 0L), apostrophe(200L), TestData.tap('d', g, 400L))
        val pattern = Matcher.buildPattern(tokens, g)
        assertNotNull("a tapped apostrophe refused the whole buffer", pattern)
        val a = pattern!![1] as Matcher.Anchor
        assertEquals(g.alphabet.apostrophe, a.code)
        assertTrue(a.keyless)
        assertFalse((pattern[0] as Matcher.Anchor).keyless)
        assertEquals("i'd", literal(tokens))
        // The fixture taps an apostrophe the same way.
        assertEquals(g.alphabet.apostrophe, TestData.tap('\'', g, 0L).code)
    }

    @Test
    fun iApostropheDTappedDecodesIdFirst() {
        val p = english()
        for (s in sloppiness) {
            val tokens = tapped("i'd", s)
            assertEquals("i'd", literal(tokens))
            val first = p.decode(tokens, listOf("said")).first()
            assertEquals("at $s kw", "i'd", first.word)
            assertEquals("at $s kw", WordCandidate.Source.EXACT_TAP, first.source)
        }
    }

    @Test
    fun aMissedLetterAfterTheApostropheIsStillReadAsTheWord() {
        // The `d` landed on `s`: no exact reading, so the fuzzy pass reads the letters loosely and the
        // apostrophe exactly. Before the guard the pass visited letters only and lost the apostrophe.
        val p = english()
        val tokens = listOf(sloppyTap('i', 0L, 0.2f, 0.1f), apostrophe(180L), sloppyTap('d', 360L, -0.6f, 0f))
        assertEquals("i's", literal(tokens))
        val out = p.decode(tokens, listOf("said"))
        val idd = out.firstOrNull()
        assertEquals("i'd missing: ${out.map { it.word }}", "i'd", idd?.word)
        assertEquals(WordCandidate.Source.FUZZY_TAP, idd!!.source)
        // `i's` is no word, so a space corrects it, at the normal autocorrect level.
        assertEquals("i'd", p.tapAutocorrect("i's", idd, 0.85f)?.word)
    }

    @Test
    fun aTracedApostropheTapReplaysAsOne() {
        // A gate capture holds `tap['`; the replay drops none of it.
        val tokens = TraceReplay.tokens("tap[i,RIGHT,t=100] tap[',RIGHT,t=300] tap[d,LEFT,t=500] ctx=[said]", g)
        assertEquals(3, tokens.size)
        assertEquals("i'd", literal(tokens))
        assertEquals("i'd", english().decode(tokens, listOf("said")).first().word)
    }

    @Test
    fun donApostropheTTappedLeadsDont() {
        val p = english()
        for (s in sloppiness) {
            val first = p.decode(tapped("don't", s), listOf("i")).first()
            assertEquals("at $s kw", "don't", first.word)
            assertEquals("at $s kw", WordCandidate.Source.EXACT_TAP, first.source)
        }
        // The small fixture holds `dont` too: the tapped apostrophe keeps it out.
        val small = WordPredictor(TestData.smallDictionary(), BigramTable.EMPTY, g)
        val words = small.decode(tapped("don't", 0.25f), emptyList()).map { it.word }
        assertEquals("don't", words.first())
        assertFalse(words.contains("dont"))
    }

    @Test
    fun theEditPassNeverDropsATappedApostrophe() {
        // `l'altro` not yet learned, `laltro` a stored row: seven taps, nothing spelled as typed, so
        // the edit pass runs. Reading the apostrophe as a stray tap offered `laltro`.
        val trie = Trie.build(listOf("laltro" to 500, "l'ora" to 300, "altro" to 2000, "couldn't" to 800))
        val p = WordPredictor(trie, BigramTable.EMPTY, g)
        for (s in sloppiness) {
            val words = p.decode(tapped("l'altro", s), emptyList()).map { it.word }
            assertFalse("at $s kw: $words", words.contains("laltro"))
            assertTrue("at $s kw: $words", words.all { it.startsWith("l'") })
        }
        // A stray letter in the same buffer is still skipped: `z` between `l` and `d`.
        val words = p.decode(tapped("coulzdn't", 0.25f), emptyList()).map { it.word }
        assertEquals(words.toString(), "couldn't", words.firstOrNull())
    }

    @Test
    fun aTappedPronounAndApostropheCompleteToTheContractions() {
        val p = english()
        val out = p.decode(tapped("i'", 0.25f), listOf("said"))
        val completions = out.filter { it.source == WordCandidate.Source.COMPLETION }.map { it.word }
        for (w in listOf("i'm", "i'll", "i'd")) assertTrue("$w missing: $completions", w in completions)
        // Every candidate keeps the apostrophe where it was tapped.
        assertTrue(out.map { it.word }.toString(), out.all { it.word.startsWith("i'") })
    }

    @Test
    fun aSwipeAfterAJoinedApostropheIsReadByThePasses() {
        val p = english()
        for (overshoot in listOf(0f, 0.25f, 0.45f)) {
            val tokens = tapped("we'", 0.25f) + TestData.sloppySwipe("re", g, 700L, 220L, overshoot, StreamId.LEFT)
            // The beam reads a tap as a letter, so a buffer with a tapped apostrophe is the passes' alone.
            val seq = tokens.sortedBy { it.tStart }
            assertFalse(ThumbBeam.applies(seq, Matcher.buildPattern(seq, g)))
            val words = p.decode(tokens, listOf("said")).map { it.word }
            assertEquals("at $overshoot kw: $words", "we're", words.firstOrNull())
        }
    }
}
