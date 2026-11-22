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

package com.kinetica.keyboard.ui

import com.kinetica.keyboard.keys.ActionRow
import com.kinetica.keyboard.keys.ChordLabels
import com.kinetica.keyboard.keys.EditorAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The action icons: one per action but the few whose symbol reads better, found from the symbol
 * a cell holds, tinted at draw time, and shipped with their licence. The symbol stays the
 * action's identity, so a list's text stays text.
 */
class GlyphIconsTest {

    private fun file(rel: String): Path? =
        listOf(Paths.get(rel), Paths.get("app/$rel")).firstOrNull { Files.exists(it) }

    @Test
    fun everyActionHasAnIconButTheOnesKeptAsText() {
        val kept = setOf(EditorAction.ESCAPE, EditorAction.CASE_LOWER, EditorAction.CASE_TITLE, EditorAction.CASE_UPPER)
        for (a in EditorAction.entries) {
            if (a in kept) assertNull("$a keeps its symbol", GlyphIcons.resOf(a))
            else assertNotNull("$a has no icon", GlyphIcons.resOf(a))
        }
        val ids = EditorAction.entries.mapNotNull { GlyphIcons.resOf(it) }
        assertEquals("two actions share an icon", ids.size, ids.toSet().size)
    }

    @Test
    fun aBarOrMenuCellFindsItsActionBySymbol() {
        for (a in EditorAction.entries) {
            assertEquals(a, GlyphIcons.actionOfSymbol(ActionRow.glyph(a)))
            assertEquals(a, GlyphIcons.actionOfSymbol(ActionRow.glyph(a) + ActionRow.ACTION_MARK))
        }
        assertNull(GlyphIcons.actionOfSymbol("x"))
        assertNull(GlyphIcons.actionOfSymbol(""))
    }

    @Test
    fun aListsTextKeepsItsSymbolAndAChordsActionGetsItsIcon() {
        // In a letter's list `→` and `ALL` are text: drawn as an icon they would promise an action.
        assertNull(ActionRow.actionForGlyph("→"))
        assertNull(ActionRow.actionForGlyph("ALL"))
        // A chord bound to the action carries the mark, so its key draws the icon.
        for (a in listOf(EditorAction.ARROW_LEFT, EditorAction.SELECT_ALL, EditorAction.PASTE, EditorAction.DICTATE)) {
            assertEquals(a, ActionRow.actionForGlyph(ChordLabels.of(a.output)))
        }
        // A chord typing text stays text.
        assertNull(ActionRow.actionForGlyph(ChordLabels.of("ALL")))
    }

    @Test
    fun everyIconIsTintedWhenDrawnAndNoneIsLeftOver() {
        val dir = file("src/main/res/drawable")
        assumeTrue("res/drawable not found", dir != null)
        val icons = Files.list(dir!!).use { s -> s.filter { it.fileName.toString().startsWith("ic_action_") }.toList() }
        val src = String(Files.readAllBytes(file("src/main/java/com/kinetica/keyboard/ui/GlyphIcons.kt")!!))
        for (f in icons) {
            val xml = String(Files.readAllBytes(f))
            val name = f.fileName.toString().removeSuffix(".xml")
            assertFalse("$name resolves a theme attribute the keyboard's theme may lack", xml.contains("?attr"))
            assertFalse("$name carries its own tint", xml.contains("android:tint"))
            assertFalse("$name mirrors in a right-to-left language", xml.contains("autoMirrored"))
            assertTrue("$name is drawn by no action", src.contains("R.drawable.$name\n"))
        }
        assertEquals(EditorAction.entries.mapNotNull { GlyphIcons.resOf(it) }.size, icons.size)
    }

    @Test
    fun theIconsLicenceShipsWithTheApp() {
        val f = file("../THIRD_PARTY_NOTICES") ?: file("THIRD_PARTY_NOTICES")
        assumeTrue("THIRD_PARTY_NOTICES not found", f != null)
        val notices = String(Files.readAllBytes(f!!))
        assertTrue(notices.contains("Material Symbols (Outlined)"))
        assertTrue(notices.contains("res/drawable/ic_action_*.xml"))
        // Apache-2.0 asks that the licence itself go with the icons.
        assertTrue(notices.contains("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION"))
    }
}
