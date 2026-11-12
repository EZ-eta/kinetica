package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.Dwell
import com.kinetica.keyboard.engine.models.PathPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a stroke resumed past the apostrophe key starts its word: the first turn or pause. */
class LeadInTest {

    /** Points along [vs], [step] kw apart, 8 ms each. */
    private fun path(vararg vs: Pair<Float, Float>, step: Float = 0.1f): List<PathPoint> {
        val out = ArrayList<PathPoint>()
        out.add(PathPoint(vs[0].first, vs[0].second, 0L))
        for (s in 0 until vs.size - 1) {
            val (x0, y0) = vs[s]
            val (x1, y1) = vs[s + 1]
            val len = kotlin.math.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
            val n = (len / step).toInt().coerceAtLeast(1)
            for (k in 1..n) out.add(PathPoint(x0 + (x1 - x0) * k / n, y0 + (y1 - y0) * k / n, 8L * out.size))
        }
        return out
    }

    @Test
    fun aStraightRunGivesNoSign() {
        assertEquals(0, LeadIn.end(path(9f to 1f, 1f to 1f), emptyList()))
    }

    @Test
    fun theWordStartsWhereTheThumbTurns() {
        // Left 4 kw, then straight up: the turn is at (5, 1), sample 40.
        val p = path(9f to 1f, 5f to 1f, 5f to -1f)
        val i = LeadIn.end(p, emptyList())
        assertTrue("$i", i in 39..41)
    }

    @Test
    fun aGentleBendIsALetterOnTheWay() {
        // 30 degrees: under the turn the rule asks for.
        val p = path(9f to 1f, 5f to 1f, 1f to -1.31f)
        assertEquals(0, LeadIn.end(p, emptyList()))
    }

    @Test
    fun aPauseOnAStraightRunMarksTheStart() {
        val p = path(9f to 1f, 1f to 1f)
        assertEquals(30, LeadIn.end(p, listOf(Dwell(30, 34, 240L, 400L))))
        // A pause still on the key's edge is the thumb leaving it, not a letter.
        assertEquals(0, LeadIn.end(p, listOf(Dwell(2, 6, 16L, 180L))))
    }

    @Test
    fun theEarlierOfATurnAndAPauseWins() {
        val p = path(9f to 1f, 5f to 1f, 5f to -1f)
        assertEquals(20, LeadIn.end(p, listOf(Dwell(20, 24, 160L, 320L))))
        val i = LeadIn.end(p, listOf(Dwell(45, 48, 360L, 520L)))
        assertTrue("$i", i in 39..41)
    }
}
