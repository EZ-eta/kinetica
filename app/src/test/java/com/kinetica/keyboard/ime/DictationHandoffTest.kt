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

package com.kinetica.keyboard.ime

import com.kinetica.keyboard.keys.EditorAction
import com.kinetica.keyboard.settings.PrefsXml
import com.kinetica.keyboard.settings.SettingsActivity
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The voice key picks a voice keyboard by its subtype and never records here. */
class DictationHandoffTest {

    private val own = "com.kinetica.keyboard"

    private fun ime(pkg: String, vararg modes: String) =
        DictationHandoff.Enabled("$pkg/.Ime", pkg, modes.toList())

    @Test
    fun theFirstDictationSubtypeInTheSystemsOrderIsPicked() {
        val enabled = listOf(
            ime("com.example.latin", "keyboard", "keyboard"),
            ime("org.y20k.voxscribe", "voice"),
            ime("com.example.other", "voice"),
        )
        assertEquals(DictationHandoff.Target("org.y20k.voxscribe/.Ime", 0), DictationHandoff.pick(enabled, own))
    }

    @Test
    fun aKeyboardThatAlsoDictatesIsNoTarget() {
        // Declares only `keyboard`: switching to it would not start listening.
        assertNull(DictationHandoff.pick(listOf(ime("me.trion.whispertype", "keyboard")), own))
    }

    @Test
    fun theDictationSubtypeIsFoundAmongAnInputMethodsOthers() {
        val enabled = listOf(ime("com.example.both", "keyboard", "handwriting", "voice"))
        assertEquals(DictationHandoff.Target("com.example.both/.Ime", 2), DictationHandoff.pick(enabled, own))
    }

    @Test
    fun thisAppIsNeverItsOwnDictationKeyboard() {
        assertNull(DictationHandoff.pick(listOf(ime(own, "voice")), own))
        val dev = "$own.dev"
        assertEquals(
            DictationHandoff.Target("$own/.Ime", 0),
            DictationHandoff.pick(listOf(ime(dev, "voice"), ime(own, "voice")), dev),
        )
    }

    @Test
    fun noneEnabledIsNoTarget() {
        assertNull(DictationHandoff.pick(emptyList(), own))
        assertNull(DictationHandoff.pick(listOf(ime("com.example.x")), own))
    }

    @Test
    fun theDictationKeyRunsTheHandoff() {
        // performLocalAction is Android-only; the one line that routes the action is guarded here.
        val commands = ImeSources.declaring("private fun startDictation()")
        assertTrue(commands.text.contains("EditorAction.DICTATE -> startDictation()"))
        assertTrue(commands.text.contains("DictationHandoff.find(ime)"))
        assertTrue(commands.text.contains("openSettings(DictationHandoff.SCREEN)"))
        assertEquals(EditorAction.DICTATE, EditorAction.of("action:dictate"))
    }

    @Test
    fun theScreenItOpensExistsAndMayBeOpened() {
        assertTrue(DictationHandoff.SCREEN in SettingsActivity.OPENABLE_SCREENS)
        assertTrue(PrefsXml.walk(PrefsXml.read()).second.containsKey(DictationHandoff.SCREEN))
    }

    @Test
    fun theLinkIsTheCheckedListing() {
        assertEquals("https://f-droid.org/packages/org.y20k.voxscribe/", DictationHandoff.SUGGESTED_URL)
        // Its own page, the redirect target of voiceinput.futo.org; it lists every store.
        assertEquals("https://voiceinput.futo.tech/", DictationHandoff.FUTO_URL)
    }

    @Test
    fun theSecondLinkSaysItIsNotOpenSource() {
        val strings = read("app/src/main/res/values/strings.xml")
        val summary = Regex("<string name=\"pref_dictation_get_futo_summary\">([^<]*)</string>").find(strings)
        assertTrue("no summary for the FUTO row", summary != null)
        val text = summary!!.groupValues[1]
        assertTrue(text.contains("not open source"))
        assertTrue(text.contains("network"))
    }

    @Test
    fun theManifestCanSeeInputMethodsAndStillCannotRecord() {
        val manifest = read("app/src/main/AndroidManifest.xml")
        assertTrue(Regex("<queries>\\s*<intent>\\s*<action android:name=\"android.view.InputMethod\" />").containsMatchIn(manifest))
        assertFalse(manifest.contains("RECORD_AUDIO"))
        assertFalse(manifest.contains("android.permission.INTERNET"))
    }

    private fun read(rel: String): String {
        val direct = Paths.get(rel.removePrefix("app/"))
        val p: Path = if (Files.exists(direct)) direct else Paths.get(rel)
        assumeTrue("$rel not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }
}
