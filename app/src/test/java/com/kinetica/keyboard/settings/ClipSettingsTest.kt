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

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The clipboard history's switches: both off by default in code and XML, in one nested screen,
 * the master never carried by a backup, and off clearing the store from settings as well.
 */
class ClipSettingsTest {

    private val xml by lazy { PrefsXml.read() }

    private fun row(key: String): String =
        requireNotNull(Regex("""android:key="$key"[^>]*>""").find(xml)?.value) { "$key not in keyboard_prefs.xml" }

    @Test
    fun bothSwitchesAreOffByDefault() {
        assertFalse(Prefs.DEFAULT_CLIP_HISTORY)
        assertFalse(Prefs.DEFAULT_CLIP_SHORT)
        assertTrue(row(Prefs.CLIP_HISTORY).contains("android:defaultValue=\"false\""))
        assertTrue(row(Prefs.CLIP_SHORT).contains("android:defaultValue=\"false\""))
    }

    @Test
    fun theRowsSitInOneScreenUnderTyping() {
        val typing = xml.substringAfter("android:key=\"pref_group_typing\"").substringBefore("android:key=\"pref_group_bar\"")
        val screen = typing.substringAfter("android:key=\"pref_group_clips\"").substringBefore("</PreferenceScreen>")
        for (key in listOf(Prefs.CLIP_HISTORY, Prefs.CLIP_SHORT, Prefs.CLIP_CLEAR)) {
            assertTrue(key, screen.contains("android:key=\"$key\""))
        }
        assertTrue(row(Prefs.CLIP_SHORT).contains("android:dependency=\"${Prefs.CLIP_HISTORY}\""))
        assertTrue(row(Prefs.CLIP_CLEAR).contains("android:dependency=\"${Prefs.CLIP_HISTORY}\""))
    }

    @Test
    fun anImportNeverTurnsTheHistoryOn() {
        assertFalse(Backup.carried(Prefs.CLIP_HISTORY))
        // The life of a clip is a preference like any other.
        assertTrue(Backup.carried(Prefs.CLIP_SHORT))
    }

    @Test
    fun offClearsTheStoreFromSettings() {
        val src = read("app/src/main/java/com/kinetica/keyboard/settings/KeyboardPrefsFragment.kt")
        val wire = src.substringAfter("private fun wireClips() {").substringBefore("\n    }\n")
        assertTrue(wire.contains("findPreference<TwoStatePreference>(Prefs.CLIP_HISTORY)"))
        assertTrue(wire.contains("ClipVault.configure(requireContext(), enabled = false"))
        assertTrue(wire.contains("ClipVault.clear(requireContext(), \"settings\")"))
        assertTrue(src.contains("        wireClips()\n"))
    }

    private fun read(rel: String): String {
        val direct = Paths.get(rel.removePrefix("app/"))
        val p: Path = if (Files.exists(direct)) direct else Paths.get(rel)
        assumeTrue("$rel not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }
}
