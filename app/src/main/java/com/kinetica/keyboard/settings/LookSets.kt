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
import android.content.res.Configuration

/**
 * The day and night looks. The day look is the settings as they always were; the night look is
 * the same keys with [Prefs.NIGHT_SUFFIX], drawn while the phone is in its dark theme and the
 * switch is on. Everything that reads a look reads it through [view], so no reader knows there
 * are two.
 */
object LookSets {

    /** Whether the phone's own theme is dark, from a configuration's `uiMode`. */
    fun systemNight(uiMode: Int): Boolean =
        (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Whether the night look is the one to draw. */
    fun nightDrawn(prefs: SharedPreferences, systemNight: Boolean): Boolean =
        systemNight && prefs.getBoolean(Prefs.LOOK_SETS, Prefs.DEFAULT_LOOK_SETS)

    /** [prefs] with each look key read from its night twin when [night]. */
    fun view(prefs: SharedPreferences, night: Boolean): SharedPreferences =
        if (night) NightView(prefs) else prefs

    /**
     * Starts the night look as a copy of the day look, the first time the switch is turned on;
     * later it is left as it was edited. Inflating the settings has stored the rows' defaults
     * under the twins by then, so every twin is written over, not only the missing ones.
     * Returns false when it had already run.
     */
    fun seed(prefs: SharedPreferences): Boolean {
        if (prefs.getBoolean(Prefs.LOOK_NIGHT_SEEDED, false)) return false
        val stored = prefs.all
        val e = prefs.edit()
        for (key in Prefs.NIGHT_TWINS) {
            val twin = Prefs.nightTwin(key)
            when (val v = stored[key]) {
                is String -> e.putString(twin, v)
                is Int -> e.putInt(twin, v)
                is Boolean -> e.putBoolean(twin, v)
                // Never written: the twin shows the same default.
                else -> e.remove(twin)
            }
        }
        e.putBoolean(Prefs.LOOK_NIGHT_SEEDED, true)
        e.apply()
        return true
    }

    private class NightView(private val base: SharedPreferences) : SharedPreferences by base {
        private fun k(key: String?): String? = key?.takeIf { it in Prefs.NIGHT_TWINS }?.let(Prefs::nightTwin) ?: key

        override fun getString(key: String?, defValue: String?): String? = base.getString(k(key), defValue)
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
            base.getStringSet(k(key), defValues)
        override fun getInt(key: String?, defValue: Int): Int = base.getInt(k(key), defValue)
        override fun getLong(key: String?, defValue: Long): Long = base.getLong(k(key), defValue)
        override fun getFloat(key: String?, defValue: Float): Float = base.getFloat(k(key), defValue)
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = base.getBoolean(k(key), defValue)
        override fun contains(key: String?): Boolean = base.contains(k(key))
    }
}
