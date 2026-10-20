package com.kinetica.keyboard.keys

import com.kinetica.keyboard.keys.DeleteSpan.Granularity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteSpanTest {

    private fun tailOf(text: String, len: Int) = text.substring(text.length - len)

    private fun headOf(text: String, len: Int) = text.substring(0, len)

    // ---- word granularity ---------------------------------------------------

    @Test
    fun oneWordTakesItsTrailingSpaceWithIt() {
        val t = "hello world "
        assertEquals("world ", tailOf(t, DeleteSpan.words(t, 1)))
        assertEquals("hello world ", tailOf(t, DeleteSpan.words(t, 2)))
    }

    @Test
    fun punctuationBelongsToTheWord() {
        // One step must remove "world," comma included.
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

    // ---- char granularity ---------------------------------------------------

    @Test
    fun charactersAreStagedOneAtATime() {
        val t = "hello"
        assertEquals("o", tailOf(t, DeleteSpan.chars(t, 1)))
        assertEquals("lo", tailOf(t, DeleteSpan.chars(t, 2)))
        assertEquals("hello", tailOf(t, DeleteSpan.chars(t, 5)))
    }

    @Test
    fun spacesAreOrdinaryCharactersInCharMode() {
        // Char mode can stop between words, which whole-word staging never does.
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
        assertEquals(5, DeleteSpan.staged(5, before, 1, Granularity.WORDS))
        assertEquals(5, DeleteSpan.staged(5, before, 1, Granularity.CHARS))
    }

    @Test
    fun furtherStepsContinueIntoTheTextBeforeTheSelection() {
        val before = "hello "
        // Word mode: the selection, then "hello " as one word with its space.
        assertEquals(5 + 6, DeleteSpan.staged(5, before, 2, Granularity.WORDS))
        // Char mode: the selection, then one character of "hello " at a time.
        assertEquals(5 + 1, DeleteSpan.staged(5, before, 2, Granularity.CHARS))
        assertEquals(5 + 2, DeleteSpan.staged(5, before, 3, Granularity.CHARS))
    }

    @Test
    fun withNoSelectionTheSpanIsTheShippedWalk() {
        // Without a selection the staging is the plain walk in either mode, degenerate
        // counts included.
        val t = "hello world, again"
        for (units in 0..4) {
            assertEquals(DeleteSpan.words(t, units), DeleteSpan.staged(0, t, units, Granularity.WORDS))
            assertEquals(DeleteSpan.chars(t, units), DeleteSpan.staged(0, t, units, Granularity.CHARS))
            assertEquals(DeleteSpan.pieces(t, units), DeleteSpan.staged(0, t, units, Granularity.PIECES))
        }
        assertEquals(0, DeleteSpan.staged(0, t, -1, Granularity.WORDS))
    }

    @Test
    fun aSelectionAtTheStartOfTheFieldCannotOverrun() {
        // Nothing before it, so every further step is a no-op, never a walk past offset zero.
        assertEquals(4, DeleteSpan.staged(4, "", 1, Granularity.WORDS))
        assertEquals(4, DeleteSpan.staged(4, "", 9, Granularity.WORDS))
        assertEquals(4, DeleteSpan.staged(4, "", 9, Granularity.CHARS))
        // And a zero-unit slide stages nothing even with a selection up: the
        // slide must stay retractable to a no-op.
        assertEquals(0, DeleteSpan.staged(4, "abc", 0, Granularity.WORDS))
    }

    @Test
    fun theSpanNeverShrinksAsTheSlideGrows() {
        // The backspace slide's highlight recomputes the staged span from a snapshot at every
        // threshold crossing, so retracting from four units to three must give a smaller span;
        // otherwise the highlight would grow on a retraction.
        val before = "the quick brown fox jumps over"
        for (sel in listOf(0, 5)) {
            for (granularity in Granularity.values()) {
                var last = 0
                for (units in 0..8) {
                    val span = DeleteSpan.staged(sel, before, units, granularity)
                    assertTrue(
                        "span shrank at units=$units (sel=$sel $granularity)",
                        span >= last,
                    )
                    last = span
                }
            }
        }
    }

    // ---- pieces: the word walk cut at punctuation ---------------------------

    @Test
    fun aLinkGoesOnePieceAtATime() {
        val t = "see https://example.com/path"
        assertEquals("path", tailOf(t, DeleteSpan.pieces(t, 1)))
        assertEquals("com/path", tailOf(t, DeleteSpan.pieces(t, 2)))
        assertEquals("example.com/path", tailOf(t, DeleteSpan.pieces(t, 3)))
        assertEquals("https://example.com/path", tailOf(t, DeleteSpan.pieces(t, 4)))
        assertEquals("see https://example.com/path", tailOf(t, DeleteSpan.pieces(t, 5)))
        // The whole link is one step for the plain word walk: the reason for the option.
        assertEquals("https://example.com/path", tailOf(t, DeleteSpan.words(t, 1)))
    }

    @Test
    fun aSelectionThenPiecesOfTheLinkBeforeIt() {
        // "see example.com/|path|" with "path" selected: the selection, then "com/".
        val before = "see example.com/"
        assertEquals(4, DeleteSpan.staged(4, before, 1, Granularity.PIECES))
        assertEquals(4 + 4, DeleteSpan.staged(4, before, 2, Granularity.PIECES))
        assertEquals(4 + 12, DeleteSpan.staged(4, before, 2, Granularity.WORDS))
    }

    @Test
    fun aQueryStringStopsAtEachMark() {
        val t = "x.com/a?utm_source=feed&id=7"
        assertEquals("7", tailOf(t, DeleteSpan.pieces(t, 1)))
        assertEquals("id=7", tailOf(t, DeleteSpan.pieces(t, 2)))
        assertEquals("feed&id=7", tailOf(t, DeleteSpan.pieces(t, 3)))
        assertEquals("source=feed&id=7", tailOf(t, DeleteSpan.pieces(t, 4)))
    }

    @Test
    fun proseStillGoesAWordWithItsPunctuation() {
        // Trailing marks belong to the piece before them, so plain text steps as the word
        // walk does.
        for (t in listOf("hello world, again ", "Fine. Thanks! ", "wait... ", "said \"no\" ", "(see) x")) {
            for (units in 0..4) {
                assertEquals(t, DeleteSpan.words(t, units), DeleteSpan.pieces(t, units))
            }
        }
    }

    @Test
    fun anElisionIsTwoPieces() {
        val t = "dell'altro"
        assertEquals("altro", tailOf(t, DeleteSpan.pieces(t, 1)))
        assertEquals(t, tailOf(t, DeleteSpan.pieces(t, 2)))
    }

    @Test
    fun accentsAndEmojiStayWhole() {
        // A decomposed accent is a combining mark, part of the piece; an emoji is a mark run,
        // its surrogate pair taken together.
        val decomposed = "re\u0301sume\u0301"
        assertEquals(decomposed.length, DeleteSpan.pieces(decomposed, 1))
        val emoji = "ok \uD83D\uDE00\uD83D\uDE00"
        assertEquals(4, DeleteSpan.pieces(emoji, 1))
        assertEquals(emoji.length, DeleteSpan.pieces(emoji, 2))
        assertEquals(0, DeleteSpan.pieces("", 3))
        assertEquals(0, DeleteSpan.pieces("abc", 0))
        assertEquals(3, DeleteSpan.pieces("abc", 9))
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

    @Test
    fun theStepSettingKeepsBothGranularitiesApartAndAboveTheSlop() {
        // Every value the slider offers: a word step as set, a character step shorter than a
        // word and never under twice the 8dp touch slop.
        for (word in DeleteSpan.MIN_WORD_STEP_DP..DeleteSpan.MAX_WORD_STEP_DP) {
            val w = DeleteSpan.slideDpPerUnit(false, word.toFloat())
            val c = DeleteSpan.slideDpPerUnit(true, word.toFloat())
            assertEquals(word.toFloat(), w, 1e-4f)
            org.junit.Assert.assertTrue("char step under the slop floor at $word: $c", c >= 16f)
            org.junit.Assert.assertTrue("char step not shorter than a word at $word: $c", c < w)
        }
        // The shipped step is the default of both the setting and the function.
        assertEquals(18f, DeleteSpan.slideDpPerUnit(true, DeleteSpan.WORD_STEP_DP.toFloat()), 1e-4f)
        assertEquals(
            com.kinetica.keyboard.settings.Prefs.DEFAULT_BACKSPACE_STEP_DP,
            DeleteSpan.WORD_STEP_DP,
        )
    }

    @Test
    fun aLongerStepIsSlowerInBothGranularities() {
        for (charMode in listOf(false, true)) {
            val fast = DeleteSpan.slideDpPerUnit(charMode, DeleteSpan.MIN_WORD_STEP_DP.toFloat())
            val slow = DeleteSpan.slideDpPerUnit(charMode, DeleteSpan.MAX_WORD_STEP_DP.toFloat())
            org.junit.Assert.assertTrue("char=$charMode: $fast vs $slow", fast < slow)
        }
    }

    // ---- the forward mirror, for the spacebar's word slide -----------------

    @Test
    fun oneWordForwardTakesItsLeadingSpaceWithIt() {
        val t = " world again"
        assertEquals(" world", headOf(t, DeleteSpan.wordsForward(t, 1)))
        assertEquals(" world again", headOf(t, DeleteSpan.wordsForward(t, 2)))
    }

    @Test
    fun punctuationBelongsToTheWordForwardsToo() {
        // The same rule in both directions, or a slide right then left would not land
        // back where it started.
        val t = "world, again"
        assertEquals("world,", headOf(t, DeleteSpan.wordsForward(t, 1)))
    }

    @Test
    fun theForwardSpanSaturatesAtTheEndOfTheText() {
        val t = "one two"
        assertEquals(t.length, DeleteSpan.wordsForward(t, 2))
        assertEquals(t.length, DeleteSpan.wordsForward(t, 9))
    }

    @Test
    fun zeroUnitsAndAnEmptyTextMoveNothingEitherWay() {
        assertEquals(0, DeleteSpan.wordsForward("one two", 0))
        assertEquals(0, DeleteSpan.wordsForward("", 3))
        assertEquals(0, DeleteSpan.words("", 3))
    }

    @Test
    fun rightStopsAtWordEndsAndLeftAtWordStarts() {
        // The two directions are asymmetric. Forward takes the whitespace before a word so it
        // lands after one; backward takes the whitespace after it so it lands before one. That
        // is the ordinary editor convention, and what `words` needs for deletion, where the
        // trailing space goes with the word it followed. The double space checks that a run of
        // whitespace is one boundary, not two.
        val t = "one two, three  four"
        val forward = ArrayList<Int>()
        var i = 0
        while (i < t.length) {
            i += DeleteSpan.wordsForward(t.substring(i), 1)
            forward.add(i)
        }
        val back = ArrayList<Int>()
        var j = t.length
        while (j > 0) {
            j -= DeleteSpan.words(t.substring(0, j), 1)
            back.add(j)
        }
        assertEquals(listOf(3, 8, 14, 20), forward)
        assertEquals(listOf(16, 9, 4, 0), back)
        // Every forward stop is the end of a word and every backward stop the start of
        // one, which is the invariant the offsets above are an instance of.
        for (n in forward) assertTrue("$n ends a word", n == t.length || t[n].isWhitespace())
        for (n in back) assertTrue("$n starts a word", n == 0 || !t[n].isWhitespace())
    }
}
