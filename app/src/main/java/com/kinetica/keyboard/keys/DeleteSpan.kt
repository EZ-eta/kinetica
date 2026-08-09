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
