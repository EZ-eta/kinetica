package com.kinetica.keyboard.ime

import com.kinetica.keyboard.ui.BarMark
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A bar word's mark says what a commit does with it: a bundled word nothing, a personal one `C`, an
 * unknown one `+` when it is learned and `?` when it is not.
 */
class WordMarkTest {

    private fun mark(
        inList: Boolean = false,
        personal: Boolean = false,
        learnable: Boolean = true,
        teachesNothing: Boolean = false,
        blocked: Boolean = false,
    ): BarMark = wordMark(inList, personal, learnable, teachesNothing, blocked)

    @Test
    fun aBundledWordHasNoMarkEvenWhenLearned() {
        assertEquals(BarMark.NONE, mark(inList = true))
        assertEquals(BarMark.NONE, mark(inList = true, personal = true))
        assertEquals(BarMark.NONE, mark(inList = true, teachesNothing = true))
    }

    @Test
    fun aPersonalWordIsMarkedWhereverItIsTyped() {
        assertEquals(BarMark.PERSONAL, mark(personal = true))
        // In a field that keeps nothing it is still the user's word.
        assertEquals(BarMark.PERSONAL, mark(personal = true, teachesNothing = true))
    }

    @Test
    fun anUnknownWordIsLearnedAtCommitUnlessSomethingRefusesIt() {
        assertEquals(BarMark.LEARN, mark())
        assertEquals(BarMark.UNKNOWN, mark(teachesNothing = true))
        assertEquals(BarMark.UNKNOWN, mark(learnable = false))
        assertEquals(BarMark.UNKNOWN, mark(blocked = true))
    }

    @Test
    fun theGlyphsAreTheOnesAskedFor() {
        assertEquals(null, BarMark.NONE.glyph)
        assertEquals("+", BarMark.LEARN.glyph)
        assertEquals("?", BarMark.UNKNOWN.glyph)
        assertEquals("C", BarMark.PERSONAL.glyph)
    }

    @Test
    fun lettersWithADigitAreNotLearned() {
        // The learn's own gate, so `?` and a refused learn agree.
        assertEquals(BarMark.UNKNOWN, mark(learnable = learnable("abc1")))
        assertEquals(BarMark.LEARN, mark(learnable = learnable("kinetica")))
    }
}
