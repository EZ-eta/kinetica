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

/**
 * The skin tone set for emoji that take one: 0 none (the yellow default), 1-5 light to dark, the
 * Fitzpatrick modifiers U+1F3FB-U+1F3FF. The catalog carries each such emoji's light form, so a tone
 * is one modifier swapped for another, and only where Unicode lists every tone.
 */
object EmojiTone {

    const val NONE = 0
    const val DARKEST = 5

    private const val LIGHT = 0x1F3FB

    /** The stored names, in tone order, so a value reads as what it is in a backup. */
    val NAMES = listOf("none", "light", "medium_light", "medium", "medium_dark", "dark")

    /** The tone a stored [name] is; anything unknown is [NONE]. */
    fun of(name: String?): Int = NAMES.indexOf(name).coerceAtLeast(NONE)

    /** What [entry] shows and types in [tone]: its toned form, or itself when it takes none. */
    fun apply(entry: EmojiCatalog.Entry, tone: Int): String {
        val light = entry.toned ?: return entry.ch
        if (tone !in 1..DARKEST) return entry.ch
        return light.replace(String(Character.toChars(LIGHT)), String(Character.toChars(LIGHT + tone - 1)))
    }
}
