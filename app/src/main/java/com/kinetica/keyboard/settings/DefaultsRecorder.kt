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

import androidx.preference.PreferenceDataStore

/**
 * Answers every read with the default it was given, remembers it, and keeps no write.
 *
 * The changed-settings screen inflates the whole tree through it to learn each row's default.
 * A row writes while it inflates (a switch, a list, a slider persist the value they just read),
 * and the base store throws on a write: the screen crashed on open. A row also reads again with
 * a guard value before writing, so the first read of a key is its default and the later ones are
 * kept out.
 */
internal class DefaultsRecorder : PreferenceDataStore() {
    private val firstSeen = HashMap<String, Any?>()

    /** Each key's default, as the row first asked for it. */
    val seen: Map<String, Any?> get() = firstSeen

    private fun <T> record(key: String, value: T): T = value.also { if (!firstSeen.containsKey(key)) firstSeen[key] = it }

    override fun getBoolean(key: String, defValue: Boolean): Boolean = record(key, defValue)
    override fun getInt(key: String, defValue: Int): Int = record(key, defValue)
    override fun getLong(key: String, defValue: Long): Long = record(key, defValue)
    override fun getFloat(key: String, defValue: Float): Float = record(key, defValue)
    override fun getString(key: String, defValue: String?): String? = record(key, defValue)
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        defValues.also { record(key, it?.toSet()) }

    override fun putBoolean(key: String, value: Boolean) = Unit
    override fun putInt(key: String, value: Int) = Unit
    override fun putLong(key: String, value: Long) = Unit
    override fun putFloat(key: String, value: Float) = Unit
    override fun putString(key: String, value: String?) = Unit
    override fun putStringSet(key: String, values: MutableSet<String>?) = Unit
}
