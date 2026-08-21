package com.kinetica.keyboard.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // ---- a selection is the first staged unit -------------------------------

    @Test
    fun aSelectionIsOneUnitWhateverTheGranularity() {
        // "hello |world| again" with "world" selected: before = "hello ",
        // selection = 5. One step takes the selection and nothing else, in either
        // mode, because the user already picked it out as one thing.
        val before = "hello "
        assertEquals(5, DeleteSpan.staged(5, before, 1, charMode = false))
        assertEquals(5, DeleteSpan.staged(5, before, 1, charMode = true))
    }

    @Test
    fun furtherStepsContinueIntoTheTextBeforeTheSelection() {
        val before = "hello "
        // Word mode: the selection, then "hello " as one word with its space.
        assertEquals(5 + 6, DeleteSpan.staged(5, before, 2, charMode = false))
        // Char mode: the selection, then one character of "hello " at a time.
        assertEquals(5 + 1, DeleteSpan.staged(5, before, 2, charMode = true))
        assertEquals(5 + 2, DeleteSpan.staged(5, before, 3, charMode = true))
    }

    @Test
    fun withNoSelectionTheSpanIsTheShippedWalk() {
        // The regression that matters: staging without a selection must not have
        // changed at all, in either mode, including the degenerate counts.
        val t = "hello world, again"
        for (units in 0..4) {
            assertEquals(DeleteSpan.words(t, units), DeleteSpan.staged(0, t, units, false))
            assertEquals(DeleteSpan.chars(t, units), DeleteSpan.staged(0, t, units, true))
        }
        assertEquals(0, DeleteSpan.staged(0, t, -1, false))
    }

    @Test
    fun aSelectionAtTheStartOfTheFieldCannotOverrun() {
        // Nothing before it, so every further step is a no-op rather than a walk
        // past offset zero.
        assertEquals(4, DeleteSpan.staged(4, "", 1, charMode = false))
        assertEquals(4, DeleteSpan.staged(4, "", 9, charMode = false))
        assertEquals(4, DeleteSpan.staged(4, "", 9, charMode = true))
        // And a zero-unit slide stages nothing even with a selection up: the
        // slide must stay retractable to a no-op.
        assertEquals(0, DeleteSpan.staged(4, "abc", 0, charMode = false))
    }

    @Test
    fun theSpanNeverShrinksAsTheSlideGrows() {
        // What the backspace slide's highlight rests on: the staged span is
        // recomputed from a snapshot on every threshold crossing, so retracting
        // from four units to three has to give back a SMALLER span. If this were
        // not monotone the highlight would grow on a retraction.
        val before = "the quick brown fox jumps over"
        for (sel in listOf(0, 5)) {
            for (charMode in listOf(false, true)) {
                var last = 0
                for (units in 0..8) {
                    val span = DeleteSpan.staged(sel, before, units, charMode)
                    assertTrue(
                        "span shrank at units=$units (sel=$sel char=$charMode)",
                        span >= last,
                    )
                    last = span
                }
            }
        }
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
