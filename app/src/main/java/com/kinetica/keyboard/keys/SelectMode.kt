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

/**
 * Select on the editing page: while it is on, a move goes out with Shift held, so it extends the
 * selection from the cursor, as Shift and an arrow do on a hardware keyboard. A toggle, not a held
 * key, because one thumb holds the phone.
 */
object SelectMode {

    /** [output] as sent while Select is on: a move gains Shift, anything else is unchanged. */
    fun withShift(output: String): String {
        EditorAction.of(output)?.let { action ->
            val key = SpecialKeys.comboKeyFor(action)?.takeIf { action in MOVES } ?: return output
            return KeyCombo(ctrl = false, shift = true, alt = false, key = key).encode()
        }
        val combo = KeyCombo.parse(output) ?: return output
        return if (combo.key in MOVE_KEYS) combo.copy(shift = true).encode() else output
    }

    /**
     * Whether Select ends after [action]: what the selection was for is done, and the next move
     * should move the cursor, not grow a selection that is gone.
     */
    fun clearsAfter(action: EditorAction): Boolean =
        action == EditorAction.COPY || action == EditorAction.CUT || action == EditorAction.PASTE

    private val MOVES = setOf(
        EditorAction.ARROW_LEFT, EditorAction.ARROW_RIGHT, EditorAction.ARROW_UP, EditorAction.ARROW_DOWN,
        EditorAction.HOME, EditorAction.END, EditorAction.PAGE_UP, EditorAction.PAGE_DOWN,
    )

    private val MOVE_KEYS = setOf("left", "right", "up", "down", "home", "end", "pageup", "pagedown")
}
