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
 * The line drawn round each key, in px. Capped at a share of the narrowest key's shorter side, so
 * the widest setting on the 24 dp landscape keys still leaves the label its room.
 *
 * No Android imports, so the rule is testable without a device.
 */
object KeyBorder {

    const val MIN_DP = 1
    const val MAX_DP = 4

    /**
     * 4 dp is 14% of a 29 dp portrait key inside its gaps; on the 19 dp narrowest landscape key the
     * line stops near 3 dp.
     */
    const val MAX_SHARE = 0.15f

    /** The width for [widthDp] (0 = off); [narrowestSidePx] 0 means no layout yet, so no cap. */
    fun widthPx(widthDp: Int, density: Float, narrowestSidePx: Float): Float {
        if (widthDp <= 0) return 0f
        val px = widthDp.coerceIn(MIN_DP, MAX_DP) * density
        return if (narrowestSidePx > 0f) minOf(px, narrowestSidePx * MAX_SHARE) else px
    }

    /** How far the stroked rect sits inside the key, so the line stays within it. */
    fun inset(widthPx: Float): Float = widthPx / 2f
}
