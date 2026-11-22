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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How many points a swipe trail lays.
 *
 * Timed from each sample, a one-colour trail sampled every 4 ms kept one point at the finger and
 * drew nothing; rainbow hid it, since its hue change per key forces a point.
 */
class TrailSamplingTest {

    /** Points a one-colour trail lays over a swipe sampled every [stepMs]. */
    private fun pointsLaid(durationMs: Long, stepMs: Long): Int {
        val sampling = TrailSampling(8L)
        var points = 0
        var t = 0L
        while (t <= durationMs) {
            if (sampling.lays(0, t, forced = false, sameHue = true, hasLast = points > 0)) points++
            t += stepMs
        }
        return points
    }

    @Test
    fun aOneColourSwipeSampledDenselyHasLength() {
        // 69 samples 4 ms apart over 296 ms, a swipe as the touch history delivers it: one point
        // per 8 ms.
        assertEquals(38, pointsLaid(296L, 4L))
    }

    @Test
    fun samplesFurtherApartEachLayAPoint() {
        assertEquals(31, pointsLaid(300L, 10L))
    }

    @Test
    fun aHueChangeOrABreakLaysAPoint() {
        val sampling = TrailSampling(8L)
        assertTrue(sampling.lays(0, 100L, forced = false, sameHue = true, hasLast = false))
        assertFalse(sampling.lays(0, 101L, forced = false, sameHue = true, hasLast = true))
        // Rainbow steps the hue per key, and a lift breaks the run.
        assertTrue(sampling.lays(0, 102L, forced = false, sameHue = false, hasLast = true))
        assertTrue(sampling.lays(0, 103L, forced = true, sameHue = true, hasLast = true))
    }

    @Test
    fun theThumbsAreTimedApart() {
        val sampling = TrailSampling(8L)
        assertTrue(sampling.lays(0, 100L, forced = false, sameHue = true, hasLast = false))
        assertTrue(sampling.lays(1, 104L, forced = false, sameHue = true, hasLast = false))
        assertFalse(sampling.lays(0, 106L, forced = false, sameHue = true, hasLast = true))
        assertTrue(sampling.lays(0, 108L, forced = false, sameHue = true, hasLast = true))
    }
}
