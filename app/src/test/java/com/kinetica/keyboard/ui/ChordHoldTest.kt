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

package com.kinetica.keyboard.ui

import com.kinetica.keyboard.engine.KineticaConstants
import com.kinetica.keyboard.settings.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A held trigger whose chord labels show keeps them through a small drift: a thumb resting on
 * `?123` or the spacebar moves a little, and lost the labels and the arm at the tap slop.
 */
class ChordHoldTest {

    private val tap = KineticaConstants.TAP_MAX_DISP_DP
    private val wiggle = KineticaConstants.CHORD_HOLD_WIGGLE_DP

    /** Whether a drift of [dp] latches the trigger as moved. */
    private fun moved(dp: Float, labelsUp: Boolean, wiggleOn: Boolean): Boolean =
        dp > triggerSlopPx(labelsUp, wiggleOn, tap, wiggle)

    @Test
    fun aDriftWithTheLabelsUpKeepsThem() {
        // 18 dp: past the tap slop, inside the wiggle. The reported case.
        assertFalse(moved(18f, labelsUp = true, wiggleOn = true))
        assertFalse(moved(wiggle, labelsUp = true, wiggleOn = true))
    }

    @Test
    fun aDriftPastTheWiggleStillMoves() {
        assertTrue(moved(wiggle + 1f, labelsUp = true, wiggleOn = true))
    }

    @Test
    fun beforeTheLabelsTheTapSlopHolds() {
        // A quick slide or page tap reads as before: the wiggle starts with the labels.
        assertTrue(moved(18f, labelsUp = false, wiggleOn = true))
        assertFalse(moved(tap, labelsUp = false, wiggleOn = true))
    }

    @Test
    fun theSwitchOffKeepsTheTapSlop() {
        assertTrue(moved(18f, labelsUp = true, wiggleOn = false))
    }

    @Test
    fun theWiggleStaysUnderTheModeSlide() {
        // The ?123 slide to the numpad is 30 dp (detectModeSlide); a wiggle that reached it
        // would hold the labels through a slide.
        assertTrue(wiggle > tap)
        assertTrue(wiggle < 30f)
    }

    @Test
    fun theSpacebarCursorWaitsWhileItsLabelsShow() {
        assertFalse(spaceSlideAllowed(labelsUp = true, wiggleOn = true))
        assertTrue(spaceSlideAllowed(labelsUp = false, wiggleOn = true))
        assertTrue(spaceSlideAllowed(labelsUp = true, wiggleOn = false))
        assertTrue(spaceSlideAllowed(labelsUp = false, wiggleOn = false))
    }

    @Test
    fun theSpacebarWiggleIsOnByDefault() {
        assertEquals(true, Prefs.DEFAULT_SPACE_CHORD_WIGGLE)
    }
}
