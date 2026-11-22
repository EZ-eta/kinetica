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

import com.kinetica.keyboard.keys.ShiftState.State
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Shift taps on an injected clock. The 300 ms window is the one the spacebar's double tap uses;
 * 120 ms is a quick double tap, 600 ms two separate taps.
 */
class ShiftStateTest {

    private var now = 10_000L
    private val shift = ShiftState { now }

    private fun tapAfter(ms: Long) {
        now += ms
        shift.onShiftKey()
    }

    @Test
    fun aDoubleTapLocksCaps() {
        tapAfter(0)
        assertEquals(State.SHIFT, shift.state)
        tapAfter(120)
        assertEquals(State.CAPS_LOCK, shift.state)
        tapAfter(600)
        assertEquals(State.NONE, shift.state)
    }

    @Test
    fun twoSlowTapsTurnShiftOnAndOff() {
        tapAfter(0)
        tapAfter(600)
        assertEquals(State.NONE, shift.state)
    }

    @Test
    fun aDoubleTapAtASentenceStartLocksCaps() {
        // #39: the sentence start is already SHIFT. The first tap clears it, and the second
        // used to set it again, so the pair went SHIFT, NONE, SHIFT and never locked.
        shift.autoShift(true)
        tapAfter(600)
        assertEquals(State.NONE, shift.state)
        tapAfter(120)
        assertEquals(State.CAPS_LOCK, shift.state)
    }

    @Test
    fun aSingleTapAtASentenceStartStillClearsTheShift() {
        shift.autoShift(true)
        tapAfter(600)
        assertEquals(State.NONE, shift.state)
        now += 600
        assertEquals(State.NONE, shift.state)
    }

    @Test
    fun aQuickTapAfterLeavingCapsLockIsAShiftNotALock() {
        tapAfter(0)
        tapAfter(120)
        assertEquals(State.CAPS_LOCK, shift.state)
        tapAfter(600)
        assertEquals(State.NONE, shift.state)
        tapAfter(120)
        assertEquals(State.SHIFT, shift.state)
    }

    @Test
    fun aLetterBetweenTwoTapsMakesTwoCapitals() {
        tapAfter(0)
        now += 100
        shift.onLetterCommitted()
        assertEquals(State.NONE, shift.state)
        tapAfter(100)
        assertEquals(State.SHIFT, shift.state)
    }

    @Test
    fun aKeyBetweenTwoTapsIsNoDoubleTapAfterClearingTheShift() {
        // The letter typed after clearing a sentence start's capital commits with shift off, so
        // onLetterCommitted is not called; the key itself must still disarm the pair.
        shift.autoShift(true)
        tapAfter(600)
        now += 100
        shift.onOtherKey()
        tapAfter(100)
        assertEquals(State.SHIFT, shift.state)
    }

    @Test
    fun capsLockSurvivesAnAutoShift() {
        tapAfter(0)
        tapAfter(120)
        shift.autoShift(false)
        assertEquals(State.CAPS_LOCK, shift.state)
        shift.autoShift(true)
        assertEquals(State.CAPS_LOCK, shift.state)
    }

    @Test
    fun anUndoneCapitalComesBackAsAOneShotShift() {
        // The gesture undo puts a spent capital back through autoShift(true).
        tapAfter(0)
        shift.onLetterCommitted()
        shift.autoShift(true)
        assertEquals(State.SHIFT, shift.state)
        assertEquals("Word", shift.apply("word"))
        shift.onLetterCommitted()
        assertEquals(State.NONE, shift.state)
    }
}
