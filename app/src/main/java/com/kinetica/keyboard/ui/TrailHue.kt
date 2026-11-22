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

/**
 * The hue a swipe trail is drawn in. Rainbow starts the right thumb 60 deg on and steps 30 deg
 * per key; any other choice keeps its own hue for both thumbs, or the setting reads as rainbow.
 *
 * No Android imports, so the rule is testable without a device.
 */
object TrailHue {

    const val RIGHT_OFFSET = 60f
    const val STEP = 30f

    /** The hue a stream starts at, over [base] in degrees. */
    fun start(base: Float, stream: StreamId, cycles: Boolean): Float =
        (base + if (cycles && stream == StreamId.RIGHT) RIGHT_OFFSET else 0f) % 360f

    /** The hue after the trail enters a new key. */
    fun next(hue: Float, cycles: Boolean): Float = if (cycles) (hue + STEP) % 360f else hue
}
