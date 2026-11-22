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

import com.kinetica.keyboard.keys.WordCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Re-casing the word the cursor is parked inside.
 *
 * These rules decide how much text either side of the cursor is replaced. A wrong count eats
 * text, so every span is read from the text handed in and nothing else.
 */
class RecaseAroundCursorTest {

    private val max = 24

    @Test
    fun theWholeWordIsSplitAtTheCursor() {
        assertEquals(WordAround("hel", "lo"), wordAroundCursor("hel", "lo", max))
        assertEquals(WordAround("hel", "lo"), wordAroundCursor("say hel", "lo world", max))
    }

    @Test
    fun aHyphenIsABoundaryOnBothSides() {
        // The same word run the commit case's fixture pins.
        assertEquals(WordAround("hear", "ted"), wordAroundCursor("half-hear", "ted", max))
        assertEquals(WordAround("half", "x"), wordAroundCursor("half", "x-hearted", max))
    }

    @Test
    fun anApostropheBelongsToTheWord() {
        assertEquals(WordAround("don", "'t"), wordAroundCursor("don", "'t", max))
        assertTrue(cursorInsideWord("don", "'t"))
    }

    @Test
    fun theStartOrEndOfAWordIsNotInsideIt() {
        // `big |cats` is the start of the next word and keeps the old refusal.
        assertFalse(cursorInsideWord("big ", "cats"))
        assertNull(wordAroundCursor("big ", "cats", max))
        assertFalse(cursorInsideWord("", "cats"))
        assertNull(wordAroundCursor("", "cats", max))
        assertFalse(cursorInsideWord("hello", ""))
        assertNull(wordAroundCursor("hello", " there", max))
    }

    @Test
    fun aWordRunningPastTheReadIsRefusedNotHalfRecased() {
        // The caller reads max + 1 each side, so an unbroken run that fills the read has no
        // visible end.
        val longHead = "a".repeat(max + 1)
        assertNull(wordAroundCursor(longHead, "b", max))
        assertNull(wordAroundCursor("b", "a".repeat(max + 1), max))
        assertNull(wordAroundCursor("a".repeat(13), "a".repeat(12), max))
        assertEquals(WordAround("a".repeat(12), "a".repeat(12)), wordAroundCursor("a".repeat(12), "a".repeat(12), max))
    }

    @Test
    fun theHalvesRecaseLikeTheWholeWord() {
        for (want in WordCase.entries) {
            val (h, t) = want.applyAround("hEL", "Lo")
            assertEquals(want.name, want.applyTo("hELLo"), h + t)
        }
        assertEquals("Hel" to "lo", WordCase.TITLE.applyAround("hEL", "LO"))
        assertEquals("STRA" to "SSE", WordCase.UPPER.applyAround("Stra", "ße"))
    }
}
