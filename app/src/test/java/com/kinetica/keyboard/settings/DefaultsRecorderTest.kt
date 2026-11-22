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

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The changed-settings screen inflates every row through [DefaultsRecorder]. Each call sequence
 * below is what an androidx row does on inflation: read with its default, then persist the value,
 * which first reads again with a guard value and then writes.
 */
class DefaultsRecorderTest {

    @Test
    fun aSwitchListAndSliderInflateWithoutAWriteAndKeepTheirDefault() {
        val r = DefaultsRecorder()
        // TwoStatePreference.setChecked -> persistBoolean: guard read with !value, then a write.
        val on = r.getBoolean("switch", true)
        r.getBoolean("switch", !on)
        r.putBoolean("switch", on)
        // ListPreference.setValue -> persistString: guard read with null.
        val v = r.getString("list", "normal")
        r.getString("list", null)
        r.putString("list", v)
        // SeekBarPreference.setValueInternal -> persistInt: guard read with ~value.
        val n = r.getInt("slider", 44)
        r.getInt("slider", n.inv())
        r.putInt("slider", n)
        val set = r.getStringSet("langs", mutableSetOf("en"))
        r.getStringSet("langs", null)
        r.putStringSet("langs", set)
        r.putLong("l", 1L)
        r.putFloat("f", 1f)
        assertEquals(mapOf("switch" to true, "list" to "normal", "slider" to 44, "langs" to setOf("en")), r.seen)
    }
}
