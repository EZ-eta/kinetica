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
     * A character is a much smaller edit than a word, so its step is smaller -
     * but not proportionally. It stays above the touch slop and above one key
     * width at every density, so a staged count is still reached deliberately
     * rather than by hand tremor, and a full keyboard width of travel spans a
     * long word rather than a whole sentence. Ten characters cost 180dp, about
     * the travel of 4-5 words, which is the granularity trade the mode exists
     * for.
     */
    fun slideDpPerUnit(charMode: Boolean): Float = if (charMode) 18f else 40f

    /**
     * Characters a staged slide of [units] covers when the editor already holds a
     * selection of [selectionLength] characters, counting back from the
     * selection's END.
     *
     * A selection is the first unit, whatever the granularity: the user selected
     * it as one thing, so one step of the slide removes exactly it and further
     * steps continue into [before] (the text preceding the selection start). With
     * no selection this is the shipped word or character walk unchanged, which is
     * what keeps the no-selection path byte-identical.
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
     * words, trailing whitespace included. Punctuation is part of a word, which
     * is what makes one slide step remove "word," rather than leaving the comma
     * behind.
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
     * leading whitespace included - the forward mirror of [words].
     *
     * Written for the spacebar's word-wise cursor slide, which needs the same walk in the
     * other direction. Kept beside [words] so both agree on what a word is - punctuation
     * belongs to it, so `word,` is one step either way.
     *
     * The two are NOT inverses and must not be made so. This one takes the whitespace
     * BEFORE the word, so moving right lands after a word; [words] takes the whitespace
     * after it, so moving left lands before one. That asymmetry is the ordinary editor
     * convention - ctrl-right stops at word ends, ctrl-left at word starts - and it also
     * happens to be exactly what [words] needs for deletion, where the trailing space has
     * to go with the word it followed. A round trip therefore does not return to its
     * starting offset, which DeleteSpanTest asserts on purpose.
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
     * Length of the tail of [text] holding the last [units] characters, counting
     * a surrogate pair as ONE - so a slide step removes a whole emoji rather than
     * half of one and leaving an unpaired surrogate behind.
     *
     * Tap backspace still deletes a single `char`; that asymmetry is pre-existing
     * and is not something this rule should quietly change on one path only.
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
