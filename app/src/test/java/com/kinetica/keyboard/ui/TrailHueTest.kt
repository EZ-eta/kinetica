package com.kinetica.keyboard.ui

import com.kinetica.keyboard.engine.models.StreamId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The trail's hue per colour setting.
 *
 * With the step on for every choice, a red or a custom trail read as rainbow by the
 * third key.
 */
class TrailHueTest {

    /** The hues one stream is drawn in over a swipe through [keys] keys. */
    private fun swipe(base: Float, stream: StreamId, cycles: Boolean, keys: Int): List<Float> {
        var hue = TrailHue.start(base, stream, cycles)
        val out = mutableListOf(hue)
        repeat(keys) {
            hue = TrailHue.next(hue, cycles)
            out += hue
        }
        return out
    }

    @Test
    fun aChosenColourKeepsItsHueThroughASwipe() {
        // Green, nine keys: `keyboards` drawn edge to edge.
        assertEquals(List(10) { 120f }, swipe(120f, StreamId.LEFT, cycles = false, keys = 9))
    }

    @Test
    fun aChosenColourIsTheSameForBothThumbs() {
        assertEquals(275f, TrailHue.start(275f, StreamId.RIGHT, cycles = false), 0f)
        assertEquals(275f, TrailHue.start(275f, StreamId.LEFT, cycles = false), 0f)
    }

    @Test
    fun rainbowStepsPerKeyAndWraps() {
        assertEquals(
            listOf(0f, 30f, 60f, 90f, 120f, 150f, 180f, 210f, 240f, 270f, 300f, 330f, 0f),
            swipe(0f, StreamId.LEFT, cycles = true, keys = 12),
        )
    }

    @Test
    fun rainbowStartsTheRightThumbApart() {
        // Two trails at once stay told apart by colour as well as by side.
        assertEquals(60f, TrailHue.start(0f, StreamId.RIGHT, cycles = true), 0f)
        assertEquals(0f, TrailHue.start(0f, StreamId.LEFT, cycles = true), 0f)
    }
}
