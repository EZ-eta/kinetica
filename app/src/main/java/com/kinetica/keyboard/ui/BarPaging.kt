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

import kotlin.math.abs

/**
 * Which page a drag across the suggestion bar lands on.
 *
 * A page swipe starts anywhere on the bar and goes either way, so it shares its start
 * with the tap and the upward flick that commit a word and the long press that reweights one.
 * Horizontal dominance tells it apart: the flick and the slide are vertical, and a wandering
 * tap drifts along the finger's axis more than across it.
 *
 * Pure, so the rule is testable without a view, like [BarAdjust].
 */
object BarPaging {

    /**
     * The page [page] becomes after a drag of [dx] by [dy] pixels, or -1 when the movement
     * is not a page swipe. Wraps in both directions, so the last page's forward swipe
     * reaches the first.
     */
    fun pageFor(page: Int, pageCount: Int, dx: Float, dy: Float, travelPx: Float): Int {
        if (pageCount <= 1) return -1
        if (abs(dx) < travelPx) return -1
        if (abs(dx) <= abs(dy)) return -1
        // Leftward travel is forward, as the page dots read left to right.
        val step = if (dx < 0f) 1 else -1
        return Math.floorMod(page + step, pageCount)
    }
}
