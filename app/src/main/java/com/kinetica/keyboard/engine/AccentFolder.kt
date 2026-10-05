package com.kinetica.keyboard.engine

/**
 * Maps accented letters onto the trie alphabet's base letters. Gesture geometry knows only base
 * keys (an Italian user swipes the same path for "perche" and "perché"), so accented words are
 * stored under their folded key and come back as display variants at emit time (see
 * [LoadedDictionary.forms]).
 */
object AccentFolder {

    /**
     * Letters that fold to two letters. Kept apart from [FOLD] because a two-letter fold is not a
     * key, so [accentedLetterCode] never returns one and a long-press popup cannot insert one
     * mid-word.
     */
    private val DIGRAPHS = mapOf('ß' to "ss", 'œ' to "oe")

    private val FOLD = HashMap<Char, Char>().apply {
        "àáâäãåąæ".forEach { put(it, 'a') }
        "èéêëęě".forEach { put(it, 'e') }
        "ìíîï".forEach { put(it, 'i') }
        "òóôöõø".forEach { put(it, 'o') }
        "ùúûüů".forEach { put(it, 'u') }
        put('ç', 'c'); put('ć', 'c'); put('č', 'c')
        put('ď', 'd')
        put('ñ', 'n'); put('ń', 'n'); put('ň', 'n')
        put('ý', 'y'); put('ÿ', 'y')
        put('ł', 'l')
        put('ř', 'r')
        put('ś', 's'); put('š', 's')
        put('ť', 't')
        put('ž', 'z'); put('ź', 'z'); put('ż', 'z')
        // Russian writes ё on е's key and most text writes е for it.
        put('ё', 'е')
        // Ukrainian writes ґ on г's key, and its apostrophe often as the modifier letter.
        put('ґ', 'г')
        put('\u02BC', '\'')
        // Arabic: the alef-hamza forms and wasla sit on alef's key.
        "أإآٱ".forEach { put(it, 'ا') }
    }

    /**
     * Marks a folded key never carries: Arabic tatweel and short vowels. Text almost always omits
     * them, and a key cannot be drawn for a mark.
     */
    private val DROPPED: Set<Char> = HashSet<Char>().apply {
        add('\u0640')
        for (c in '\u064B'..'\u0652') add(c)
        add('\u0670')
    }

    /**
     * Letter code of [text] when it is a single accented letter of this alphabet, else -1.
     *
     * An accented letter from a long-press popup continues the word; a digit, symbol or emoji
     * from the same popup ends it. Only one character folding to one different letter qualifies,
     * so the popup's base cell ("o" under "ó") still commits and then inserts, and "ß" and "œ"
     * never match.
     *
     * Pure so the composing decision is JVM-testable, unlike the buffer code in KineticaIME.
     */
    fun accentedLetterCode(text: String, alphabet: Alphabet = Alphabet.LATIN): Int {
        if (text.length != 1) return -1
        val lower = text[0].lowercaseChar()
        val folded = FOLD[lower] ?: return -1
        return alphabet.codeOf(folded)
    }

    /**
     * Folded form of [word]; returns the same instance when nothing folds.
     *
     * Case folds too: the trie alphabet has no capitals, so "Haus" would encode to null and
     * [Trie.build] would drop it silently. A wordlist can then carry a capitalized display form on
     * a lowercase key, and German nouns use the same forms mechanism as "perché".
     */
    fun fold(word: String): String {
        var needsFold = false
        for (ch in word) {
            if (ch in DIGRAPHS || FOLD.containsKey(ch) || ch.isUpperCase() || ch in DROPPED) {
                needsFold = true
                break
            }
        }
        if (!needsFold) return word
        val sb = StringBuilder(word.length + 1)
        for (ch in word) {
            // Lowercase first, so the maps need only lowercase keys and "Ä" folds as "ä" does.
            val lower = ch.lowercaseChar()
            if (lower in DROPPED) continue
            val digraph = DIGRAPHS[lower]
            if (digraph != null) sb.append(digraph) else sb.append(FOLD[lower] ?: lower)
        }
        return sb.toString()
    }
}
