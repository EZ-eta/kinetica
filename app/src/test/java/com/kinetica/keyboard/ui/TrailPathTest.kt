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
import org.junit.Test

/**
 * The joins in a smoothed trail.
 *
 * If one piece does not end where the next begins, a fast swipe draws as a dotted line instead
 * of a curve. The arithmetic carries no Canvas, so that invariant is testable on the JVM.
 */
class TrailPathTest {

    /** A path with a corner in it, so a straight-line renderer and this one differ. */
    private val xs = floatArrayOf(0f, 10f, 20f, 20f, 30f)
    private val ys = floatArrayOf(0f, 0f, 0f, 10f, 20f)

    private fun quadFor(i: Int): FloatArray {
        val out = FloatArray(TrailPath.SIZE)
        val last = xs.size - 1
        TrailPath.quadInto(
            out,
            xs[i - 1], ys[i - 1], xs[i], ys[i],
            if (i < last) xs[i + 1] else xs[i],
            if (i < last) ys[i + 1] else ys[i],
            isFirst = i == 1,
            isLast = i == last,
        )
        return out
    }

    @Test
    fun consecutivePiecesShareAnEndpoint() {
        for (i in 1 until xs.size - 1) {
            val a = quadFor(i)
            val b = quadFor(i + 1)
            assertEquals("piece $i endX", a[4], b[0], 0f)
            assertEquals("piece $i endY", a[5], b[1], 0f)
        }
    }

    @Test
    fun theFirstPieceStartsAtTheTrailsOwnTail() {
        val first = quadFor(1)
        assertEquals(xs[0], first[0], 0f)
        assertEquals(ys[0], first[1], 0f)
    }

    @Test
    fun theLastPieceEndsUnderTheFinger() {
        val last = xs.size - 1
        val piece = quadFor(last)
        assertEquals(xs[last], piece[4], 0f)
        assertEquals(ys[last], piece[5], 0f)
    }

    @Test
    fun theSampleIsTheControlPointNotACorner() {
        // The piece bends through the sample and neither endpoint is the sample, except at the
        // two ends of the run, so the trail is a curve.
        val middle = quadFor(2)
        assertEquals(xs[2], middle[2], 0f)
        assertEquals(ys[2], middle[3], 0f)
        // start is mid(10,0)-(20,0), end is mid(20,0)-(20,10).
        assertEquals(15f, middle[0], 0f)
        assertEquals(0f, middle[1], 0f)
        assertEquals(20f, middle[4], 0f)
        assertEquals(5f, middle[5], 0f)
    }

    @Test
    fun aRunOfOneSegmentIsJustTheLine() {
        // Both ends anchored, so a two-sample trail is the plain line it always was.
        val out = FloatArray(TrailPath.SIZE)
        TrailPath.quadInto(out, 1f, 2f, 3f, 4f, 3f, 4f, isFirst = true, isLast = true)
        assertEquals(1f, out[0], 0f)
        assertEquals(2f, out[1], 0f)
        assertEquals(3f, out[4], 0f)
        assertEquals(4f, out[5], 0f)
    }
}
