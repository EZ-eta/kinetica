package com.kinetica.keyboard.ui

import com.kinetica.keyboard.engine.AccentFolder

/**
 * The bundled emoji set, parsed from `assets/emoji/emoji.tsv` as written by
 * `tools/generate_emoji.py`. Pure, so the parser and the search run against the real assets on
 * the JVM, where `org.json` is a stub.
 */
class EmojiCatalog(val groups: List<Group>) {

    /** [index] is the emoji's line among all emoji in the file, the line of its search words. */
    class Entry(val ch: String, val name: String, val index: Int)

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
                if (drawable(ch)) entries.add(Entry(ch, line.substring(tab + 1), index))
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
