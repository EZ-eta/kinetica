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

import com.kinetica.keyboard.ui.BarMark
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A bar word's mark says what a commit does with it: a bundled word nothing, a personal one `C`, an
 * unknown one `+` when it is learned and `?` when it is not.
 */
class WordMarkTest {

    private fun mark(
        inList: Boolean = false,
        personal: Boolean = false,
        learnable: Boolean = true,
        teachesNothing: Boolean = false,
        blocked: Boolean = false,
    ): BarMark = wordMark(inList, personal, learnable, teachesNothing, blocked)

    @Test
    fun aBundledWordHasNoMarkEvenWhenLearned() {
        assertEquals(BarMark.NONE, mark(inList = true))
        assertEquals(BarMark.NONE, mark(inList = true, personal = true))
        assertEquals(BarMark.NONE, mark(inList = true, teachesNothing = true))
    }

    @Test
    fun aPersonalWordIsMarkedWhereverItIsTyped() {
        assertEquals(BarMark.PERSONAL, mark(personal = true))
        // In a field that keeps nothing it is still the user's word.
        assertEquals(BarMark.PERSONAL, mark(personal = true, teachesNothing = true))
    }

    @Test
    fun anUnknownWordIsLearnedAtCommitUnlessSomethingRefusesIt() {
        assertEquals(BarMark.LEARN, mark())
        assertEquals(BarMark.UNKNOWN, mark(teachesNothing = true))
        assertEquals(BarMark.UNKNOWN, mark(learnable = false))
        assertEquals(BarMark.UNKNOWN, mark(blocked = true))
    }

    @Test
    fun theGlyphsAreTheOnesAskedFor() {
        assertEquals(null, BarMark.NONE.glyph)
        assertEquals("+", BarMark.LEARN.glyph)
        assertEquals("?", BarMark.UNKNOWN.glyph)
        assertEquals("C", BarMark.PERSONAL.glyph)
    }

    @Test
    fun lettersWithADigitAreNotLearned() {
        // The learn's own gate, so `?` and a refused learn agree.
        assertEquals(BarMark.UNKNOWN, mark(learnable = learnable("abc1")))
        assertEquals(BarMark.LEARN, mark(learnable = learnable("kinetica")))
    }
}
