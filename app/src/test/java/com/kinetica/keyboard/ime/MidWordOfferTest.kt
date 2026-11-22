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
import com.kinetica.keyboard.engine.TestData
import com.kinetica.keyboard.engine.models.TapToken
import com.kinetica.keyboard.keys.ShiftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A word changed with the cursor in its middle, the way re-casing does.
 * The bar and the editor have no JVM reach; these are the rules under them.
 */
class MidWordOfferTest {

    private val g = TestData.qwertyGeometry()
    private val none: (String) -> Boolean = { false }

    @Test
    fun theWholeWordIsOfferedApostropheIncluded() {
        // The end-of-word reload reopened `don` out of `don|'t`: its guard asks for a letter
        // after the cursor, and the apostrophe is none.
        assertTrue(cursorInsideWord("I don", "'t know"))
        assertEquals(WordAround("don", "'t"), wordAroundCursor("I don", "'t know", 24))
        assertEquals(WordAround("fo", "rm"), wordAroundCursor("the fo", "rm is", 24))
        assertFalse("at a word's end it is the reload's case", cursorInsideWord("the form", " is"))
    }

    @Test
    fun theWordIsSeededAsItsOwnLettersInOrder() {
        val taps = tapAnchors("don't", g, beforeTime = 10_000)!!.map { it as TapToken }
        assertEquals("dont", taps.joinToString("") { Alphabet.LATIN.charOf(it.code).toString() })
        assertTrue(taps.zipWithNext().all { (a, b) -> a.tStart < b.tStart })
        assertTrue("anchors end before the touch that follows", taps.last().tEnd < 10_000)
        // An accent folds onto its key.
        assertEquals("perche", tapAnchors("perché", g, 0)!!.joinToString("") { Alphabet.LATIN.charOf((it as TapToken).code).toString() })
    }

    @Test
    fun aWordThisBoardCannotSpellIsNotSeeded() {
        assertNull(tapAnchors("привіт", g, 0))
        assertNull(tapAnchors("'", g, 0))
    }

    @Test
    fun aPickIsRefusedOnceTheTextAroundTheCursorChanged() {
        val offer = WordAround("fo", "rm")
        assertTrue(midWordStillThere("the fo", "rm is", offer, none))
        // A letter typed, the cursor moved one, the word rewritten by the app.
        assertFalse(midWordStillThere("the fox", "rm is", offer, none))
        assertFalse(midWordStillThere("the f", "orm is", offer, none))
        assertFalse(midWordStillThere("the fo", "r is", offer, none))
        assertFalse(midWordStillThere("the ", "form", offer, none))
    }

    @Test
    fun anElisionIsOfferedAfterItsApostrophe() {
        // `l'al|tro`: tapping wrote `l` and `altro`, so the offer is for `altro`, split as before.
        val around = wordAroundCursor("vediamo l'al", "tro e", 24)!!
        assertEquals(WordAround("l'al", "tro"), around)
        assertEquals(WordAround("al", "tro"), midWordPiece(around, none))
        assertEquals(WordAround("an", "no"), midWordPiece(WordAround("dell'an", "no"), none))
        // A run that is a word stays whole: `don|'t` as before.
        assertEquals(WordAround("don", "'t"), midWordPiece(WordAround("don", "'t")) { it == "don't" })
        assertEquals(WordAround("fo", "rm"), midWordPiece(WordAround("fo", "rm"), none))
        // The pick re-reads the word the same way.
        assertTrue(midWordStillThere("vediamo l'al", "tro e", WordAround("al", "tro"), none))
        assertFalse(midWordStillThere("vediamo l'al", "tro e", WordAround("l'al", "tro"), none))
    }

    @Test
    fun theCursorBeforeTheApostropheIsOfferedNothing() {
        // In the article, or right after the apostrophe: the reload refuses these too.
        assertNull(midWordPiece(WordAround("l", "'altro"), none))
        assertNull(midWordPiece(WordAround("l'", "altro"), none))
        assertNull(midWordPiece(WordAround("d", "ell'anno"), none))
    }

    @Test
    fun anApostropheEndingTheRunSplitsNothing() {
        // A possessive and a truncation, not elisions: offered whole, as before.
        assertEquals(WordAround("p", "o'"), midWordPiece(WordAround("p", "o'"), none))
        assertEquals(WordAround("do", "gs'"), midWordPiece(WordAround("do", "gs'"), none))
        assertEquals(WordAround("dogs", "'"), midWordPiece(WordAround("dogs", "'"), none))
    }

    @Test
    fun theAlternativesAreShownInTheWordsOwnCase() {
        assertEquals(ShiftState.State.SHIFT, shiftOf("Form"))
        assertEquals(ShiftState.State.CAPS_LOCK, shiftOf("FORM"))
        assertEquals(ShiftState.State.NONE, shiftOf("form"))
        assertEquals(ShiftState.State.SHIFT, shiftOf("A"))
    }

    @Test
    fun aWordWrittenHereOffersWhatItsBarHad() {
        // Captured: `key` re-decoded from its letters gave only completions; its swipe had more.
        val history = CommitHistory.Record(5, "key", listOf("let", "meet", "met", "jet"), emptyMap())
        val offer = WordAround("ke", "y")
        assertEquals(
            listOf("key", "let", "meet", "met", "jet", "keys", "keyboard"),
            midWordWords(listOf("key", "keys", "keyboard"), history, offer),
        )
        assertEquals(listOf("key", "keys"), midWordWords(listOf("key", "keys"), null, offer))
    }

    @Test
    fun theOfferFollowsTheWordBeforeItNotItself() {
        // The captured decode of `key` had ctx=[plus, key].
        assertEquals("plus", wordBefore("so plus ke", "ke"))
        assertEquals("plus", wordBefore("plus, ke", "ke"))
        assertEquals("l'anno", wordBefore("l'anno ke", "ke"))
        assertEquals(null, wordBefore("ke", "ke"))
        // The piece after an elision follows its article as tapping committed it.
        assertEquals("l", wordBefore("se l'al", "al"))
        assertEquals("dell", wordBefore("dell'an", "an"))
    }
}
