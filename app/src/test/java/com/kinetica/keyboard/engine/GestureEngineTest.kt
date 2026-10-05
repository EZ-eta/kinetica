package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.TapToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How pointers become streams. Characterization: pins the assignment two-thumb decoding rests on. */
class GestureEngineTest {

    private val keyW = 100f

    /** QWERTY with the stream midline at the board's centre, 500 px. */
    private val g: KeyboardGeometry = run {
        val rows = listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f)
        val rects = ArrayList<FloatArray>()
        val codes = ArrayList<Int>()
        for ((r, row) in rows.withIndex()) {
            for ((i, ch) in row.first.withIndex()) {
                val left = (row.second + i) * keyW
                rects.add(floatArrayOf(left, r * 150f, left + keyW, r * 150f + 150f))
                codes.add(ch - 'a')
            }
        }
        KeyboardGeometry.fromPx(keyW, 500f, rects, codes.toIntArray())
    }

    private val tokens = ArrayList<InputToken>()
    private val engine = GestureEngine(object : GestureEngine.Listener {
        override fun onTokenFinalized(token: InputToken) { tokens.add(token) }
        override fun onKeyTransition(streamId: StreamId, code: Int) {}
        override fun onAllPointersUp() {}
    }).also {
        it.maxPointers = 2
        it.setGeometry(g, 12f)
    }

    /** Centre of [ch]'s key in px. */
    private fun at(ch: Char): Pair<Float, Float> {
        val code = ch - 'a'
        return g.centerX(code) * keyW to g.centerY(code) * keyW
    }

    private fun down(id: Int, ch: Char, t: Long): Boolean {
        val (x, y) = at(ch)
        return engine.onPointerDown(id, x, y, t)
    }

    private fun up(id: Int, ch: Char, t: Long) {
        val (x, y) = at(ch)
        engine.onPointerUp(id, x, y, t)
    }

    @Test
    fun aThumbIsLabelledBySideOfTheMidlineWhereItLands() {
        assertTrue(down(0, 'a', 0))
        assertTrue(down(1, 'l', 10))
        assertEquals(StreamId.LEFT, engine.streamIdOf(0))
        assertEquals(StreamId.RIGHT, engine.streamIdOf(1))
    }

    @Test
    fun aSecondPointerOnTheSameSideTakesTheOtherSlot() {
        // A stream is a pointer slot, not a hand: two touches left of the midline still
        // become LEFT and RIGHT.
        assertTrue(down(0, 'a', 0))
        assertTrue(down(1, 's', 10))
        assertEquals(StreamId.LEFT, engine.streamIdOf(0))
        assertEquals(StreamId.RIGHT, engine.streamIdOf(1))
    }

    @Test
    fun aThirdPointerIsRefused() {
        assertTrue(down(0, 'a', 0))
        assertTrue(down(1, 'l', 10))
        assertFalse(down(2, 'g', 20))
        assertFalse(engine.isTracking(2))
    }

    @Test
    fun aCancelledPointerProducesNoToken() {
        assertTrue(down(0, 'a', 0))
        engine.cancelPointer(0)
        assertFalse(engine.hasActivePointers())
        up(0, 'a', 50)
        assertTrue(tokens.isEmpty())
    }

    @Test
    fun aReusedPointerIdStartsAFreshStream() {
        assertTrue(down(0, 'a', 0))
        up(0, 'a', 40)
        assertTrue(down(0, 'l', 100))
        up(0, 'l', 140)
        assertEquals(2, tokens.size)
        assertEquals('a' - 'a', (tokens[0] as TapToken).code)
        assertEquals('l' - 'a', (tokens[1] as TapToken).code)
        assertEquals(100L, tokens[1].tStart)
    }

    @Test
    fun aLandingNextToTheOtherThumbIsTracedWithItsDistance() {
        val lines = ArrayList<String>()
        DecodeTrace.sink = { lines.add(it) }
        try {
            assertTrue(down(0, 'f', 0))
            assertTrue(down(1, 'g', 10))
            up(1, 'g', 60)
        } finally {
            DecodeTrace.sink = null
        }
        assertTrue("down line in $lines", lines.any { it.startsWith("pointer down stream=RIGHT other=LEFT dist=1.00kw") })
        assertTrue("up line in $lines", lines.any { it.startsWith("pointer up stream=RIGHT other=LEFT") })
    }

    @Test
    fun aSampleThatJumpsAcrossTheBoardIsTraced() {
        val lines = ArrayList<String>()
        DecodeTrace.sink = { lines.add(it) }
        try {
            assertTrue(down(0, 'a', 0))
            val (x, y) = at('l')
            engine.onPointerMove(0, x, y, 4)
        } finally {
            DecodeTrace.sink = null
        }
        assertTrue("jump line in $lines", lines.any { it.startsWith("pointer jump stream=LEFT step=8.00kw dt=4ms") })
    }
}
