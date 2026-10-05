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
