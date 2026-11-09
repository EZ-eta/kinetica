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
    fun aTapUnderASwipeThatLandedFirstSeesTheSwipeHeld() {
        // `I`, its space, then `noticed` with the right thumb swiping n-o-i while the left
        // tapped. The tap lifted first, under a swipe that began the word.
        assertTrue(down(1, 'n', 0))
        val (x, y) = at('i')
        engine.onPointerMove(1, x, y, 100)
        assertTrue(down(0, 't', 150))
        up(0, 't', 160)
        assertTrue(engine.swipeHeldSince(tokens.single().tStart))
    }

    @Test
    fun aTapThatLandedFirstOrUnderAHeldTapSeesNoSwipe() {
        // The tap landed before the swipe: the tap begins the word.
        assertTrue(down(0, 'p', 0))
        assertTrue(down(1, 'e', 50))
        val (x, y) = at('t')
        engine.onPointerMove(1, x, y, 100)
        up(0, 'p', 120)
        assertFalse(engine.swipeHeldSince(tokens.last().tStart))
        up(1, 't', 200)
        // A held tap is not a swipe, so `car`, then `p` held under `e`, still continues `car`.
        assertTrue(down(1, 'p', 300))
        assertTrue(down(0, 'e', 320))
        up(0, 'e', 340)
        assertFalse(engine.swipeHeldSince(tokens.last().tStart))
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

    @Test
    fun aTouchOnNoKeyGoesToTheNearestLetterWhenTheViewHasNoKeyThere() {
        // The home row starts half a key in: x 20 is on no key, nearest `a`.
        assertFalse("without the snap the touch is refused", engine.onPointerDown(0, 20f, 225f, 0))
        assertTrue(engine.onPointerDown(0, 20f, 225f, 0, snap = true))
        engine.onPointerUp(0, 20f, 225f, 40)
        val tap = tokens.single() as TapToken
        assertEquals('a' - 'a', tap.code)
        // The touch point is kept for the fuzzy pass, not moved to the key.
        assertEquals(0.2f, tap.x, 1e-4f)
    }

    @Test
    fun aTouchBelowTheLettersIsNeverSnapped() {
        // Under the bottom row: the space row or the padding, not a letter.
        assertFalse(engine.onPointerDown(0, 400f, 470f, 0, snap = true))
        assertEquals(-1, g.letterNear(4f, 4.7f))
    }

    @Test
    fun aTouchInTheSplitGapGoesToItsNearerHalf() {
        // The right half moved 200 px out, as the portrait split opens a gap between g and h.
        val rows = listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f)
        val rects = ArrayList<FloatArray>()
        val codes = ArrayList<Int>()
        for ((r, row) in rows.withIndex()) {
            for ((i, ch) in row.first.withIndex()) {
                val shift = if (ch in "yuiophjklbnm") 200f else 0f
                val left = (row.second + i) * keyW + shift
                rects.add(floatArrayOf(left, r * 150f, left + keyW, r * 150f + 150f))
                codes.add(ch - 'a')
            }
        }
        val split = KeyboardGeometry.fromPx(keyW, 600f, rects, codes.toIntArray())
        // Home row: g ends at 550, h starts at 750.
        assertEquals(-1, split.keyAt(6.0f, 2.25f))
        assertEquals('g' - 'a', split.letterNear(6.0f, 2.25f))
        assertEquals('h' - 'a', split.letterNear(7.2f, 2.25f))
        // A key under the touch is always the one, even at a corner nearer the centre above.
        assertEquals('j' - 'a', split.letterNear(9.0f, 2.25f))
        assertEquals('h' - 'a', split.letterNear(7.52f, 1.52f))
    }
}
