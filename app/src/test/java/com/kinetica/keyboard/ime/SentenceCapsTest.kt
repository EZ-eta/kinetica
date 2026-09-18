package com.kinetica.keyboard.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sentence-caps decision, which is why autocapitalization did nothing on
 * device: the keyboard asked the editor (getCursorCapsMode) a question the
 * platform answers for a different writing pattern than this one.
 *
 * The naive rule - "the last non-space character is . ! or ?" - fails FIVE of the
 * nine cases below: the ellipsis, the newline, the abbreviations, the closing
 * punctuation and the Spanish opener. That is what the groups are separated for.
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
        // so the no-space form is the one that actually gets asked about.
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
    fun aRunOfMarksIsStillATerminator() {
        // "Wait..." must not read as an abbreviation just because the word it
        // ends in now contains a period.
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
        // But a closer with no terminator behind it is just mid-sentence.
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
        // The IME fetches a bounded window; a word longer than it must not be
        // mistaken for an abbreviation just because the walk hits the boundary.
        val long = "x".repeat(200) + "."
        assertTrue(startsNewSentence(long))
    }

    // ---- R69: when a double space may become a sentence end -------------------------

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
        // Without this, "e.g. " plus a second tap gives "e.g.. ", which is worse than
        // doing nothing. Item 70 is the related capitalization bug and is separate.
        assertFalse(doubleSpaceEndsSentence("e.g. "))
        assertFalse(doubleSpaceEndsSentence("done. "))
        assertFalse(doubleSpaceEndsSentence("really? "))
    }

    @Test
    fun thereIsNothingToCloseAtTheStartOfAField() {
        assertFalse(doubleSpaceEndsSentence(""))
        assertFalse(doubleSpaceEndsSentence(" "))
        // And the cursor must actually be after a space; the caller reads two characters.
        assertFalse(doubleSpaceEndsSentence("ok"))
    }
}
