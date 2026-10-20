package com.kinetica.keyboard.keys

/**
 * How much of the text before the cursor a staged backspace slide covers.
 *
 * Pure and separate from the touch handling so both granularities are testable:
 * [BackspaceController] turns travel into a unit count, this turns a unit count
 * into a character length, and `KineticaIME` only has to preview and delete it.
 */
object DeleteSpan {

    /** What one staged unit is: a character, a word, or a word cut at punctuation. */
    enum class Granularity { CHARS, WORDS, PIECES }

    /** A word's step as shipped, and the setting's bounds. */
    const val WORD_STEP_DP = 40
    const val MIN_WORD_STEP_DP = 20
    const val MAX_WORD_STEP_DP = 80

    /**
     * Leftward travel, in dp, that stages one more unit, for a word step of [wordStepDp].
     *
     * A character's step is smaller than a word's, but not proportionally: 18dp at the shipped
     * 40. It never drops under 16dp, twice the touch slop, so a staged count is reached by intent
     * and not by hand tremor, and a full keyboard width spans a long word, not a sentence.
     */
    fun slideDpPerUnit(charMode: Boolean, wordStepDp: Float = WORD_STEP_DP.toFloat()): Float =
        if (charMode) (wordStepDp * CHAR_STEP_SHARE).coerceAtLeast(MIN_CHAR_STEP_DP) else wordStepDp

    private const val CHAR_STEP_SHARE = 0.45f
    private const val MIN_CHAR_STEP_DP = 16f

    /**
     * Characters a staged slide of [units] covers when the editor already holds a selection of
     * [selectionLength] characters, counting back from the selection's end.
     *
     * A selection is the first unit, whatever the granularity: the user selected it as one
     * thing, so one step removes it and further steps continue into [before] (the text preceding
     * the selection start). With no selection this is the plain word or character walk.
     */
    fun staged(selectionLength: Int, before: CharSequence, units: Int, granularity: Granularity): Int {
        if (units <= 0) return 0
        if (selectionLength <= 0) return walk(before, units, granularity)
        return selectionLength + walk(before, units - 1, granularity)
    }

    private fun walk(text: CharSequence, units: Int, granularity: Granularity): Int =
        when (granularity) {
            Granularity.CHARS -> chars(text, units)
            Granularity.WORDS -> words(text, units)
            Granularity.PIECES -> pieces(text, units)
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
     * Length of the tail of [text] holding the last [units] pieces: a word cut at punctuation,
     * so `example.com/path` goes `path`, `com/`, `example.`. Each piece keeps the punctuation
     * and whitespace after it, as [words] does, and the marks that open a word, so prose still
     * goes `word, ` or `"word" ` in one step.
     *
     * Letters, digits and combining marks make a piece; everything else that is not whitespace
     * cuts one. A surrogate pair is never split: both halves are on the same side of that line.
     */
    fun pieces(text: CharSequence, units: Int): Int {
        if (units <= 0) return 0
        var i = text.length
        var n = 0
        while (n < units && i > 0) {
            while (i > 0 && text[i - 1].isWhitespace()) i--
            while (i > 0 && isMark(text[i - 1])) i--
            while (i > 0 && isPieceChar(text[i - 1])) i--
            var open = i
            while (open > 0 && isMark(text[open - 1])) open--
            if (open == 0 || text[open - 1].isWhitespace()) i = open
            n++
        }
        return text.length - i
    }

    private fun isMark(c: Char): Boolean = !c.isWhitespace() && !isPieceChar(c)

    private fun isPieceChar(c: Char): Boolean = c.isLetterOrDigit() || when (Character.getType(c)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        -> true
        else -> false
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
