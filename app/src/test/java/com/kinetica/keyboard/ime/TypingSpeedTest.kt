package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The spacebar's burst meter: characters over five per minute, a pause empties it. */
class TypingSpeedTest {

    /** Four-letter words, each drawn in 600 ms with 600 ms between: 5 chars per 1.2 s = 50 wpm. */
    private fun steady(meter: TypingSpeed, words: Int, from: Long = 0L): Float? {
        var shown: Float? = null
        for (i in 0 until words) {
            val start = from + i * 1_200L
            shown = meter.logWord(start, start + 600L, letters = 4)
        }
        return shown
    }

    @Test
    fun aSteadyPaceReadsItsOwnSpeed() {
        val meter = TypingSpeed()
        val shown = steady(meter, words = 8)
        // The span runs from the first word's first touch to the last word's last one, so
        // it is short of the words' full cycle by one gap: 40 chars over 9.0 s = 53.3.
        assertEquals(53.3f, meter.rate()!!, 0.1f)
        assertNotNull(shown)
        assertTrue(shown!! in 50f..56f)
    }

    @Test
    fun oneWordShowsNothing() {
        val meter = TypingSpeed()
        assertNull(meter.logWord(0L, 400L, letters = 3))
        assertNull(meter.display)
    }

    @Test
    fun aPauseStartsANewBurstInsteadOfDraggingTheNumberDown() {
        val meter = TypingSpeed()
        steady(meter, words = 6)
        val before = meter.rate()!!
        // Eight seconds away, then the same pace again: longer than a pause, shorter than
        // the window, so only the reset keeps the gap out. Counted, it would put the reading
        // near 30 wpm; a short burst reads a little high instead, since its span misses a
        // larger share of one gap.
        val after = steady(meter, words = 3, from = 6 * 1_200L + 8_000L)!!
        assertTrue("$before -> $after", after in 0.9f * before..1.3f * before)
    }

    @Test
    fun oldWordsFallOutOfTheWindow() {
        val meter = TypingSpeed()
        // Slow words first, then fast ones for longer than the window.
        for (i in 0 until 4) meter.logWord(i * 3_000L, i * 3_000L + 2_000L, letters = 4)
        val slow = meter.rate()!!
        var t = 4 * 3_000L
        repeat(30) {
            meter.logWord(t, t + 300L, letters = 4)
            t += 600L
        }
        assertTrue("fast $slow -> ${meter.rate()}", meter.rate()!! > 2 * slow)
    }

    @Test
    fun theShownNumberMovesHalfWayToEachReading() {
        val meter = TypingSpeed()
        meter.logWord(0L, 600L, 4)
        val first = meter.logWord(1_200L, 1_800L, 4)!!
        val reading = meter.rate()!!
        assertEquals(reading, first, 0.01f)
        // A much slower word: the shown number moves half way, not all the way.
        val shown = meter.logWord(1_800L + 3_500L, 1_800L + 3_500L + 2_000L, 4)!!
        val newReading = meter.rate()!!
        assertEquals(first + TypingSpeed.EASING * (newReading - first), shown, 0.01f)
    }
}
