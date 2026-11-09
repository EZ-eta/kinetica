package com.kinetica.keyboard.keys

import com.kinetica.keyboard.engine.AccentFolder

/**
 * A letter's long-press list follows the enabled languages: each one's own letters ([NativeLetters])
 * are offered on the key they fold to, `ß` on `s` once German is on. Pure, so the rule is testable
 * without a board.
 *
 * A built-in list only gains letters; hiding the foreign ones is the plain-accents setting's job. A
 * list the user wrote also loses a language letter no enabled language writes, so the Default
 * button's accents leave with their language. Symbols, actions, text and the user's order stay.
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
     * A user's [list] for [letter]: a language's letter stays while one of [languages] writes it,
     * and the letters they write join it. At most [cap].
     */
    fun followed(list: List<String>, letter: Char, languages: List<String>, cap: Int): List<String> {
        val wanted = of(letter, languages)
        val any = of(letter, NativeLetters.languages.toList()).toHashSet()
        val kept = list.filter { val l = it.lowercase(); l !in any || l in wanted }
        return withMissing(kept, wanted).take(cap)
    }

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
