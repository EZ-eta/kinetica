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
 * The two look groups of Theme and look, each a header row over a category of rows, as on Size and
 * layout ([SizeGroups]). The look the keyboard shows now starts open, the other closed; the night
 * group is there only while the day and night looks are on.
 */
object LookGroups {

    const val DAY_HEADER = "pref_look_day"
    const val DAY_ROWS = "pref_look_day_rows"
    const val NIGHT_HEADER = "pref_look_night"
    const val NIGHT_ROWS = "pref_look_night_rows"

    /** Header key to the key of the rows it opens and closes. */
    val GROUPS: Map<String, String> = mapOf(DAY_HEADER to DAY_ROWS, NIGHT_HEADER to NIGHT_ROWS)

    /** Whether [rows] starts open, [nightShown] when the keyboard draws the night look now. */
    fun startsOpen(rows: String, nightShown: Boolean): Boolean = (rows == NIGHT_ROWS) == nightShown
}
