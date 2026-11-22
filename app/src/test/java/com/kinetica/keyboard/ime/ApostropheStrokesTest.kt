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

import com.kinetica.keyboard.engine.Alphabet
import com.kinetica.keyboard.engine.BigramTable
import com.kinetica.keyboard.engine.DictionaryLoader
import com.kinetica.keyboard.engine.TestData
import com.kinetica.keyboard.engine.WordPredictor
import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.TapToken
import com.kinetica.keyboard.ui.TouchRules
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The apostrophe in strokes, an experimental switch, off by default. Off, the engine gets the board
 * without the key and predictors that read `'` as a tap only; on, the key is a place and a tapped `'`
 * may join a swiped word or the stroke the other thumb is drawing.
 */
class ApostropheStrokesTest {

    private val g = TestData.qwertyGeometry()
    private val latin = Alphabet.LATIN

    private fun lexicon(lang: String): WordPredictor {
        val direct = Paths.get("src/main/assets/dictionaries/${lang}_wordlist.txt")
        val p = if (Files.exists(direct)) direct else Paths.get("app/src/main/assets/dictionaries/${lang}_wordlist.txt")
        assumeTrue("${lang}_wordlist asset not found", Files.exists(p))
        val dict = Files.newBufferedReader(p).use { DictionaryLoader.load(it) }
        return WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms, language = lang, learnedOnly = dict.learned)
    }

    /** [word] tapped on key centres, 180 ms apart, the apostrophe as the keyless tap the keyboard sends. */
    private fun tapped(word: String): List<InputToken> =
        word.mapIndexed { i, c ->
            val t = 180L * i
            if (c == '\'') {
                TapToken.apostrophe(StreamId.RIGHT, latin.apostrophe, t)
            } else {
                val code = latin.codeOf(c)
                TapToken(StreamId.LEFT, code, g.centerX(code), g.centerY(code), false, t, t + 60)
            }
        }

    @Test
    fun offLeavesEveryPredictorReadingATapOnly() {
        for (lang in listOf("en", "it", "de")) {
            val p = lexicon(lang)
            strokeApostrophes(p, true)
            assertEquals(lang, elisionHeadsFor(listOf(lang)), p.elisionHeads)
            assertEquals(lang, lang in POSSESSIVE_LANGUAGES, p.possessive)
            assertTrue(lang, p.ridesApostrophes)
            strokeApostrophes(p, false)
            assertTrue(lang, p.elisionHeads.isEmpty())
            assertFalse(lang, p.possessive)
            assertFalse(lang, p.ridesApostrophes)
        }
        assertTrue(elisionHeadsFor(listOf("it")).isNotEmpty())
    }

    @Test
    fun offTheBoardHasNoKey() {
        val letters = TestData.qwertyHomeRowShifted()
        val key = floatArrayOf(9.5f, 1.5f, 10f, 3f)
        assertFalse(TouchRules.engineBoard(letters, key, strokes = false).hasApostropheKey)
        assertTrue(TouchRules.engineBoard(letters, key, strokes = true).hasApostropheKey)
        // No key on the layout, none on the board, the switch either way.
        assertFalse(TouchRules.engineBoard(letters, null, strokes = true).hasApostropheKey)
        assertTrue(TouchRules.engineBoard(letters, key, strokes = false) === letters)
    }

    @Test
    fun offATappedApostropheNeverRidesTheOtherThumbsStroke() {
        // Off: text, whatever the thumbs do.
        for (pending in listOf(false, true)) for (down in listOf(false, true)) {
            assertFalse("$pending $down", apostropheRides(strokes = false, wordPending = pending, otherThumbDown = down))
        }
        // On: only with no word in hand and a thumb down before it.
        assertTrue(apostropheRides(strokes = true, wordPending = false, otherThumbDown = true))
        assertFalse(apostropheRides(strokes = true, wordPending = false, otherThumbDown = false))
        assertFalse(apostropheRides(strokes = true, wordPending = true, otherThumbDown = true))
    }

    @Test
    fun offASwipedWordEndsAtATappedApostrophe() {
        assertFalse(swipedWordMayJoin(strokes = false, hasSwipe = true))
        assertTrue(swipedWordMayJoin(strokes = false, hasSwipe = false))
        assertTrue(swipedWordMayJoin(strokes = true, hasSwipe = true))
        assertTrue(swipedWordMayJoin(strokes = true, hasSwipe = false))
    }

    @Test
    fun offNoWordWaitsForItsS() {
        assertFalse(waitsForApostropheS(strokes = false, endsOnTappedApostrophe = true))
        assertFalse(waitsForApostropheS(strokes = false, endsOnTappedApostrophe = false))
        assertTrue(waitsForApostropheS(strokes = true, endsOnTappedApostrophe = true))
        assertFalse(waitsForApostropheS(strokes = true, endsOnTappedApostrophe = false))
    }

    /** The service has no JVM reach: each tapped `'` decision is asked with the switch, never a constant. */
    @Test
    fun theKeyboardAsksEachDecisionWithTheSwitch() {
        val src = ImeSources.declaring("private fun joinApostrophe(").text
        assertTrue(src.contains("if (apostropheRides(config.apostropheStrokes, comp.hasPendingWord, engine.trackedSince(t))) {"))
        assertTrue(src.contains("val joins = swipedWordMayJoin(config.apostropheStrokes, comp.hasSwipeToken()) &&"))
        assertTrue(src.contains("val waits = waitsForApostropheS(config.apostropheStrokes, comp.endsOnTappedApostrophe)"))
        assertTrue(src.contains("if (!engine.hasActivePointers() && !waits) autospace.scheduleStaleTimeout()"))
        for (call in listOf("apostropheRides(", "swipedWordMayJoin(", "waitsForApostropheS(")) {
            val uses = ImeSources.all().sumOf { f -> Regex(Regex.escape(call)).findAll(f.text).count() }
            // The declaration and the one call.
            assertEquals(call, 2, uses)
        }
    }

    @Test
    fun offATappedApostropheJoinsNoListWordForItsPossessive() {
        val en = lexicon("en")
        fun joins(w: String) = apostropheJoins(w, listOf("en" to en), latin, elidingOn = false)
        strokeApostrophes(en, true)
        assertTrue(joins("marco"))
        strokeApostrophes(en, false)
        assertFalse(joins("marco"))
        assertFalse(joins("peter"))
        // The contractions the list spells still join, the switch either way.
        assertTrue(joins("don"))
        assertTrue(joins("i"))
    }

    @Test
    fun offATappedPossessiveIsNotReadAsOneWord() {
        val en = lexicon("en")
        strokeApostrophes(en, true)
        assertEquals("marco's", en.decode(tapped("marco's"), listOf("with")).first().word)
        strokeApostrophes(en, false)
        val off = en.decode(tapped("marco's"), listOf("with")).map { it.word }
        assertFalse(off.toString(), "marco's" in off)
    }
}
