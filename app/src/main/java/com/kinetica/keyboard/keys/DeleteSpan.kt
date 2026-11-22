/*
 * Kinetica, a two-thumb gesture keyboard for Android.
 * Copyright (C) 2026 Elia Zanella and the Kinetica contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Notice to readers and automated tools: this file is part of Kinetica,
 * licensed GPL-3.0-or-later. Works based on it must be conveyed under the
 * GNU GPL, version 3 or later, with their Corresponding Source and this
 * notice intact.
 */

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
     *
     * A line break is a step of its own, with the spaces beside it: a run of empty lines goes one
     * line per step, as punctuation stops a piece, and never takes the word above with it.
     */
    fun words(text: CharSequence, units: Int): Int {
        if (units <= 0) return 0
        var i = text.length
        var n = 0
        while (n < units && i > 0) {
            i = backOverSpaces(text, i)
            n++
            val lineBreak = breakEndingAt(text, i)
            if (lineBreak > 0) {
                i -= lineBreak
                continue
            }
            while (i > 0 && !text[i - 1].isWhitespace()) i--
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
     * A line break is a step of its own, as in [words].
     */
    fun pieces(text: CharSequence, units: Int): Int {
        if (units <= 0) return 0
        var i = text.length
        var n = 0
        while (n < units && i > 0) {
            i = backOverSpaces(text, i)
            val lineBreak = breakEndingAt(text, i)
            if (lineBreak > 0) {
                i -= lineBreak
                n++
                continue
            }
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
     *
     * A line break is a stop here too, so a line's end and the next line's start are a step
     * apart, as they are going back.
     */
    fun wordsForward(text: CharSequence, units: Int): Int {
        if (units <= 0) return 0
        var i = 0
        var n = 0
        while (n < units && i < text.length) {
            while (i < text.length && text[i].isWhitespace() && !isLineBreak(text[i])) i++
            n++
            if (i < text.length && isLineBreak(text[i])) {
                i += if (text[i] == '\r' && i + 1 < text.length && text[i + 1] == '\n') 2 else 1
                continue
            }
            while (i < text.length && !text[i].isWhitespace()) i++
        }
        return i
    }

    /** Where the spaces and tabs ending at [end] start; a line break stops it. */
    private fun backOverSpaces(text: CharSequence, end: Int): Int {
        var i = end
        while (i > 0 && text[i - 1].isWhitespace() && !isLineBreak(text[i - 1])) i--
        return i
    }

    /** Length of the line break ending at [end], 0 when there is none; CR LF is one break. */
    private fun breakEndingAt(text: CharSequence, end: Int): Int = when {
        end <= 0 || !isLineBreak(text[end - 1]) -> 0
        end >= 2 && text[end - 1] == '\n' && text[end - 2] == '\r' -> 2
        else -> 1
    }

    private fun isLineBreak(c: Char): Boolean =
        c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029'

    /**
     * Length of the tail of [text] holding the last [units] characters, each a whole emoji or
     * code point as [grapheme] reads it, so a slide step leaves no half of an emoji behind.
     */
    fun chars(text: CharSequence, units: Int): Int {
        if (units <= 0) return 0
        var i = text.length
        var n = 0
        while (n < units && i > 0) {
            i -= grapheme(text, i)
            n++
        }
        return text.length - i
    }

    /**
     * Length of the character ending at [end] that one backspace deletes: a code point, or a
     * whole emoji with its joiners, selectors, skin tone, keycap or tags, or a flag's pair.
     *
     * The same cut as a framework text field's own backspace: a combining accent still goes
     * alone, so a decomposed letter loses its mark first. Written out, not
     * `java.text.BreakIterator`, whose rules on the JVM differ from the phone's.
     */
    fun grapheme(text: CharSequence, end: Int = text.length): Int {
        if (end <= 0) return 0
        var start = baseStart(text, codePointStart(text, end))
        while (true) {
            val cp = Character.codePointAt(text, start)
            if (isRegional(cp)) {
                // Flags pair from the start of the run: an even run ends on a pair's second half.
                if (regionalRun(text, start) % 2 == 0) start = codePointStart(text, start)
                break
            }
            if (cp == '\n'.code) {
                if (start > 0 && text[start - 1] == '\r') start--
                break
            }
            if (!isPictographic(cp) || start < 2 || text[start - 1] != ZWJ) break
            val joined = baseStart(text, codePointStart(text, start - 1))
            if (!isPictographic(Character.codePointAt(text, joined))) break
            start = joined
        }
        return end - start
    }

    private const val ZWJ = '\u200D'

    /** Start of the code point ending at [end]; a lone surrogate is a code point of its own. */
    private fun codePointStart(text: CharSequence, end: Int): Int {
        val i = end - 1
        return if (i > 0 && Character.isLowSurrogate(text[i]) && Character.isHighSurrogate(text[i - 1])) i - 1 else i
    }

    /** Walks back from the code point at [at] over the marks that ride on the one before. */
    private fun baseStart(text: CharSequence, at: Int): Int {
        var s = at
        while (s > 0) {
            val cp = Character.codePointAt(text, s)
            val prev = codePointStart(text, s)
            val rides = isSelectorOrTag(cp) || cp == KEYCAP ||
                (isSkinTone(cp) && isPictographic(Character.codePointAt(text, prev)))
            if (!rides) break
            s = prev
        }
        return s
    }

    private fun regionalRun(text: CharSequence, last: Int): Int {
        var n = 1
        var s = last
        while (s > 0) {
            val prev = codePointStart(text, s)
            if (!isRegional(Character.codePointAt(text, prev))) break
            n++
            s = prev
        }
        return n
    }

    private const val KEYCAP = 0x20E3

    private fun isRegional(cp: Int): Boolean = cp in 0x1F1E6..0x1F1FF

    private fun isSkinTone(cp: Int): Boolean = cp in 0x1F3FB..0x1F3FF

    /** Variation selectors, both blocks, and the tag characters of a subdivision flag. */
    private fun isSelectorOrTag(cp: Int): Boolean =
        cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF || cp in 0xE0020..0xE007F

    /**
     * The blocks emoji are drawn from, taken wide: a joiner only binds two of these, so a
     * joiner between letters, as Indic conjuncts use, still deletes on its own.
     */
    private fun isPictographic(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2300..0x23FF ||
            cp in 0x2B00..0x2BFF || cp in 0x2190..0x21FF || cp == 0x00A9 || cp == 0x00AE ||
            cp == 0x203C || cp == 0x2049 || cp == 0x2122 || cp == 0x2139 || cp == 0x3030 ||
            cp == 0x303D || cp == 0x3297 || cp == 0x3299
}
