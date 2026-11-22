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

import org.junit.Assert.assertEquals
import org.junit.Test

/** In right-to-left text the spacebar's word step follows the thumb, like the letter step. */
class CursorWordStepTest {

    @Test
    fun latinTextKeepsTheStep() {
        assertEquals(1, wordStepDirection(1, "hello ", "world"))
        assertEquals(-1, wordStepDirection(-1, "hello ", "world"))
    }

    @Test
    fun hebrewAndArabicTextTurnItRound() {
        assertEquals(-1, wordStepDirection(1, "שלום ", "עולם"))
        assertEquals(1, wordStepDirection(-1, "שלום ", "עולם"))
        assertEquals(-1, wordStepDirection(1, "مرحبا ", "بك"))
    }

    @Test
    fun digitsSpacesAndPunctuationAreLookedPast() {
        assertEquals(-1, wordStepDirection(1, "שלום 123, ", ""))
        assertEquals(1, wordStepDirection(1, "", " 42 hello"))
    }

    @Test
    fun theNearestLetterDecidesInMixedText() {
        // An English word typed inside Hebrew: the cursor sits by the English letters.
        assertEquals(1, wordStepDirection(1, "שלום hello", " עולם"))
        assertEquals(-1, wordStepDirection(1, "hello שלום", " world"))
    }

    @Test
    fun noLettersKeepTheStep() {
        assertEquals(1, wordStepDirection(1, "", ""))
        assertEquals(-1, wordStepDirection(-1, "12 ", "!"))
    }
}
