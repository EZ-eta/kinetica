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

/**
 * Per stream, whether a touch sample lays a new trail point or moves the last one.
 *
 * One point per [minIntervalMs] bounds draw work on high-refresh screens. Timed from when the
 * last point was laid, not from its latest sample: dense touch history kept moving one point
 * forward, and a one-colour trail, with no hue change to force a point, had no length.
 *
 * No Android imports, so the rule is testable without a device.
 */
class TrailSampling(private val minIntervalMs: Long) {

    private val laidAt = LongArray(2)

    /**
     * True when the sample at [t] on [stream] lays a point. A break ([forced]), a hue change or
     * an empty trail ([hasLast] false) always lays one.
     */
    fun lays(stream: Int, t: Long, forced: Boolean, sameHue: Boolean, hasLast: Boolean): Boolean {
        val since = t - laidAt[stream]
        if (hasLast && !forced && sameHue && since >= 0 && since < minIntervalMs) return false
        laidAt[stream] = t
        return true
    }
}
