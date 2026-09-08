package com.kinetica.keyboard.keys

/**
 * The case of a written word, as a thing that can be asked for.
 *
 * `ShiftState` already knows these three shapes but only as a consequence of taps: it
 * decides what the NEXT letter will look like and has no notion of re-casing text that
 * already exists. R34 asks for the other direction - the word is on screen and wrong, and
 * the user wants it in another case - so the map has to be nameable on its own, and it has
 * to be invertible, because the popup pre-selects the case the word is already in.
 *
 * Pure, and deliberately not a method on `ShiftState`: reading a state off text is not
 * something a tap machine should be able to do.
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

    companion object {
        /**
         * The case [word] is written in.
         *
         * A one-letter word reads as TITLE when it is uppercase, matching the same
         * `length > 1` guard `reloadWordUnderCursor` uses to tell `I` from a shouted
         * word: a single capital is far more often a sentence start than an
         * abbreviation, and the popup would otherwise open on UPPER for every `I`.
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
