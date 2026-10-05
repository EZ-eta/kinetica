package com.kinetica.keyboard.keys

/**
 * How much of the text before the cursor a staged backspace slide covers.
 *
 * Pure and separate from the touch handling so both granularities are testable:
 * [BackspaceController] turns travel into a unit count, this turns a unit count
 * into a character length, and `KineticaIME` only has to preview and delete it.
 */
object DeleteSpan {

    /**
     * Leftward travel, in dp, that stages one more unit.
     *
     * A character's step is smaller than a word's, but not proportionally. It stays above the
     * touch slop at every density, so a staged count is reached by intent and not by hand
     * tremor, and a full keyboard width spans a long word, not a sentence. Ten characters cost
     * 180dp, about the travel of 4-5 words.
     */
    fun slideDpPerUnit(charMode: Boolean): Float = if (charMode) 18f else 40f

    /**
     * Characters a staged slide of [units] covers when the editor already holds a selection of
     * [selectionLength] characters, counting back from the selection's end.
     *
     * A selection is the first unit, whatever the granularity: the user selected it as one
     * thing, so one step removes it and further steps continue into [before] (the text preceding
     * the selection start). With no selection this is the plain word or character walk.
     */
    fun staged(selectionLength: Int, before: CharSequence, units: Int, charMode: Boolean): Int {
        if (units <= 0) return 0
        if (selectionLength <= 0) {
            return if (charMode) chars(before, units) else words(before, units)
        }
        val rest = units - 1
        return selectionLength + if (charMode) chars(before, rest) else words(before, rest)
    }

    /**
     * Length of the tail of [text] holding the last [units] whitespace-delimited
     * words, trailing whitespace included. Punctuation is part of a word, so one slide step
     * removes "word," and leaves no comma behind.
     */
    fun words(text: CharSequence, units: Int): Int {
        if (units <= 0) return 0
        var i = text.length
        var n = 0
        while (n < units && i > 0) {
            while (i > 0 && text[i - 1].isWhitespace()) i--
            while (i > 0 && !text[i - 1].isWhitespace()) i--
            n++
        }
        return text.length - i
    }

    /**
     * Length of the head of [text] holding the first [units] whitespace-delimited words,
     * leading whitespace included: the forward mirror of [words], for the spacebar's word-wise
     * cursor slide. Kept beside [words] so both agree on what a word is; punctuation belongs to
     * it, so `word,` is one step either way.
     *
     * The two are not inverses and must not be made so. This one takes the whitespace before the
     * word, so moving right lands after a word; [words] takes the whitespace after it, so moving
     * left lands before one. That is the usual editor convention (ctrl-right stops at word ends,
     * ctrl-left at word starts), and deletion needs the trailing space to go with its word. A
     * round trip does not return to its starting offset, and DeleteSpanTest asserts it.
     */
    fun wordsForward(text: CharSequence, units: Int): Int {
        if (units <= 0) return 0
        var i = 0
        var n = 0
        while (n < units && i < text.length) {
            while (i < text.length && text[i].isWhitespace()) i++
            while (i < text.length && !text[i].isWhitespace()) i++
            n++
        }
        return i
    }

    /**
     * Length of the tail of [text] holding the last [units] characters, counting a surrogate pair
     * as one, so a slide step removes a whole emoji and leaves no unpaired surrogate.
     *
     * Tap backspace still deletes a single `char`; that asymmetry predates this rule, which
     * should not change it on one path only.
     */
    fun chars(text: CharSequence, units: Int): Int {
        if (units <= 0) return 0
        var i = text.length
        var n = 0
        while (n < units && i > 0) {
            i--
            if (i > 0 && Character.isLowSurrogate(text[i]) && Character.isHighSurrogate(text[i - 1])) i--
            n++
        }
        return text.length - i
    }
}
