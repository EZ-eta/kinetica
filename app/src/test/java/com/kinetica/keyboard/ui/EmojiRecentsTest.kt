package com.kinetica.keyboard.ui

import com.kinetica.keyboard.ui.EmojiRecents.Use
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiRecentsTest {

    @Test
    fun ordersByCountNotByRecency() {
        // The daily emoji is the older one. A recency ordering would put the
        // one-off first, which is the mistake this ordering exists to avoid.
        val daily = Use("😀", count = 40, updatedAt = 1_000L)
        val onceEver = Use("🦄", count = 1, updatedAt = 9_000L)
        assertEquals(listOf(daily.emoji, onceEver.emoji), EmojiRecents.ordered(listOf(onceEver, daily)))
    }

    @Test
    fun recencyBreaksACountTie() {
        val older = Use("🍕", count = 5, updatedAt = 1_000L)
        val newer = Use("🍔", count = 5, updatedAt = 2_000L)
        assertEquals(listOf(newer.emoji, older.emoji), EmojiRecents.ordered(listOf(older, newer)))
    }

    @Test
    fun spellingBreaksAFullTieSoTheOrderIsStable() {
        val a = Use("☀", count = 3, updatedAt = 7L)
        val b = Use("☁", count = 3, updatedAt = 7L)
        assertEquals(EmojiRecents.ordered(listOf(a, b)), EmojiRecents.ordered(listOf(b, a)))
    }

    @Test
    fun countsAtOrBelowZeroAreDropped() {
        val used = Use("👍", count = 2, updatedAt = 5L)
        val spent = Use("👎", count = 0, updatedAt = 9L)
        assertEquals(listOf(used.emoji), EmojiRecents.ordered(listOf(used, spent)))
    }

    @Test
    fun theCapIsThreeFullRows() {
        assertEquals(0, EmojiRecents.MAX % 8)
        val many = (1..60).map { Use("e$it", count = it, updatedAt = it.toLong()) }
        assertEquals(EmojiRecents.MAX, EmojiRecents.ordered(many).size)
        // Best first: the highest count survives the cap, the lowest does not.
        assertEquals("e60", EmojiRecents.ordered(many).first())
        assertTrue(EmojiRecents.ordered(many).none { it == "e1" })
    }

    @Test
    fun anEmptyStoreYieldsAnEmptyTab() {
        assertEquals(emptyList<String>(), EmojiRecents.ordered(emptyList()))
    }

    @Test
    fun aMultiCodepointEmojiSurvivesWhole() {
        // U+2708 U+FE0F - the reason the store is keyed on the string and not
        // on a single Int codepoint.
        val plane = "✈️"
        assertEquals(listOf(plane), EmojiRecents.ordered(listOf(Use(plane, 1, 1L))))
    }
}
