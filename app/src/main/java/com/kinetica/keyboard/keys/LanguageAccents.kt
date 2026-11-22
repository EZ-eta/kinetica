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

import com.kinetica.keyboard.engine.AccentFolder

/**
 * A letter's long-press list follows the enabled languages: each one's own letters ([NativeLetters])
 * are offered on the key they fold to, `ß` on `s` once German is on. Pure, so the rule is testable
 * without a board.
 *
 * A built-in list only gains letters; hiding the foreign ones is the plain-accents setting's job. A
 * list the user wrote is never touched: its Default button already holds every enabled language.
 */
object LanguageAccents {

    /** The letters [languages] write that fold onto [letter], in language order, each once. */
    fun of(letter: Char, languages: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (lang in languages) {
            for (c in NativeLetters.of(lang)) {
                val s = c.toString()
                if (AccentFolder.fold(s).firstOrNull() == letter) out.add(s)
            }
        }
        return out.toList()
    }

    /** A built-in [list] for [letter], with the letters [languages] write that it lacks. */
    fun added(list: List<String>, letter: Char, languages: List<String>): List<String> =
        withMissing(list, of(letter, languages))

    /**
     * [list] with the [letters] it lacks after its last letter, or at its end with none: the first
     * cell, the plain long-press and the corner hint, stays where it was.
     */
    private fun withMissing(list: List<String>, letters: List<String>): List<String> {
        val have = list.mapTo(HashSet()) { it.lowercase() }
        val missing = letters.filter { it !in have }
        if (missing.isEmpty()) return list
        val at = list.indexOfLast(::isLetterCell) + 1
        val out = ArrayList<String>(list.size + missing.size)
        out.addAll(list.subList(0, if (at == 0) list.size else at))
        out.addAll(missing)
        if (at != 0) out.addAll(list.subList(at, list.size))
        return out
    }

    private fun isLetterCell(cell: String): Boolean =
        cell.firstOrNull()?.isLetter() == true && cell.last() != ActionRow.ACTION_MARK
}
