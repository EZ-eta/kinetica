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

package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.Dwell
import com.kinetica.keyboard.engine.models.PathPoint
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Where the next word starts in a stroke that goes on past the apostrophe key: the thumb travels from
 * the key to the word's first letter, and that travel spells nothing. Read as letters, `'` to `a`
 * across the home row made `altro` decode as `lavoro`.
 */
internal object LeadIn {

    /**
     * Index of the word's first letter in [path]: the first corner or pause at least
     * [KineticaConstants.LEAD_IN_WINDOW_KW] of travel from the key. 0 when there is neither: a
     * straight run gives no sign of where the word began, so all of it is read, softStart.
     */
    fun end(path: List<PathPoint>, dwells: List<Dwell>): Int {
        val w = KineticaConstants.LEAD_IN_WINDOW_KW
        val arc = FloatArray(path.size)
        for (i in 1 until path.size) arc[i] = arc[i - 1] + dist(path[i], path[i - 1])
        val dwell = dwells.firstOrNull { arc[it.enterIdx] >= w }?.enterIdx ?: -1
        val corner = firstCorner(path, arc, w)
        return when {
            dwell >= 0 && corner >= 0 -> minOf(dwell, corner)
            dwell >= 0 -> dwell
            corner >= 0 -> corner
            else -> 0
        }
    }

    /**
     * The first sample where the direction over [w] kw before and after it turns by at least
     * [KineticaConstants.LEAD_IN_TURN_DEG], at the sharpest sample of that turn; -1 when none.
     */
    private fun firstCorner(path: List<PathPoint>, arc: FloatArray, w: Float): Int {
        var best = -1
        var bestAngle = 0f
        for (i in path.indices) {
            // [back] finds nothing within [w] of the key, so the turn is read past it only.
            val a = back(arc, i, w)
            val b = ahead(arc, i, w)
            if (a < 0 || b < 0) {
                if (best >= 0) return best
                continue
            }
            val angle = turn(path[a], path[i], path[b])
            if (angle >= KineticaConstants.LEAD_IN_TURN_DEG) {
                if (angle > bestAngle) {
                    best = i
                    bestAngle = angle
                }
            } else if (best >= 0) {
                return best
            }
        }
        return best
    }

    private fun back(arc: FloatArray, i: Int, w: Float): Int {
        var j = i
        while (j > 0 && arc[i] - arc[j] < w) j--
        return if (arc[i] - arc[j] >= w) j else -1
    }

    private fun ahead(arc: FloatArray, i: Int, w: Float): Int {
        var j = i
        while (j < arc.size - 1 && arc[j] - arc[i] < w) j++
        return if (arc[j] - arc[i] >= w) j else -1
    }

    /** Degrees between the heading into [p] from [a] and out of it toward [b]. */
    private fun turn(a: PathPoint, p: PathPoint, b: PathPoint): Float {
        val ux = p.x - a.x
        val uy = p.y - a.y
        val vx = b.x - p.x
        val vy = b.y - p.y
        val n = sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy)
        if (n < 1.014e-6f) return 0f
        val c = ((ux * vx + uy * vy) / n).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(c).toDouble()).toFloat()
    }

    private fun dist(a: PathPoint, b: PathPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
}
