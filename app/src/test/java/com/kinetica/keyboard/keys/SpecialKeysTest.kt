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

package com.kinetica.keyboard.keys

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tab and the other keys the keyboard has no key for, offered as actions. */
class SpecialKeysTest {

    private val special = listOf(
        EditorAction.TAB, EditorAction.ESCAPE, EditorAction.FORWARD_DELETE, EditorAction.HOME,
        EditorAction.END, EditorAction.ARROW_UP, EditorAction.ARROW_DOWN, EditorAction.ARROW_LEFT,
        EditorAction.ARROW_RIGHT, EditorAction.PAGE_UP, EditorAction.PAGE_DOWN,
    )

    @Test
    fun undoAndRedoGoOutAsTheKeysAHardwareKeyboardSends() {
        // The menu call is a silent no-op in an editor with no menu undo, and WebView never answers it.
        assertEquals("combo:ctrl+z", SpecialKeys.comboFor(EditorAction.UNDO)?.encode())
        assertEquals("combo:ctrl+shift+z", SpecialKeys.comboFor(EditorAction.REDO)?.encode())
        for (a in EditorAction.entries.filter { it != EditorAction.UNDO && it != EditorAction.REDO }) {
            assertNull(a.name, SpecialKeys.comboFor(a))
        }
    }

    @Test
    fun eachSpecialKeySendsItsOwnKeyCode() {
        assertEquals(KeyEvent.KEYCODE_TAB, SpecialKeys.keyCodeFor(EditorAction.TAB))
        assertEquals(KeyEvent.KEYCODE_ESCAPE, SpecialKeys.keyCodeFor(EditorAction.ESCAPE))
        assertEquals(KeyEvent.KEYCODE_FORWARD_DEL, SpecialKeys.keyCodeFor(EditorAction.FORWARD_DELETE))
        assertEquals(KeyEvent.KEYCODE_MOVE_HOME, SpecialKeys.keyCodeFor(EditorAction.HOME))
        assertEquals(KeyEvent.KEYCODE_MOVE_END, SpecialKeys.keyCodeFor(EditorAction.END))
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, SpecialKeys.keyCodeFor(EditorAction.ARROW_UP))
        assertEquals(KeyEvent.KEYCODE_DPAD_DOWN, SpecialKeys.keyCodeFor(EditorAction.ARROW_DOWN))
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT, SpecialKeys.keyCodeFor(EditorAction.ARROW_LEFT))
        assertEquals(KeyEvent.KEYCODE_DPAD_RIGHT, SpecialKeys.keyCodeFor(EditorAction.ARROW_RIGHT))
        assertEquals(KeyEvent.KEYCODE_PAGE_UP, SpecialKeys.keyCodeFor(EditorAction.PAGE_UP))
        assertEquals(KeyEvent.KEYCODE_PAGE_DOWN, SpecialKeys.keyCodeFor(EditorAction.PAGE_DOWN))
        assertEquals(special.size, special.map { SpecialKeys.keyCodeFor(it) }.toSet().size)
    }

    @Test
    fun noOtherActionIsAKeyEvent() {
        for (a in EditorAction.entries) if (a !in special) assertNull(a.name, SpecialKeys.keyCodeFor(a))
    }

    @Test
    fun theyReachEveryShortcutSurface() {
        // The chord, edge-swipe and bar choosers all list ActionRow.ALL or the enum; a name in
        // a letter list resolves too. An arrow carries the mark, so `←` typed as text stays text.
        for (a in special) {
            assertTrue(a.name, a in ActionRow.ALL)
            assertEquals(a, EditorAction.of(a.output))
            assertTrue(a.name, a !in EditorAction.NOT_EXPANSION_TARGETS)
        }
        assertEquals("⇥", ActionRow.glyphForToken(":tab"))
        assertEquals("⎋", ActionRow.glyphForToken(":escape"))
        assertEquals("←${ActionRow.ACTION_MARK}", ActionRow.glyphForToken(":arrow_left"))
        assertEquals(EditorAction.ARROW_LEFT, ActionRow.actionForGlyph("←${ActionRow.ACTION_MARK}"))
        assertNull(ActionRow.actionForGlyph("←"))
        assertEquals(ActionRow.Notice.Sent(EditorAction.TAB), ActionRow.notice(EditorAction.TAB, ActionRow.KeyboardState(true, listOf("en"), "en", "full", null)))
    }
}
