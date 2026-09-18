package com.kinetica.keyboard.engine

/**
 * Maps accented Latin letters onto the 27-symbol trie alphabet (a-z plus
 * apostrophe). Gesture geometry only knows base keys - an Italian user swipes
 * the same path for "perche" and "perché" - so accented dictionary words are
 * stored under their folded key and resurface as display variants at emit
 * time (see [LoadedDictionary.forms]).
 */
object AccentFolder {

    /**
     * Letters that fold to two a-z letters rather than one. Kept apart from
     * [FOLD] because a two-letter fold is not a key, so these are excluded from
     * [accentedLetterCode] by construction and cannot be inserted mid-word from
     * a long-press popup.
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
    }

    /**
     * Letter code of [text] when it is a single ACCENTED letter of this
     * alphabet, else -1.
     *
     * Exists because an accented letter inserted from a long-press popup is part
     * of the word being written, while every other thing that popup can insert -
     * a digit, a symbol, an emoji - ends it. The predicate is deliberately
     * narrow: one character, folding to exactly one a-z letter, and actually
     * different from it. So the popup's own base cell ("o" under "ó") keeps the
     * shipped commit-then-insert behaviour, and the digraphs "ß" and "œ" (which
     * fold to two letters, i.e. not a key) are excluded by construction.
     *
     * Pure and Android-free so the composing decision is JVM-testable, unlike
     * the buffer plumbing in KineticaIME that acts on it.
     */
    fun accentedLetterCode(text: String): Int {
        if (text.length != 1) return -1
        val lower = text[0].lowercaseChar()
        val folded = FOLD[lower] ?: return -1
        return Alphabet.codeOf(folded)
    }

    /**
     * Folded form of [word]; returns the same instance when nothing folds.
     *
     * Case folds too, because the trie alphabet has no capitals:
     * [Alphabet.codeOf] admits only a-z and an apostrophe, so an entry written
     * "Haus" would encode to null and [Trie.build] would drop it with no error
     * at all. Folding case here is what lets a wordlist carry a capitalized
     * DISPLAY form on a lowercase key, which is how German noun capitalization
     * rides the same forms mechanism that turns "perche" into "perché".
     *
     * Inert for every language whose asset is already lowercase, which was all
     * of them before German: an all-lowercase unaccented word still returns the
     * same instance it was given.
     */
    fun fold(word: String): String {
        var needsFold = false
        for (ch in word) {
            if (ch in DIGRAPHS || FOLD.containsKey(ch) || ch.isUpperCase()) {
                needsFold = true
                break
            }
        }
        if (!needsFold) return word
        val sb = StringBuilder(word.length + 1)
        for (ch in word) {
            // Lowercase FIRST, so the maps only need their lowercase keys and
            // "Ä" folds as "ä" does.
            val lower = ch.lowercaseChar()
            val digraph = DIGRAPHS[lower]
            if (digraph != null) sb.append(digraph) else sb.append(FOLD[lower] ?: lower)
        }
        return sb.toString()
    }
}
