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
 * One shortcut set as its settings screen edits it: which actions are on and in what order.
 *
 * Pure, because the screen is a view with no JVM harness and the list arithmetic is the part
 * that can go wrong. Chosen actions are listed first in their order, the rest after them in
 * [ActionRow.ALL]'s order; only chosen ones move.
 */
class ShortcutSet(initialChosen: List<EditorAction>) {

    private val chosen = initialChosen.distinct().toMutableList()

    /** Every action in display order, each with whether it is on. */
    fun rows(): List<Pair<EditorAction, Boolean>> =
        chosen.map { it to true } + ActionRow.ALL.filter { it !in chosen }.map { it to false }

    /** Turns [action] on, at the end of the chosen ones, or off. */
    fun toggle(action: EditorAction) {
        if (!chosen.remove(action)) chosen.add(action)
    }

    /** Moves a chosen [action] one place up (-1) or down (+1); anything else is a no-op. */
    fun move(action: EditorAction, delta: Int) {
        val i = chosen.indexOf(action)
        val j = i + delta
        if (i < 0 || j !in chosen.indices) return
        chosen[i] = chosen[j]
        chosen[j] = action
    }

    fun canMove(action: EditorAction, delta: Int): Boolean {
        val i = chosen.indexOf(action)
        return i >= 0 && (i + delta) in chosen.indices
    }

    /** The selection as the preference stores it. */
    fun selection(): Set<String> = chosen.map { it.name }.toSet()

    /** The order as the preference stores it. */
    fun order(): String = ActionRow.encodeOrder(chosen)

    companion object {
        /** The screen's starting state, from what the two preferences hold. */
        fun from(selection: Set<String>, storedOrder: String?): ShortcutSet =
            ShortcutSet(ActionRow.ordered(selection, ActionRow.decodeOrder(storedOrder)))
    }
}
