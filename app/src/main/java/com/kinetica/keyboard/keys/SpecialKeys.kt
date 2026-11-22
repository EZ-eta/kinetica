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

/**
 * The key event each special-key action sends. A key event, not text, so the editor decides what
 * Tab, Home or an arrow means in it, as it does for a hardware keyboard.
 */
object SpecialKeys {

    /** The key code [action] sends, or null when it is not a special key. */
    fun keyCodeFor(action: EditorAction): Int? = when (action) {
        EditorAction.TAB -> KeyEvent.KEYCODE_TAB
        EditorAction.ESCAPE -> KeyEvent.KEYCODE_ESCAPE
        EditorAction.FORWARD_DELETE -> KeyEvent.KEYCODE_FORWARD_DEL
        EditorAction.HOME -> KeyEvent.KEYCODE_MOVE_HOME
        EditorAction.END -> KeyEvent.KEYCODE_MOVE_END
        EditorAction.ARROW_UP -> KeyEvent.KEYCODE_DPAD_UP
        EditorAction.ARROW_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        EditorAction.ARROW_LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        EditorAction.ARROW_RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        EditorAction.PAGE_UP -> KeyEvent.KEYCODE_PAGE_UP
        EditorAction.PAGE_DOWN -> KeyEvent.KEYCODE_PAGE_DOWN
        else -> null
    }

    /**
     * The combination [action] goes out as, in place of a context-menu call. A TextView answers
     * Ctrl+Z with the undo the menu call reaches; WebView answers the key alone, its menu call
     * covering only select all and the clipboard.
     */
    fun comboFor(action: EditorAction): KeyCombo? = when (action) {
        EditorAction.UNDO -> KeyCombo(ctrl = true, shift = false, alt = false, key = "z")
        EditorAction.REDO -> KeyCombo(ctrl = true, shift = true, alt = false, key = "z")
        else -> null
    }

    /** The key a special-key action names in a combination, for the one-shot Ctrl. */
    fun comboKeyFor(action: EditorAction): String? = when (action) {
        EditorAction.TAB -> "tab"
        EditorAction.ESCAPE -> "escape"
        EditorAction.FORWARD_DELETE -> "delete"
        EditorAction.HOME -> "home"
        EditorAction.END -> "end"
        EditorAction.ARROW_UP -> "up"
        EditorAction.ARROW_DOWN -> "down"
        EditorAction.ARROW_LEFT -> "left"
        EditorAction.ARROW_RIGHT -> "right"
        EditorAction.PAGE_UP -> "pageup"
        EditorAction.PAGE_DOWN -> "pagedown"
        else -> null
    }
}
