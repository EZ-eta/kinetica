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

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * White radial bursts on key contact: radius 12 -> 28 dp, alpha 255 -> 0 over
 * 80 ms. Bursts are cheap circles; a handful at most are ever alive.
 */
class BurstRenderer(private val density: Float) {

    private class Burst(val x: Float, val y: Float, val t0: Long)

    private val bursts = ArrayList<Burst>(8)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    fun spawn(x: Float, y: Float, t: Long) {
        bursts.add(Burst(x, y, t))
    }

    /** Drops finished bursts; returns true while any is still animating. */
    fun prune(now: Long): Boolean {
        bursts.removeAll { now - it.t0 >= LIFE_MS }
        return bursts.isNotEmpty()
    }

    fun clear() = bursts.clear()

    fun draw(canvas: Canvas, now: Long) {
        for (b in bursts) {
            val f = ((now - b.t0).coerceAtLeast(0)) / LIFE_MS.toFloat()
            if (f >= 1f) continue
            paint.alpha = (255 * (1f - f)).toInt()
            val radius = (12f + 16f * f) * density
            canvas.drawCircle(b.x, b.y, radius, paint)
        }
    }

    private companion object {
        const val LIFE_MS = 80L
    }
}
