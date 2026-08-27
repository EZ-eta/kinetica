package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.Dwell
import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.TapToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dwell detection in [GestureStream] (see MergeAlternatives, which consumes
 * dwells as a secondary cut source). A run of samples that stays within
 * DWELL_RADIUS_KW of the run's first sample for at least DWELL_MIN_MS is a
 * pause - the signal that this thumb stopped producing letters while the other
 * one carried on.
 *
 * The first test of GestureStream in the project: the class is engine-pure
 * (kw geometry, primitive callbacks) so it runs on the JVM like the rest of the
 * engine. Detection is all this class does, so these tests are the whole
 * automated gate for the detector's semantics; the *values* of the two
 * thresholds are a device question (see the class KDoc constants).
 */
class GestureStreamDwellTest {

    private val g = TestData.qwertyGeometry()

    /**
     * Drives one stream in pixel space (kw * KEY_W), tracking the live position
     * so legs and rests can be chained the way a real pointer produces them.
     */
    private inner class Pen(c: Char, t0: Long) {
        private val downCode = c - 'a'
        var x = g.centerX(downCode) * TestData.KEY_W
            private set
        var y = g.centerY(downCode) * TestData.KEY_W
            private set
        var t = t0
            private set
        val stream = GestureStream(
            StreamId.LEFT, 0, g, TAP_DISP_KW, x, y, t0, downCode,
        ) { }

        /** Straight travel to [c]'s center in [steps] samples over [durMs]. */
        fun travelTo(c: Char, durMs: Long, steps: Int = 10): Pen {
            val code = c - 'a'
            return travelBy(
                g.centerX(code) * TestData.KEY_W - x,
                g.centerY(code) * TestData.KEY_W - y,
                durMs, steps,
            )
        }

        /** Straight travel by a pixel delta - lets a test pick an exact speed. */
        fun travelBy(dxPx: Float, dyPx: Float, durMs: Long, steps: Int): Pen {
            val x0 = x
            val y0 = y
            val t0 = t
            for (k in 1..steps) {
                val f = k / steps.toFloat()
                x = x0 + f * dxPx
                y = y0 + f * dyPx
                t = t0 + durMs * k / steps
                stream.addPoint(x, y, t)
            }
            return this
        }

        /**
         * Stationary samples at the current point across [durMs]: a parked thumb
         * keeps receiving timestamped samples for as long as the other thumb
         * moves (KeyboardView.handleMove feeds every pointer of every event).
         */
        fun rest(durMs: Long, samples: Int = 10): Pen {
            val t0 = t
            for (k in 1..samples) {
                t = t0 + durMs * k / samples
                stream.addPoint(x, y, t)
            }
            return this
        }

        fun lift(): InputToken = stream.finish(t)

        fun dwells(): List<Dwell> = (lift() as SwipeToken).dwells
    }

    private fun pen(c: Char, t0: Long = 0L) = Pen(c, t0)

    @Test
    fun steadyTravelHasNoDwells() {
        // q->w->e at ~10 kw/s: the run breaks every 0.3 kw, long before any run
        // reaches DWELL_MIN_MS.
        val dwells = pen('q').travelTo('w', 100).travelTo('e', 100).dwells()
        assertEquals("clean swipe must not segment: $dwells", 0, dwells.size)
    }

    @Test
    fun restBetweenLegsIsOneDwell() {
        // Travel, park 200 ms, resume. The contract is that the dwell COVERS the
        // rest; its exact endpoints may bleed up to DWELL_RADIUS_KW into the
        // adjoining legs, which is harmless (both sides still land on the rest
        // key, well inside the matcher's endpoint radius).
        val p = pen('q').travelTo('w', 100)
        val restStart = p.t
        p.rest(200)
        val restEnd = p.t
        val dwells = p.travelTo('e', 100).dwells()

        assertEquals("expected exactly one dwell, got $dwells", 1, dwells.size)
        val d = dwells[0]
        assertTrue("dwell must start at or before the rest ($d, rest $restStart)", d.tEnter <= restStart)
        assertTrue("dwell must end at or after the rest ($d, rest $restEnd)", d.tExit >= restEnd)
        assertTrue("dwell must span >= DWELL_MIN_MS ($d)", d.tExit - d.tEnter >= KineticaConstants.DWELL_MIN_MS)
        assertTrue("dwell must cover >= 2 samples ($d)", d.exitIdx > d.enterIdx)
    }

    @Test
    fun shortPauseIsNotDwell() {
        // 100 ms park: under the threshold even after the radius bleed adds the
        // tail of the approach leg (~30 ms here), so no boundary is claimed.
        val dwells = pen('q').travelTo('w', 100).rest(100).travelTo('e', 100).dwells()
        assertEquals("sub-threshold pause must not segment: $dwells", 0, dwells.size)
    }

    @Test
    fun slowSteadyTravelIsNotDwell() {
        // 1.0 kw over 400 ms = 2.5 kw/s, just above the 2.0 kw/s dwell boundary
        // (DWELL_RADIUS_KW / DWELL_MIN_MS): each run crosses the radius in
        // ~120 ms and is discarded. This is the radius/threshold interaction, so
        // it must hold with fine sampling too.
        val dwells = pen('q').travelBy(TestData.KEY_W, 0f, 400, steps = 40).dwells()
        assertEquals("2.5 kw/s travel must not segment: $dwells", 0, dwells.size)
    }

    @Test
    fun creepBelowTheSpeedBoundaryCountsAsDwell() {
        // Documented, deliberate: at 1.25 kw/s (0.5 kw over 400 ms) the pointer
        // takes longer than DWELL_MIN_MS to leave the radius, so this IS a dwell.
        // A thumb covering a third of a key in 150 ms is producing no letter, so
        // treating it as a pause is the intent, not a leak.
        val dwells = pen('q')
            .travelBy(0.5f * TestData.KEY_W, 0f, 400, steps = 40)
            .travelTo('e', 100)
            .dwells()
        assertTrue("1.25 kw/s creep must register a dwell", dwells.isNotEmpty())
    }

    @Test
    fun overflowKeepsTheLongestDwellsInTimeOrder() {
        // Five pauses, two of them clearly shorter. Only MAX_DWELL_SEGMENTS - 1
        // survive, and they must stay in time order so path slicing can consume
        // them directly.
        val p = pen('q')
        for ((key, restMs) in listOf('w' to 500L, 'e' to 200L, 'r' to 800L, 't' to 180L, 'y' to 600L)) {
            p.travelTo(key, 100).rest(restMs)
        }
        val dwells = p.travelTo('u', 100).dwells()

        assertEquals("cap is MAX_DWELL_SEGMENTS - 1: $dwells", KineticaConstants.MAX_DWELL_SEGMENTS - 1, dwells.size)
        for (i in 1 until dwells.size) {
            assertTrue("dwells must stay in time order: $dwells", dwells[i].tEnter > dwells[i - 1].tEnter)
        }
        // The 200 ms and 180 ms pauses are the ones dropped.
        for (d in dwells) {
            assertTrue("short pause survived the cap: $d in $dwells", d.tExit - d.tEnter >= 400)
        }
    }

    @Test
    fun trailingRestBeforeLiftIsNotADwell() {
        // A rest with no following leg is not a boundary - there is nothing to
        // separate it from. finish() deliberately leaves the open run unclosed.
        val dwells = pen('q').travelTo('w', 100).rest(400).dwells()
        assertEquals("rest before lift must not segment: $dwells", 0, dwells.size)
    }

    @Test
    fun squaredTapThresholdKeepsBoundaryClassification() {
        assertTrue(
            "one ULP below the threshold must remain a tap",
            tokenAtDisplacement(java.lang.Math.nextDown(TAP_DISP_KW)) is TapToken,
        )
        assertTrue(
            "the threshold itself commits a swipe",
            tokenAtDisplacement(TAP_DISP_KW) is SwipeToken,
        )
        assertTrue(
            "one ULP above the threshold must remain a swipe",
            tokenAtDisplacement(java.lang.Math.nextUp(TAP_DISP_KW)) is SwipeToken,
        )
    }

    private fun tokenAtDisplacement(distanceKw: Float): InputToken {
        // Unit key width and a zero origin avoid pixel conversion rounding, so
        // this pins the exact float boundary used by GestureStream.
        val geometry = KeyboardGeometry.fromPx(
            1f, 3f,
            listOf(floatArrayOf(-1f, -1f, 2f, 2f)),
            intArrayOf(0),
        )
        val stream = GestureStream(
            StreamId.LEFT, 0, geometry, TAP_DISP_KW,
            0f, 0f, 0L, 0,
        ) { }
        stream.addPoint(distanceKw, 0f, 10L)
        return stream.finish(10L)
    }

    @Test
    fun dwellTrackingLeavesTapClassificationAlone() {
        // A long stationary press is still a tap (classification is
        // displacement-only); dwell bookkeeping must not perturb that path.
        val token = pen('q').rest(300).lift()
        assertTrue("stationary press must stay a tap, was $token", token is TapToken)
        assertTrue("300 ms press must flag longPress", (token as TapToken).longPress)
    }

    private companion object {
        // Mirrors the shipping conversion of TAP_MAX_DISP_DP at typical density
        // closely enough for classification; only the swipe/tap split uses it.
        const val TAP_DISP_KW = 0.5f
    }
}
