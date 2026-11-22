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

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words that have to reach a setting the tree walk cannot describe.
 *
 * Two invariants. The first decays silently: a setting with no preference row and no synonyms
 * cannot be found at all.
 */
class SettingsSynonymsTest {

    @Test
    fun everySettingWithNoRowOfItsOwnCarriesTerms() {
        // The chord lead-in, the language cycle key and the peck chord key live on
        // ChordSettingsActivity with no entry in keyboard_prefs.xml, so only their terms let a
        // search reach them.
        for (key in SettingsSynonyms.EXTRA_KEYS) {
            assertTrue("$key has no synonyms and nothing else can find it",
                SettingsSynonyms.termsFor(key).isNotEmpty())
        }
    }

    @Test
    fun aSettingOnItsOwnScreenHasNoRowInTheList() {
        // The trigger case sits with the triggers; a row left in the list as well would be two
        // places to look and two search hits.
        assertTrue(Prefs.TRIGGER_CASE in SettingsSynonyms.EXTRA_KEYS)
        val xml = PrefsXml.read()
        for (key in SettingsSynonyms.EXTRA_KEYS) {
            assertTrue("$key is also in keyboard_prefs.xml", "android:key=\"$key\"" !in xml)
        }
    }

    @Test
    fun theWordsPeopleActuallyTypeFindTheirSetting() {
        // Spot checks: none of these words appears in the title of the setting it has to find.
        assertTrue("size" in SettingsSynonyms.termsFor(Prefs.KEYBOARD_HEIGHT_PCT))
        assertTrue("dark" in SettingsSynonyms.termsFor(Prefs.THEME_MODE))
        assertTrue("margin" in SettingsSynonyms.termsFor(Prefs.SIDE_PAD_DP))
        assertTrue("haptic" in SettingsSynonyms.termsFor(Prefs.VIBRATION))
        assertTrue("split" in SettingsSynonyms.termsFor(Prefs.LAYOUT_MODE))
        assertTrue("chord" in SettingsSynonyms.termsFor(Prefs.CHORD_ARM_MS))
    }

    @Test
    fun anUnknownKeyHasNoTermsRatherThanNoAnswer() {
        assertTrue(SettingsSynonyms.termsFor("pref_does_not_exist").isEmpty())
    }
}
