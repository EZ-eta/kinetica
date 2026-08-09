package com.kinetica.keyboard.keys

import org.junit.Assert.assertEquals
import org.junit.Test

class DeleteSpanTest {

    private fun tailOf(text: String, len: Int) = text.substring(text.length - len)

    // ---- word granularity: the shipped behavior, now under test -------------

    @Test
    fun oneWordTakesItsTrailingSpaceWithIt() {
        val t = "hello world "
        assertEquals("world ", tailOf(t, DeleteSpan.words(t, 1)))
        assertEquals("hello world ", tailOf(t, DeleteSpan.words(t, 2)))
    }

    @Test
    fun punctuationBelongsToTheWord() {
        // One step must remove "world," rather than leaving the comma behind.
        val t = "hello world, again"
        assertEquals("again", tailOf(t, DeleteSpan.words(t, 1)))
        assertEquals("world, again", tailOf(t, DeleteSpan.words(t, 2)))
    }

    @Test
    fun theSpanSaturatesAtTheStartOfTheText() {
        val t = "one two"
        assertEquals(t.length, DeleteSpan.words(t, 5))
        assertEquals(0, DeleteSpan.words(t, 0))
        assertEquals(0, DeleteSpan.words("", 3))
    }

    // ---- char granularity: the new mode -------------------------------------

    @Test
    fun charactersAreStagedOneAtATime() {
        val t = "hello"
        assertEquals("o", tailOf(t, DeleteSpan.chars(t, 1)))
        assertEquals("lo", tailOf(t, DeleteSpan.chars(t, 2)))
        assertEquals("hello", tailOf(t, DeleteSpan.chars(t, 5)))
    }

    @Test
    fun spacesAreOrdinaryCharactersInCharMode() {
        // The point of the mode: it must be able to stop between words, which
        // whole-word staging never does.
        val t = "hi there"
        assertEquals("e", tailOf(t, DeleteSpan.chars(t, 1)))
        assertEquals(" there", tailOf(t, DeleteSpan.chars(t, 6)))
    }

    @Test
    fun aSurrogatePairCountsAsOneCharacter() {
        // Otherwise one step leaves an unpaired surrogate in the editor and the
        // preview chip renders a replacement glyph.
        val t = "hi 😀"          // 5 chars: "hi " plus a 2-char surrogate pair
        assertEquals(5, t.length)
        assertEquals(2, DeleteSpan.chars(t, 1))
        assertEquals("😀", tailOf(t, DeleteSpan.chars(t, 1)))
        assertEquals(" 😀", tailOf(t, DeleteSpan.chars(t, 2)))
        // Four code points, not five chars: the pair spends one unit.
        assertEquals(t.length, DeleteSpan.chars(t, 4))
    }

    @Test
    fun charSpanSaturatesAndHandlesDegenerateInput() {
        assertEquals(4, DeleteSpan.chars("abcd", 99))
        assertEquals(0, DeleteSpan.chars("abcd", 0))
        assertEquals(0, DeleteSpan.chars("abcd", -2))
        assertEquals(0, DeleteSpan.chars("", 1))
        // A lone low surrogate (a truncated buffer) must not walk past the start.
        assertEquals(1, DeleteSpan.chars("\uDE00", 1))
    }

    // ---- the two granularities are the same gesture ------------------------

    @Test
    fun charModeUsesAShorterStepButNotAProportionalOne() {
        val word = DeleteSpan.slideDpPerUnit(false)
        val char = DeleteSpan.slideDpPerUnit(true)
        assertEquals("word staging must keep its shipped threshold", 40f, word, 1e-4f)
        // Shorter, because a character is a smaller edit...
        org.junit.Assert.assertTrue("char step must be shorter: $char vs $word", char < word)
        // ...but well above the 8dp touch slop, or hand tremor would stage one,
        // and not proportional to the edit size or a long word would need a
        // screen and a half of travel.
        org.junit.Assert.assertTrue("char step must clear the touch slop: $char", char >= 16f)
        org.junit.Assert.assertTrue("char step must stay a fraction of a word: $char", char < word / 2f)
    }
}
