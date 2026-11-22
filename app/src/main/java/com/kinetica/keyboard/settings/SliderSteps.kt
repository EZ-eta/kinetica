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

package com.kinetica.keyboard.settings

/**
 * The arithmetic behind [SteppedSliderPreference], kept free of Android so it can be tested.
 */
object SliderSteps {

    /**
     * The index of the step nearest [value], the lower one on a tie. A stored value that is
     * not a step, from the old linear slider or a derived default, shows where it is closest
     * instead of jumping to an end.
     */
    fun nearestIndex(steps: IntArray, value: Int): Int {
        require(steps.isNotEmpty()) { "a slider needs at least one step" }
        var best = 0
        for (i in 1 until steps.size) {
            if (Math.abs(steps[i] - value) < Math.abs(steps[best] - value)) best = i
        }
        return best
    }
}
