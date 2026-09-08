package com.kinetica.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The joins in a smoothed trail.
 *
 * The thing that can go wrong is a gap: if one piece does not end where the next begins,
 * a fast swipe draws as a dotted line rather than a curve. That is the invariant here, and
 * it is reachable from the JVM because the arithmetic carries no Canvas.
 */
class TrailPathTest {

    /** A path with a corner in it, so a straight-line renderer and this one differ. */
    private val xs = floatArrayOf(0f, 10f, 20f, 20f, 30f)
    private val ys = floatArrayOf(0f, 0f, 0f, 10f, 20f)

    private fun quadFor(i: Int): FloatArray {
        val out = FloatArray(TrailPath.SIZE)
        val last = xs.size - 1
        TrailPath.quadInto(
            out,
            xs[i - 1], ys[i - 1], xs[i], ys[i],
            if (i < last) xs[i + 1] else xs[i],
            if (i < last) ys[i + 1] else ys[i],
            isFirst = i == 1,
            isLast = i == last,
        )
        return out
    }

    @Test
    fun consecutivePiecesShareAnEndpoint() {
        for (i in 1 until xs.size - 1) {
            val a = quadFor(i)
            val b = quadFor(i + 1)
            assertEquals("piece $i endX", a[4], b[0], 0f)
            assertEquals("piece $i endY", a[5], b[1], 0f)
        }
    }

    @Test
    fun theFirstPieceStartsAtTheTrailsOwnTail() {
        val first = quadFor(1)
        assertEquals(xs[0], first[0], 0f)
        assertEquals(ys[0], first[1], 0f)
    }

    @Test
    fun theLastPieceEndsUnderTheFinger() {
        val last = xs.size - 1
        val piece = quadFor(last)
        assertEquals(xs[last], piece[4], 0f)
        assertEquals(ys[last], piece[5], 0f)
    }

    @Test
    fun theSampleIsTheControlPointNotACorner() {
        // What makes it a curve: the piece bends THROUGH the sample and neither of its
        // endpoints is the sample, except at the two ends of the run.
        val middle = quadFor(2)
        assertEquals(xs[2], middle[2], 0f)
        assertEquals(ys[2], middle[3], 0f)
        // start is mid(10,0)-(20,0), end is mid(20,0)-(20,10).
        assertEquals(15f, middle[0], 0f)
        assertEquals(0f, middle[1], 0f)
        assertEquals(20f, middle[4], 0f)
        assertEquals(5f, middle[5], 0f)
    }

    @Test
    fun aRunOfOneSegmentIsJustTheLine() {
        // Both ends anchored, so a two-sample trail is drawn exactly where it used to be.
        val out = FloatArray(TrailPath.SIZE)
        TrailPath.quadInto(out, 1f, 2f, 3f, 4f, 3f, 4f, isFirst = true, isLast = true)
        assertEquals(1f, out[0], 0f)
        assertEquals(2f, out[1], 0f)
        assertEquals(3f, out[4], 0f)
        assertEquals(4f, out[5], 0f)
    }
}
