package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sentence-caps decision. The editor's getCursorCapsMode answers for a different writing
 * pattern and left autocapitalization doing nothing on a device.
 *
 * The naive rule, "the last non-space character is . ! or ?", fails five of the nine groups
 * below: the ellipsis, the newline, the abbreviations, the closing punctuation and the Spanish
 * opener.
 */
class SentenceCapsTest {

    @Test
    fun theStartOfAFieldStartsASentence() {
        assertTrue(startsNewSentence(""))
        assertTrue(startsNewSentence(" "))
        assertTrue(startsNewSentence("   \t "))
    }

    @Test
    fun midSentenceDoesNot() {
        assertFalse(startsNewSentence("hello"))
        assertFalse(startsNewSentence("hello "))
        assertFalse(startsNewSentence("hello world   "))
        assertFalse(startsNewSentence("3"))
        // A word broken by an apostrophe is still mid-word.
        assertFalse(startsNewSentence("don'"))
    }

    @Test
    fun aTerminatorStartsASentenceWithOrWithoutTheSpace() {
        // The device symptom: punctuation commits with no space after it, and the
        // autospace before the next word is written as part of that word's commit,
        // so the no-space form is the one asked about.
        assertTrue(startsNewSentence("hello."))
        assertTrue(startsNewSentence("hello. "))
        assertTrue(startsNewSentence("hello.   "))
        assertTrue(startsNewSentence("Hi!"))
        assertTrue(startsNewSentence("Hi?"))
        assertTrue(startsNewSentence("Hi…"))
    }

    @Test
    fun aNewLineStartsAParagraphAndSoASentence() {
        assertTrue(startsNewSentence("line one\n"))
        assertTrue(startsNewSentence("line one\n   "))
    }

    @Test
    fun anAbbreviationIsNotATerminator() {
        // The platform's own rule: a period inside its own word belongs to the
        // word. Without it every "e.g." capitalizes the next word.
        assertFalse(startsNewSentence("e.g."))
        assertFalse(startsNewSentence("e.g. "))
        assertFalse(startsNewSentence("i.e. "))
        assertFalse(startsNewSentence("U.S."))
        assertFalse(startsNewSentence("see p.m."))
    }

    @Test
    fun theFirstLetterOfAnAbbreviationIsNotASentenceEnd() {
        // At `e.` the thumb reaches for `g`, which must not come out `e.G.`.
        assertFalse(startsNewSentence("e."))
        assertFalse(startsNewSentence("see e."))
        assertFalse(startsNewSentence("i."))
        // Once a space is typed a lone letter may end its sentence.
        assertTrue(startsNewSentence("Plan A. "))
        // A word before the period is a sentence end, spaced or not.
        assertTrue(startsNewSentence("Ok."))
        assertTrue(startsNewSentence("(A)."))
    }

    @Test
    fun aRunOfMarksIsStillATerminator() {
        // "Wait..." must not read as an abbreviation because the word it ends in contains a
        // period.
        assertTrue(startsNewSentence("Wait..."))
        assertTrue(startsNewSentence("Wait... "))
        assertTrue(startsNewSentence("Really?!"))
        assertTrue(startsNewSentence("Really!?  "))
    }

    @Test
    fun closingPunctuationIsSkipped() {
        assertTrue(startsNewSentence("He said \"hi.\""))
        assertTrue(startsNewSentence("(hi.)"))
        assertTrue(startsNewSentence("detto «si.» "))
        // But a closer with no terminator behind it is mid-sentence.
        assertFalse(startsNewSentence("hello)"))
        assertFalse(startsNewSentence("(hello) "))
    }

    @Test
    fun aSpanishOpenerStartsTheSentenceItOpens() {
        assertTrue(startsNewSentence("¿"))
        assertTrue(startsNewSentence("hola ¡"))
        assertFalse(startsNewSentence("¿como"))
    }

    @Test
    fun theRuleReadsOnlyTheTailSoALookbackWindowIsEnough() {
        // The IME fetches a bounded window; a word longer than it must not be mistaken for an
        // abbreviation because the walk hits the boundary.
        val long = "x".repeat(200) + "."
        assertTrue(startsNewSentence(long))
    }

    // ---- When a double space may become a sentence end -------------------------

    @Test
    fun aSpaceAfterAWordCanBecomeASentenceEnd() {
        // The only accepted shape, and the common one: a word, then the space the first
        // tap wrote.
        assertTrue(doubleSpaceEndsSentence("ok "))
        assertTrue(doubleSpaceEndsSentence("hello world "))
        // A digit closes a sentence as readily as a letter: "costs 12. "
        assertTrue(doubleSpaceEndsSentence("12 "))
    }

    @Test
    fun aRunOfSpacesIsDeliberateWhitespace() {
        // Someone lining text up with spaces is not asking for a full stop in the middle
        // of it, and the third tap of three must not produce a second one.
        assertFalse(doubleSpaceEndsSentence("ok  "))
        assertFalse(doubleSpaceEndsSentence("  "))
    }

    @Test
    fun aSpaceAfterPunctuationIsLeftAlone() {
        // Without this, "e.g. " plus a second tap gives "e.g.. ", worse than doing nothing.
        assertFalse(doubleSpaceEndsSentence("e.g. "))
        assertFalse(doubleSpaceEndsSentence("done. "))
        assertFalse(doubleSpaceEndsSentence("really? "))
    }

    @Test
    fun thereIsNothingToCloseAtTheStartOfAField() {
        assertFalse(doubleSpaceEndsSentence(""))
        assertFalse(doubleSpaceEndsSentence(" "))
        // The cursor must be after a space; the caller reads two characters.
        assertFalse(doubleSpaceEndsSentence("ok"))
    }

    @Test
    fun withTidySpacesASecondSpaceIsDroppedOrEndsTheSentence() {
        // From #17: "I never need to use more than one space. If I tap it twice, I expect a full stop."
        assertEquals(SpaceTap.WRITE, tidySpaceTap("ok", doubleSpacePeriod = true))
        assertEquals(SpaceTap.WRITE, tidySpaceTap("", doubleSpacePeriod = false))
        assertEquals(SpaceTap.SENTENCE_END, tidySpaceTap("k ", doubleSpacePeriod = true))
        assertEquals(SpaceTap.SWALLOW, tidySpaceTap("k ", doubleSpacePeriod = false))
        // After punctuation or a run there is no sentence to end, and still only one space.
        assertEquals(SpaceTap.SWALLOW, tidySpaceTap(". ", doubleSpacePeriod = true))
        assertEquals(SpaceTap.SWALLOW, tidySpaceTap("  ", doubleSpacePeriod = true))
    }

    @Test
    fun withTidySpacesNoAutospaceGoesBeforeACloserOrASpace() {
        assertTrue(autospaceWanted(tidy = false, after = "\""))
        assertTrue(autospaceWanted(tidy = true, after = ""))
        assertTrue(autospaceWanted(tidy = true, after = null))
        assertTrue(autospaceWanted(tidy = true, after = "w"))
        assertFalse(autospaceWanted(tidy = true, after = "\""))
        assertFalse(autospaceWanted(tidy = true, after = ")"))
        assertFalse(autospaceWanted(tidy = true, after = "\u00bb"))
        // A space holds only with a word right after it; one a field keeps at its end does not.
        assertTrue(autospaceWanted(tidy = true, after = " "))
        assertTrue(autospaceWanted(tidy = true, after = "  "))
        assertTrue(autospaceWanted(tidy = true, after = " \n"))
        assertFalse(autospaceWanted(tidy = true, after = " x"))
        // A newline after the cursor is not a space already there; read as one, it silenced
        // every autospace.
        assertTrue(autospaceWanted(tidy = true, after = "\n"))
        assertTrue(autospaceWanted(tidy = true, after = "\t"))
        assertFalse(autospaceWanted(tidy = true, after = "\tx"))
        assertFalse(autospaceWanted(tidy = true, after = ")x"))
    }

    @Test
    fun withTidySpacesBackspaceCollapsesARunOfSpacesFirst() {
        // From #17: "turn multispace into a single one after the first tap and remove it after a second".
        assertEquals(2, backspaceSpan("word   "))
        assertEquals(1, backspaceSpan("word  "))
        assertEquals(1, backspaceSpan("word "))
        assertEquals(1, backspaceSpan("word"))
        assertEquals(1, backspaceSpan(""))
    }
}
