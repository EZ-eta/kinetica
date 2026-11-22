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

import com.kinetica.keyboard.R
import com.kinetica.keyboard.keys.EdgeSwipeBindings
import com.kinetica.keyboard.keys.EditorAction
import com.kinetica.keyboard.keys.KeyCombo

/** What a shortcut does, as an editor's dropdown offers it. */
sealed class ShortcutKind {
    /** Types the field's text. */
    object Text : ShortcutKind()

    /** Holds Ctrl and presses the key typed in the field. */
    object CtrlKey : ShortcutKind()

    /** Opens the emoji picker; edge swipes only. */
    object Emoji : ShortcutKind()

    data class Action(val action: EditorAction) : ShortcutKind()
}

/**
 * The dropdown of the chord, edge-swipe and expansion editors, in one place so the three offer the
 * same kinds, key combinations included.
 */
object ShortcutKinds {

    private val actions = EditorAction.entries.map { ShortcutKind.Action(it) }

    val chords: List<ShortcutKind> = listOf(ShortcutKind.Text, ShortcutKind.CtrlKey) + actions

    val edgeSwipes: List<ShortcutKind> =
        listOf(ShortcutKind.Text, ShortcutKind.CtrlKey, ShortcutKind.Emoji) + actions

    val expansions: List<ShortcutKind> = listOf(ShortcutKind.Text, ShortcutKind.CtrlKey) +
        actions.filter { it.action !in EditorAction.NOT_EXPANSION_TARGETS }

    /** An editing page key's: no emoji picker, and not the page's own action, which would only close it. */
    val editPage: List<ShortcutKind> = listOf(ShortcutKind.Text, ShortcutKind.CtrlKey) +
        actions.filter { it.action != EditorAction.EDIT_PAGE }

    /** Whether [kind] reads the editor's text field. */
    fun usesField(kind: ShortcutKind): Boolean = kind == ShortcutKind.Text || kind == ShortcutKind.CtrlKey

    /** The stored output for [kind] with the field's [text], or null when the field makes none. */
    fun encode(kind: ShortcutKind, text: String): String? = when (kind) {
        ShortcutKind.Text -> text.takeIf { it.isNotEmpty() }
        ShortcutKind.CtrlKey -> KeyCombo.typed(text)?.encode()
        ShortcutKind.Emoji -> EdgeSwipeBindings.ACTION_EMOJI
        is ShortcutKind.Action -> kind.action.output
    }

    /** The kind and field text an editor shows for a stored [output]; null is a new shortcut. */
    fun decode(output: String?): Pair<ShortcutKind, String> {
        if (output == null) return ShortcutKind.Text to ""
        KeyCombo.parse(output)?.let { return ShortcutKind.CtrlKey to KeyCombo.typedOf(it) }
        if (output == EdgeSwipeBindings.ACTION_EMOJI) return ShortcutKind.Emoji to ""
        EditorAction.of(output)?.let { return ShortcutKind.Action(it) to "" }
        return ShortcutKind.Text to output
    }

    /** The name the dropdown shows for [kind]. */
    fun labelRes(kind: ShortcutKind): Int = when (kind) {
        ShortcutKind.Text -> R.string.chord_kind_text
        ShortcutKind.CtrlKey -> R.string.chord_kind_combo
        ShortcutKind.Emoji -> R.string.edge_swipe_action_emoji
        is ShortcutKind.Action -> ActionLabels.labelRes(kind.action)
    }

    /** The field's hint for [kind]: text to type, or the key to press with Ctrl. */
    fun hintRes(kind: ShortcutKind, textHint: Int): Int =
        if (kind == ShortcutKind.CtrlKey) R.string.combo_key_hint else textHint
}
