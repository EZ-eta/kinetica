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
 * Which enabled languages suggest: the active one alone, the next one mixed in behind it, or up to
 * three as equals following the text. One choice, since with both old switches on mix did nothing.
 */
enum class LanguageMode(val value: String) {
    ONE("one"),
    MIX("mix"),
    FOLLOW("follow"),
    ;

    companion object {
        fun fromPref(value: String?): LanguageMode = when (value) {
            MIX.value -> MIX
            FOLLOW.value -> FOLLOW
            else -> ONE
        }

        /**
         * The mode the two old switches in [stored] stand for, or null when neither is there. Follow
         * wins over mix: with both on, follow alone ran.
         */
        fun fromLegacy(stored: Map<String, *>): LanguageMode? {
            if (Prefs.NO_PRIMARY !in stored && Prefs.AUTO_DETECT_LANGUAGE !in stored) return null
            return when {
                stored[Prefs.NO_PRIMARY] as? Boolean == true -> FOLLOW
                stored[Prefs.AUTO_DETECT_LANGUAGE] as? Boolean == true -> MIX
                else -> ONE
            }
        }

        /**
         * Turns the old switches into the mode and removes them. They win over a stored mode: present,
         * they are either not migrated yet or brought back by an old backup, so the order of the
         * callers never matters.
         */
        @SuppressLint("ApplySharedPref") // the config read right after must see the mode
        fun migrate(prefs: SharedPreferences) {
            val mode = fromLegacy(prefs.all) ?: return
            prefs.edit()
                .putString(Prefs.LANGUAGE_MODE, mode.value)
                .remove(Prefs.NO_PRIMARY)
                .remove(Prefs.AUTO_DETECT_LANGUAGE)
                .commit()
        }
    }
}
