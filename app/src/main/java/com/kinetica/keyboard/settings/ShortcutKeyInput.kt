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

/**
 * The key a shortcut goes on, typed instead of picked from a list, so any key of any board works:
 * `й`, `1` or `,` alike. Spaces are dropped, since a typed space is never the key meant; more than
 * one character is refused, not guessed at.
 */
object ShortcutKeyInput {

    sealed class Result {
        object Blank : Result()

        /** A letter is its lowercase: a shifted board types the same chord. */
        data class One(val key: Char) : Result()

        data class TooMany(val typed: String) : Result()
    }

    fun parse(raw: CharSequence?): Result {
        val s = raw?.filterNot { it.isWhitespace() }?.toString().orEmpty()
        return when (s.length) {
            0 -> Result.Blank
            1 -> Result.One(s[0].lowercaseChar())
            else -> Result.TooMany(s)
        }
    }
}
