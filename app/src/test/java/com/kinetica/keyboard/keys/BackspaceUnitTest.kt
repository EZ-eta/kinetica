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

package com.kinetica.keyboard.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A backspace slide's unit: a held shift swaps it until the first unit, then it holds. */
class BackspaceUnitTest {

    @Test
    fun aShiftHeldBeforeTheFirstUnitSwapsIt() {
        val u = BackspaceUnit()
        assertFalse(u.chars)
        u.flipped = true
        assertTrue(u.chars)
        u.charMode = true
        assertFalse("chars by setting, words under shift", u.chars)
    }

    @Test
    fun theModeIsFixedAtTheFirstUnit() {
        val u = BackspaceUnit()
        u.fix()
        u.flipped = true
        assertFalse("a flip after the first unit would change what the count means", u.chars)
        assertEquals(DeleteSpan.Granularity.WORDS, u.granularity)
        u.release()
        assertTrue("the next touch reads shift again", u.chars)
    }

    @Test
    fun aFixedFlipHoldsAfterShiftIsLifted() {
        val u = BackspaceUnit()
        u.flipped = true
        u.fix()
        u.flipped = false
        assertEquals(DeleteSpan.Granularity.CHARS, u.granularity)
    }

    @Test
    fun punctuationStopsFollowTheWordMode() {
        val u = BackspaceUnit()
        u.stopsAtPunctuation = true
        assertEquals(DeleteSpan.Granularity.PIECES, u.granularity)
        u.flipped = true
        assertEquals(DeleteSpan.Granularity.CHARS, u.granularity)
        u.charMode = true
        assertEquals("words under shift keep the stops", DeleteSpan.Granularity.PIECES, u.granularity)
    }
}
