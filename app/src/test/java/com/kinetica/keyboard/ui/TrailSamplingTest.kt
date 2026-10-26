package com.kinetica.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How many points a swipe trail lays.
 *
 * Timed from each sample, a one-colour trail sampled every 4 ms kept one point at the finger and
 * drew nothing; rainbow hid it, since its hue change per key forces a point.
 */
class TrailSamplingTest {

    /** Points a one-colour trail lays over a swipe sampled every [stepMs]. */
    private fun pointsLaid(durationMs: Long, stepMs: Long): Int {
        val sampling = TrailSampling(8L)
        var points = 0
        var t = 0L
        while (t <= durationMs) {
            if (sampling.lays(0, t, forced = false, sameHue = true, hasLast = points > 0)) points++
            t += stepMs
        }
        return points
    }

    @Test
    fun aOneColourSwipeSampledDenselyHasLength() {
        // 69 samples 4 ms apart over 296 ms, a swipe as the touch history delivers it: one point
        // per 8 ms.
        assertEquals(38, pointsLaid(296L, 4L))
    }

    @Test
    fun samplesFurtherApartEachLayAPoint() {
        assertEquals(31, pointsLaid(300L, 10L))
    }

    @Test
    fun aHueChangeOrABreakLaysAPoint() {
        val sampling = TrailSampling(8L)
        assertTrue(sampling.lays(0, 100L, forced = false, sameHue = true, hasLast = false))
        assertFalse(sampling.lays(0, 101L, forced = false, sameHue = true, hasLast = true))
        // Rainbow steps the hue per key, and a lift breaks the run.
        assertTrue(sampling.lays(0, 102L, forced = false, sameHue = false, hasLast = true))
        assertTrue(sampling.lays(0, 103L, forced = true, sameHue = true, hasLast = true))
    }

    @Test
    fun theThumbsAreTimedApart() {
        val sampling = TrailSampling(8L)
        assertTrue(sampling.lays(0, 100L, forced = false, sameHue = true, hasLast = false))
        assertTrue(sampling.lays(1, 104L, forced = false, sameHue = true, hasLast = false))
        assertFalse(sampling.lays(0, 106L, forced = false, sameHue = true, hasLast = true))
        assertTrue(sampling.lays(0, 108L, forced = false, sameHue = true, hasLast = true))
    }
}
