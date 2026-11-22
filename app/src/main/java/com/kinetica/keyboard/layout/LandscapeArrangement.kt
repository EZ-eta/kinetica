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

package com.kinetica.keyboard.layout

/**
 * Where the letter block sits in landscape. SPLIT is sized by its gap slider, CENTERED holds row
 * pitch at [com.kinetica.keyboard.engine.KineticaConstants.LANDSCAPE_ROW_PITCH_KW], and STRETCH
 * is the full-width board earlier versions drew.
 */
enum class LandscapeArrangement {
    SPLIT, CENTERED, STRETCH;

    companion object {
        /**
         * Android's own line for a tablet layout. An unfolded phone held upright is past it and
         * no phone is: a full-width board there stretches keys to twice a thumb's reach.
         */
        const val WIDE_SCREEN_DP = 600

        /**
         * Whether the arrangement applies: always in landscape, and in portrait on a screen
         * [WIDE_SCREEN_DP] wide or more when [wideToo] is on. Rebuilt on every configuration
         * change, so folding and unfolding switch it.
         */
        fun appliesTo(landscape: Boolean, widthDp: Int, wideToo: Boolean): Boolean =
            landscape || (wideToo && widthDp >= WIDE_SCREEN_DP)

        fun fromPref(value: String?): LandscapeArrangement = when (value) {
            "centered" -> CENTERED
            "stretch" -> STRETCH
            else -> SPLIT
        }
    }
}
