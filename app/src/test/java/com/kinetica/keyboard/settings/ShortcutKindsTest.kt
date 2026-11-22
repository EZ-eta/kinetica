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

import com.kinetica.keyboard.keys.EdgeSwipeBindings
import com.kinetica.keyboard.keys.EditorAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The one dropdown shared by the chord, edge-swipe and expansion editors. */
class ShortcutKindsTest {

    @Test
    fun anEditingPageKeyOffersNoEmojiAndNotThePageItself() {
        val kinds = ShortcutKinds.editPage
        assertEquals(listOf(ShortcutKind.Text, ShortcutKind.CtrlKey), kinds.take(2))
        assertFalse(ShortcutKind.Emoji in kinds)
        assertFalse(ShortcutKind.Action(EditorAction.EDIT_PAGE) in kinds)
        assertTrue(ShortcutKind.Action(EditorAction.SELECT_MODE) in kinds)
        assertTrue(ShortcutKind.Action(EditorAction.ARROW_LEFT) in kinds)
    }

    @Test
    fun everyKindReadsBackFromWhatItStores() {
        for (kinds in listOf(ShortcutKinds.chords, ShortcutKinds.edgeSwipes, ShortcutKinds.expansions)) {
            for (kind in kinds) {
                val field = when (kind) {
                    ShortcutKind.Text -> "hello"
                    ShortcutKind.CtrlKey -> "Del"
                    else -> ""
                }
                val stored = ShortcutKinds.encode(kind, field)!!
                assertEquals(kind to field, ShortcutKinds.decode(stored))
            }
        }
    }

    @Test
    fun ctrlPlusKeyStoresTheCombinationTheFieldNames() {
        assertEquals("combo:ctrl+c", ShortcutKinds.encode(ShortcutKind.CtrlKey, "c"))
        assertEquals("combo:ctrl+delete", ShortcutKinds.encode(ShortcutKind.CtrlKey, "DEL"))
        assertNull(ShortcutKinds.encode(ShortcutKind.CtrlKey, "ab"))
        assertNull(ShortcutKinds.encode(ShortcutKind.Text, ""))
        assertEquals(ShortcutKind.CtrlKey to "Shift+Left", ShortcutKinds.decode("combo:ctrl+shift+left"))
    }

    @Test
    fun eachEditorOffersWhatItCanRun() {
        assertTrue(ShortcutKind.CtrlKey in ShortcutKinds.expansions)
        assertTrue(ShortcutKind.Emoji in ShortcutKinds.edgeSwipes)
        assertFalse(ShortcutKind.Emoji in ShortcutKinds.chords)
        for (a in EditorAction.NOT_EXPANSION_TARGETS) assertFalse(ShortcutKind.Action(a) in ShortcutKinds.expansions)
        for (a in EditorAction.entries) assertTrue(ShortcutKind.Action(a) in ShortcutKinds.chords)
        assertEquals(EdgeSwipeBindings.ACTION_EMOJI, ShortcutKinds.encode(ShortcutKind.Emoji, ""))
    }

    private fun strings(): Map<String, String> {
        val direct = Paths.get("src/main/res/values/strings.xml")
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/src/main/res/values/strings.xml")
        assumeTrue("strings.xml not found", Files.exists(p))
        val xml = Files.newBufferedReader(p).use { it.readText() }
        return Regex("""<string name="([^"]+)"[^>]*>([^<]*)</string>""").findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun everyChoiceIsAShortName() {
        // Short names ("Up arrow", "Page up", "DEL"), not sentences ("Presses the up arrow").
        val byId = com.kinetica.keyboard.R.string::class.java.fields.associate { it.getInt(null) to it.name }
        val text = strings()
        val sentences = Regex("""^(Presses|Holds|Turns|Opens|Writes|Switches|Moves|Deletes|Copies|Cuts|Selects|Retypes|Expands|Pastes|Types|Inserts)\b""")
        for (kind in ShortcutKinds.edgeSwipes + ShortcutKinds.chords) {
            val label = text.getValue(byId.getValue(ShortcutKinds.labelRes(kind)))
            assertFalse("$kind reads as a sentence: $label", sentences.containsMatchIn(label))
            assertTrue("$kind is long: $label", label.length <= 24)
        }
    }
}
