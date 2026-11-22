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

package com.kinetica.keyboard.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** A colour typed as `#RRGGBB`: what is taken, what is refused, and how it is written back. */
class HexColorTest {

    @Test
    fun aSixDigitColourInAnyCaseWithOrWithoutTheHash() {
        val green = HexColor.Result.Valid(0xFF2E7D32.toInt())
        assertEquals(green, HexColor.parse("#2E7D32"))
        assertEquals(green, HexColor.parse("#2e7d32"))
        assertEquals(green, HexColor.parse("2E7D32"))
    }

    @Test
    fun spacesAreDropped() {
        assertEquals(HexColor.Result.Valid(0xFF2E7D32.toInt()), HexColor.parse(" # 2E 7D 32 "))
    }

    @Test
    fun nothingTypedIsBlank() {
        assertEquals(HexColor.Result.Blank, HexColor.parse(""))
        assertEquals(HexColor.Result.Blank, HexColor.parse("   "))
        assertEquals(HexColor.Result.Blank, HexColor.parse(null))
    }

    @Test
    fun theWrongLengthOrABadDigitIsRefused() {
        for (typed in listOf("#12345", "#1234567", "#GG0000", "#12 34 5Z", "##123456", "#FFF")) {
            assertEquals(typed, HexColor.Result.Invalid(typed.filterNot { it.isWhitespace() }), HexColor.parse(typed))
        }
    }

    @Test
    fun aColourIsWrittenBackAsSixUpperCaseDigits() {
        assertEquals("#2E7D32", HexColor.format(0xFF2E7D32.toInt()))
        assertEquals("#00000A", HexColor.format(0x0000000A))
        assertEquals("#808080", HexColor.format(0x80808080.toInt()))
    }

    @Test
    fun anythingButAColourFallsBack() {
        assertEquals(7, HexColor.colorOr("teal", 7))
        assertEquals(7, HexColor.colorOr(null, 7))
        assertEquals(0xFF000000.toInt(), HexColor.colorOr("#000000", 7))
    }
}
