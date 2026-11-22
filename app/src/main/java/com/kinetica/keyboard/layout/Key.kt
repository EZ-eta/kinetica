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

package com.kinetica.keyboard.layout

import com.kinetica.keyboard.engine.Alphabet

enum class KeyType {
    CHAR, SHIFT, BACKSPACE, ENTER, SPACE,
    MODE_SYMBOLS, MODE_SYMBOLS2, MODE_ALPHA, MODE_NUMPAD, EMOJI;

    companion object {
        fun fromJson(s: String): KeyType = when (s) {
            "char" -> CHAR
            "shift" -> SHIFT
            "backspace" -> BACKSPACE
            "enter" -> ENTER
            "space" -> SPACE
            "mode_symbols" -> MODE_SYMBOLS
            "mode_symbols2" -> MODE_SYMBOLS2
            "mode_alpha" -> MODE_ALPHA
            "mode_numpad" -> MODE_NUMPAD
            "emoji" -> EMOJI
            else -> throw IllegalArgumentException("key type $s is not one the board draws")
        }
    }
}

/**
 * One key definition with normalized (0..1) geometry over the keyboard area.
 * Pixel rects are computed per layout mode at measure time; this class stays
 * immutable across size changes.
 */
data class Key(
    val id: String,
    val type: KeyType,
    val label: String,
    val output: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val hint: String? = null,
    /** Long-press choices; the first entry is the plain-long-press default. */
    val alternates: List<String> = emptyList(),
    /**
     * Drawn as a bare label, with no background, border or press highlight; the hit target
     * is unchanged. Used for the optional apostrophe key, Nintype-style.
     */
    val chromeless: Boolean = false,
    /**
     * The letters of the board this key belongs to. A key is a letter only in its board's own
     * alphabet, so `π` on a symbol page stays a symbol and a Latin letter key is a-z.
     */
    val alphabet: Alphabet = Alphabet.LATIN,
) {
    val isLetter: Boolean =
        type == KeyType.CHAR && output.length == 1 && alphabet.isLetter(output[0])

    /** The letter's code in [alphabet], or -1 for anything that is not a letter key. */
    val letterCode: Int = if (isLetter) alphabet.codeOf(output[0]) else -1

    /**
     * The character a chord is keyed by, or null for a key that types none of its own: any
     * single-character key of any board, `й`, `1` and `,` alike. A letter is its lowercase, so
     * a shifted board finds the same chord.
     */
    val chordChar: Char? = if (type == KeyType.CHAR && output.length == 1) output[0].lowercaseChar() else null

    /** Character painted small in the top-right corner of the key. */
    val hintChar: String? = hint ?: alternates.firstOrNull()
}
