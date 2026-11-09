package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The emoji search query: what the keys do to it while the panel is up. */
class EmojiQueryTest {

    @Test
    fun backspaceOnAnEmptyQuerySaysSoAndTouchesNothing() {
        val q = EmojiQuery()
        assertFalse("an empty query must not report a delete, or the field would lose one", q.backspace())
        q.tapped("a")
        assertTrue(q.backspace())
        assertEquals("", q.text)
    }

    @Test
    fun spacesSeparateWordsAndNeverLeadOrDouble() {
        val q = EmojiQuery()
        q.space()
        assertEquals("", q.text)
        q.tapped("r"); q.tapped("e"); q.tapped("d")
        q.space(); q.space()
        q.tapped("h")
        assertEquals("red h", q.text)
    }

    /**
     * A captured `heart`: a right-thumb tap on h, then `ea` and `rt` swiped by alternate thumbs.
     * Each swipe was decoded alone and spaced, so the query read `h eat`, then `h era rt`.
     */
    @Test
    fun aTwoThumbWordIsOneWordInTheQuery() {
        val q = EmojiQuery()
        q.tapped("h")
        q.beginSwipe()
        // The decode of tap + first swipe, then of the whole word: each replaces the word in hand.
        q.decoded("Hea")
        assertEquals("hea", q.text)
        q.beginSwipe()
        q.decoded("heart")
        assertEquals("heart", q.text)
    }

    @Test
    fun aTappedWordKeepsItsLettersAgainstItsDecode() {
        val q = EmojiQuery()
        q.tapped("c"); q.tapped("u"); q.tapped("o")
        q.decoded("cuore")
        assertEquals("cuo", q.text)
    }

    @Test
    fun aSecondDecodeReplacesTheFirstAndASettledWordStays() {
        val q = EmojiQuery()
        q.tapped("r"); q.tapped("e"); q.tapped("d")
        q.space()
        q.beginSwipe()
        q.decoded("Hearth")
        q.decoded("heart")
        assertEquals("red heart", q.text)
        // The word end settles it: a stale decode arriving after it changes nothing.
        q.space()
        q.decoded("hear")
        assertEquals("red heart ", q.text)
        assertFalse(q.inWord)
    }

    @Test
    fun aSwipeOnAnEmptyQueryStartsIt() {
        val q = EmojiQuery()
        q.beginSwipe()
        assertTrue(q.inWord)
        q.decoded("Pizza")
        assertEquals("pizza", q.text)
    }

    @Test
    fun aKeyOutsideTheGesturesEndsTheWordInHand() {
        val q = EmojiQuery()
        q.beginSwipe()
        q.decoded("caff")
        q.typed("è")
        assertFalse(q.inWord)
        q.decoded("caffe")
        assertEquals("caffè", q.text)
    }

    @Test
    fun backspaceEndsTheWordInHand() {
        val q = EmojiQuery()
        q.beginSwipe()
        q.decoded("heart")
        q.backspace()
        q.decoded("heart")
        assertEquals("hear", q.text)
    }

    @Test
    fun backspaceTakesAWholeCharacterOutsideTheBasicPlane() {
        val q = EmojiQuery()
        q.tapped("a"); q.tapped("𝒜")
        q.backspace()
        assertEquals("a", q.text)
    }

    @Test
    fun theQueryIsCapped() {
        val q = EmojiQuery()
        repeat(EmojiQuery.MAX_LEN + 10) { q.tapped("a") }
        assertEquals(EmojiQuery.MAX_LEN, q.text.length)
        q.space()
        q.beginSwipe()
        q.decoded("x".repeat(100))
        assertEquals(EmojiQuery.MAX_LEN, q.text.length)
    }
}
