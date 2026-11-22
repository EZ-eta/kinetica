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

import com.kinetica.keyboard.engine.models.StreamId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The trail's hue per colour setting.
 *
 * With the step on for every choice, a red or a custom trail read as rainbow by the
 * third key.
 */
class TrailHueTest {

    /** The hues one stream is drawn in over a swipe through [keys] keys. */
    private fun swipe(base: Float, stream: StreamId, cycles: Boolean, keys: Int): List<Float> {
        var hue = TrailHue.start(base, stream, cycles)
        val out = mutableListOf(hue)
        repeat(keys) {
            hue = TrailHue.next(hue, cycles)
            out += hue
        }
        return out
    }

    @Test
    fun aChosenColourKeepsItsHueThroughASwipe() {
        // Green, nine keys: `keyboards` drawn edge to edge.
        assertEquals(List(10) { 120f }, swipe(120f, StreamId.LEFT, cycles = false, keys = 9))
    }

    @Test
    fun aChosenColourIsTheSameForBothThumbs() {
        assertEquals(275f, TrailHue.start(275f, StreamId.RIGHT, cycles = false), 0f)
        assertEquals(275f, TrailHue.start(275f, StreamId.LEFT, cycles = false), 0f)
    }

    @Test
    fun rainbowStepsPerKeyAndWraps() {
        assertEquals(
            listOf(0f, 30f, 60f, 90f, 120f, 150f, 180f, 210f, 240f, 270f, 300f, 330f, 0f),
            swipe(0f, StreamId.LEFT, cycles = true, keys = 12),
        )
    }

    @Test
    fun rainbowStartsTheRightThumbApart() {
        // Two trails at once stay told apart by colour as well as by side.
        assertEquals(60f, TrailHue.start(0f, StreamId.RIGHT, cycles = true), 0f)
        assertEquals(0f, TrailHue.start(0f, StreamId.LEFT, cycles = true), 0f)
    }
}
