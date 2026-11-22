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
 * The two orientation groups of Size and layout, each a header row over a category of rows. The
 * group of the orientation the settings are held in starts open, the other closed, so landscape
 * stays out of the way upright and its own sizes come first sideways.
 */
object SizeGroups {

    const val PORTRAIT_HEADER = "pref_size_portrait"
    const val PORTRAIT_ROWS = "pref_size_portrait_rows"
    const val LANDSCAPE_HEADER = "pref_size_landscape"
    const val LANDSCAPE_ROWS = "pref_size_landscape_rows"

    /** Header key to the key of the rows it opens and closes. */
    val GROUPS: Map<String, String> = mapOf(PORTRAIT_HEADER to PORTRAIT_ROWS, LANDSCAPE_HEADER to LANDSCAPE_ROWS)

    /** Whether [rows] starts open with the settings held [landscape]. */
    fun startsOpen(rows: String, landscape: Boolean): Boolean = (rows == LANDSCAPE_ROWS) == landscape
}
