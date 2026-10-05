package com.kinetica.keyboard.keys

/**
 * The case of a written word, as a thing that can be asked for.
 *
 * `ShiftState` decides what the next letter looks like from taps; re-casing a word already on
 * screen needs the case as a value of its own, and invertible, because the popup
 * pre-selects the case the word is in. Pure, and not on `ShiftState`, which should not read state
 * off text.
 */
enum class WordCase {
    LOWER,
    TITLE,
    UPPER,
    ;

    fun applyTo(word: String): String = when (this) {
        LOWER -> word.lowercase()
        TITLE -> word.lowercase().replaceFirstChar { it.uppercaseChar() }
        UPPER -> word.uppercase()
    }

    /**
     * [applyTo] for any run of text, a selection: TITLE capitalizes every word in it, not
     * only the first letter of the run.
     */
    fun applyToText(text: String): String = when (this) {
        LOWER, UPPER -> applyTo(text)
        TITLE -> buildString(text.length) {
            // A word starts at a letter after anything but a letter or an apostrophe, so
            // `(quoted)` is a word and `don't` stays one.
            var start = true
            for (c in text.lowercase()) {
                append(if (start && c.isLetter()) c.uppercaseChar() else c)
                start = !c.isLetter() && c != '\''
            }
        }
    }

    /**
     * [applyTo] for a word split at the cursor, one half at a time, so the cursor can go back
     * between them. Joined, the halves equal [applyTo] on the whole word wherever a case
     * change keeps each character's length, which is every letter but a few like `ß`.
     */
    fun applyAround(head: String, tail: String): Pair<String, String> = when (this) {
        LOWER -> head.lowercase() to tail.lowercase()
        TITLE -> TITLE.applyTo(head) to tail.lowercase()
        UPPER -> head.uppercase() to tail.uppercase()
    }

    companion object {
        /**
         * The case [word] is written in.
         *
         * A one-letter word reads as TITLE when it is uppercase, matching the `length > 1` guard
         * `reloadWordUnderCursor` uses to tell `I` from a shouted word: a single capital is far
         * more often a sentence start than an abbreviation, and the popup would otherwise open on
         * UPPER for every `I`.
         */
        fun of(word: String): WordCase = when {
            word.isEmpty() -> LOWER
            word.length > 1 && word.all { !it.isLetter() || it.isUpperCase() } &&
                word.any { it.isLetter() } -> UPPER
            word.first().isUpperCase() -> TITLE
            else -> LOWER
        }
    }
}
