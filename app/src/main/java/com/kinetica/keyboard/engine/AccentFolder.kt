package com.kinetica.keyboard.engine

/**
 * Maps accented Latin letters onto the 27-symbol trie alphabet (a-z plus
 * apostrophe). Gesture geometry only knows base keys - an Italian user swipes
 * the same path for "perche" and "perché" - so accented dictionary words are
 * stored under their folded key and resurface as display variants at emit
 * time (see [LoadedDictionary.forms]).
 */
object AccentFolder {

    private val FOLD = HashMap<Char, Char>().apply {
        "àáâäãå".forEach { put(it, 'a') }
        "èéêë".forEach { put(it, 'e') }
        "ìíîï".forEach { put(it, 'i') }
        "òóôöõ".forEach { put(it, 'o') }
        "ùúûü".forEach { put(it, 'u') }
        put('ç', 'c'); put('ć', 'c')
        put('ñ', 'n'); put('ń', 'n')
        put('ý', 'y')
        put('ł', 'l')
        put('ś', 's')
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
     * shipped commit-then-insert behaviour, and "ß" (which folds to "ss", two
     * letters, i.e. not a key) is excluded by construction.
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

    /** Folded form of [word]; returns the same instance when nothing folds. */
    fun fold(word: String): String {
        var needsFold = false
        for (ch in word) {
            if (ch == 'ß' || FOLD.containsKey(ch)) {
                needsFold = true
                break
            }
        }
        if (!needsFold) return word
        val sb = StringBuilder(word.length + 1)
        for (ch in word) {
            when {
                ch == 'ß' -> sb.append("ss")
                else -> sb.append(FOLD[ch] ?: ch)
            }
        }
        return sb.toString()
    }
}
