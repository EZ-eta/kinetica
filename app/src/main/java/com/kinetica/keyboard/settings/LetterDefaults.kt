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
 * What a letter's Default button fills in: every enabled language's built-in list for that letter.
 * A trimmed list would otherwise lose the accents it dropped for good; this brings them all back to
 * trim again.
 */
object LetterDefaults {

    /** The lists' entries in order, earlier lists first, each entry once, at most [cap]. */
    fun union(lists: List<List<String>>, cap: Int): List<String> {
        val out = LinkedHashSet<String>()
        for (list in lists) {
            for (entry in list) {
                if (out.size >= cap) return out.toList()
                out.add(entry)
            }
        }
        return out.toList()
    }

    /** Per letter, the [union] of [perLanguage] (the active language's first). */
    fun unionPerLetter(perLanguage: List<Map<Char, List<String>>>, cap: Int): Map<Char, List<String>> {
        val letters = LinkedHashSet<Char>()
        for (m in perLanguage) letters.addAll(m.keys)
        return letters.associateWith { c -> union(perLanguage.map { it[c].orEmpty() }, cap) }
    }
}
