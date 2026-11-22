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
 * A colour typed as `#RRGGBB`. Spaces are dropped and the `#` is optional, since both are easy to
 * miss on a phone; anything else is refused, not guessed at.
 *
 * Hand-rolled: `android.graphics.Color.parseColor` has no JVM implementation, so a palette built
 * on it could not be tested.
 */
object HexColor {

    sealed class Result {
        object Blank : Result()

        /** Opaque ARGB. */
        data class Valid(val color: Int) : Result()

        data class Invalid(val typed: String) : Result()
    }

    fun parse(raw: CharSequence?): Result {
        val s = raw?.filterNot { it.isWhitespace() }?.toString().orEmpty()
        if (s.isEmpty()) return Result.Blank
        val digits = s.removePrefix("#")
        if (digits.length != 6 || digits.any { Character.digit(it, 16) < 0 }) return Result.Invalid(s)
        return Result.Valid(digits.toInt(16) or OPAQUE)
    }

    /** The colour of [raw], or [fallback] when it is not one. */
    fun colorOr(raw: String?, fallback: Int): Int = (parse(raw) as? Result.Valid)?.color ?: fallback

    /** `#RRGGBB`, upper case; alpha dropped. */
    fun format(color: Int): String = "#%06X".format(color and 0xFFFFFF)

    private const val OPAQUE = 0xFF shl 24
}
