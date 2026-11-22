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

import android.content.SharedPreferences
import com.kinetica.keyboard.keys.EdgeSwipeBinding
import com.kinetica.keyboard.keys.EdgeSwipeBindings

/** Swipes added to the defaults after a swipe set could be saved, given to a saved set once each. */
object EdgeSwipeDefaults {

    fun applyTo(prefs: SharedPreferences) {
        seed(prefs, Prefs.SHIFT_CASE_SEEDED, EdgeSwipeBindings::withShiftCase)
        seed(prefs, Prefs.EDIT_PAGE_SEEDED, EdgeSwipeBindings::withEditPage)
        seed(prefs, Prefs.EDIT_PAGE_UP_SEEDED, EdgeSwipeBindings::withEditPageUp)
    }

    /** Once per [flag]: a saved set gets [add]'s change; nothing saved reads as the defaults. */
    private fun seed(prefs: SharedPreferences, flag: String, add: (List<EdgeSwipeBinding>) -> List<EdgeSwipeBinding>) {
        if (prefs.getBoolean(flag, false)) return
        val edit = prefs.edit().putBoolean(flag, true)
        prefs.getString(Prefs.EDGE_SWIPES, null)?.let { stored ->
            edit.putString(Prefs.EDGE_SWIPES, EdgeSwipeBindings(add(EdgeSwipeBindings.parse(stored).bindings)).serialize())
        }
        // apply() updates the in-memory set at once, so the next seed reads what this one wrote.
        edit.apply()
    }
}
