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
 * What a key shows of its chord while the trigger is held: the action's symbol, a key
 * combination in symbols, or the start of the text.
 *
 * A key cap holds three or four characters before its label stops reading, so text is cut at
 * [MAX_TEXT] with an ellipsis, and a combination is symbols only, as on Apple-style keyboards:
 * `Ctrl+Delete` would spill past the key, `⌃⌦` fits.
 */
object ChordLabels {

    /** Characters of an expansion's text shown before the ellipsis. */
    const val MAX_TEXT = 3

    fun of(expansion: String): String {
        // Marked when the symbol is also text (`ALL`, `→`), so the key draws the action's icon.
        EditorAction.of(expansion)?.let { return ActionRow.glyphForToken(expansion) ?: ActionRow.glyph(it) }
        KeyCombo.parse(expansion)?.let { return combination(it) }
        if (expansion.length <= MAX_TEXT + 1) return expansion
        return expansion.take(MAX_TEXT) + "…"
    }

    /** The modifiers, then the key, with no `+`: `⌃⇧←`. Settings keep [KeyCombo.label]. */
    private fun combination(combo: KeyCombo): String =
        (if (combo.ctrl) "⌃" else "") + (if (combo.shift) "⇧" else "") + (if (combo.alt) "⌥" else "") +
            (KEY_GLYPHS[combo.key] ?: combo.key.uppercase())

    /**
     * A combination's key as the action row draws the action that presses it, so `⌦` means the
     * same on a key and in the bar. Backspace, Enter and Space press no special-key action.
     */
    private val KEY_GLYPHS: Map<String, String> =
        EditorAction.entries.mapNotNull { action -> SpecialKeys.comboKeyFor(action)?.let { it to ActionRow.glyph(action) } }
            .toMap() +
            mapOf(
                "backspace" to ActionRow.glyph(EditorAction.BACKSPACE),
                "enter" to ActionRow.glyph(EditorAction.ENTER),
                "space" to "␣",
            )
}
