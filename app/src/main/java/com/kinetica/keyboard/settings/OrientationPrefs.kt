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

import android.annotation.SuppressLint
import android.content.SharedPreferences

/**
 * Landscape's own sizes, started from the portrait ones so nothing moves on upgrade.
 */
object OrientationPrefs {

    /**
     * Writes each landscape size that is not stored yet from its portrait value. A stored one is
     * never touched, and an untouched portrait size leaves both at the shipped default. Run before
     * the settings inflate: inflation would store the default under the twin first.
     */
    @SuppressLint("ApplySharedPref") // the config read right after must see the copies
    fun migrate(prefs: SharedPreferences) {
        val stored = prefs.all
        val e = prefs.edit()
        var changed = false
        for (key in Prefs.LANDSCAPE_TWINS) {
            val twin = Prefs.landscapeTwin(key)
            val value = stored[key] as? Int ?: continue
            if (twin in stored) continue
            e.putInt(twin, value)
            changed = true
        }
        if (changed) e.commit()
    }
}
