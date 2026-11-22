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

import com.kinetica.keyboard.engine.AccentFolder

/**
 * The bundled emoji set, parsed from `assets/emoji/emoji.tsv` as written by
 * `tools/generate_emoji.py`. Pure, so the parser and the search run against the real assets on
 * the JVM, where `org.json` is a stub.
 */
class EmojiCatalog(val groups: List<Group>) {

    /**
     * [index] is the emoji's line among all emoji in the file, the line of its search words.
     * [toned] is its light skin tone form, null when it takes no tone ([EmojiTone]).
     */
    class Entry(val ch: String, val name: String, val index: Int, val toned: String? = null)

    class Group(val name: String, val icon: String, val entries: List<Entry>)

    /**
     * One emoji's search words in one language, folded: [hand] written for this project,
     * [name] from the CLDR name, [other] the rest of the CLDR keywords.
     */
    class Words(val hand: List<String>, val name: List<String>, val other: List<String>)

    val entries: List<Entry> = groups.flatMap { it.entries }

    companion object {
        private const val GROUP_MARK = '@'

        /**
         * [drawable] decides per emoji and per tab icon, so a set newer than the phone's font
         * loses what it cannot draw and a group left empty loses its tab.
         */
        fun parse(lines: Sequence<String>, drawable: (String) -> Boolean = { true }): EmojiCatalog {
            val groups = ArrayList<Group>()
            var name = ""
            var icon = ""
            var entries = ArrayList<Entry>()
            var index = 0
            fun close() {
                if (entries.isNotEmpty() && drawable(icon)) groups.add(Group(name, icon, entries))
                entries = ArrayList()
            }
            for (line in lines) {
                if (line.isEmpty()) continue
                val tab = line.indexOf('\t')
                if (tab < 0) continue
                if (line[0] == GROUP_MARK) {
                    close()
                    name = line.substring(1, tab)
                    icon = line.substring(tab + 1)
                    continue
                }
                val ch = line.substring(0, tab)
                val toneTab = line.indexOf('\t', tab + 1)
                val name = if (toneTab < 0) line.substring(tab + 1) else line.substring(tab + 1, toneTab)
                val toned = if (toneTab < 0) null else line.substring(toneTab + 1).ifEmpty { null }
                if (drawable(ch)) entries.add(Entry(ch, name, index, toned))
                index++
            }
            close()
            return EmojiCatalog(groups)
        }

        /** One [Words] per emoji line of `kw_<lang>.tsv`, folded once here, not per keystroke. */
        fun parseWords(lines: Sequence<String>): List<Words> =
            lines.filter { it.isNotEmpty() }.map { line ->
                val f = line.split('\t')
                Words(fields(f, 0), fields(f, 1), fields(f, 2))
            }.toList()

        private fun fields(f: List<String>, i: Int): List<String> {
            val s = f.getOrNull(i) ?: return emptyList()
            if (s.isEmpty()) return emptyList()
            return s.split(' ').filter { it.isNotEmpty() }.map { AccentFolder.fold(it.lowercase()) }
        }
    }
}
