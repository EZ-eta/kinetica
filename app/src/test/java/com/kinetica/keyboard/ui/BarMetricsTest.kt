package com.kinetica.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The suggestion bar's ornament scaling. Two things worth locking: that the
 * shipped 44dp rendering did not move when the sizes became relative, and that
 * the furniture still fits at the floor - the reason the change exists.
 */
class BarMetricsTest {

    private val densities = listOf(1f, 1.5f, 2f, 2.625f, 3f, 3.5f, 4f)

    @Test
    fun theShippedHeightIsUnchangedToThePixel() {
        // scale == 1 at the reference height, at every density, so every
        // `dp * density * scale` is exactly the `dp * density` it replaced.
        for (d in densities) {
            val h = BarMetrics.REFERENCE_DP * d
            assertEquals("density $d", 1f, BarMetrics.scale(h, d), 1e-6f)
            for (dp in listOf(1.2f, 1.5f, 3.2f, 3.5f, 4f, 6f, 8f)) {
                assertEquals(
                    "dp $dp at density $d",
                    dp * d, BarMetrics.ornament(dp, h, d), 1e-4f,
                )
            }
        }
    }

    @Test
    fun ornamentsShrinkWithTheBarAndGrowWithIt() {
        val d = 2.625f
        val ref = BarMetrics.REFERENCE_DP * d
        val badgeRef = BarMetrics.ornament(3.2f, ref, d)
        assertTrue(BarMetrics.ornament(3.2f, BarMetrics.MIN_DP * d, d) < badgeRef)
        assertTrue(BarMetrics.ornament(3.2f, BarMetrics.MAX_DP * d, d) > badgeRef)
        // Proportional, not merely monotone: half the bar is half the ornament.
        assertEquals(badgeRef / 2f, BarMetrics.ornament(3.2f, ref / 2f, d), 1e-4f)
    }

    @Test
    fun everyOrnamentKeepsAConstantRatioToTheWordText() {
        // This is the property the scaling exists for, and the one a fixed dp size
        // does not have: the badge is the word's own annotation, so it must read at
        // the same weight whatever the bar height.
        val d = 2.625f
        val ratios = HashMap<Float, MutableList<Float>>()
        for (dp in listOf(BarMetrics.MIN_DP, 32f, BarMetrics.REFERENCE_DP, 60f, BarMetrics.MAX_DP)) {
            val h = dp * d
            for (orn in listOf(1.2f, 3.2f, 4f, 6f, 8f)) {
                val r = BarMetrics.ornament(orn, h, d) / BarMetrics.textSize(h)
                ratios.getOrPut(orn) { ArrayList() }.add(r)
            }
        }
        for ((orn, rs) in ratios) {
            for (r in rs) {
                assertEquals("ornament $orn drifts against the text", rs[0], r, 1e-4f)
            }
        }
    }

    @Test
    fun theFurnitureFitsInsideTheBarAtEveryHeight() {
        // An invariant rather than a regression: solving the geometry says a fixed
        // ornament never clipped either, so this locks the property against a
        // future change to the fractions rather than recording a fixed bug.
        for (d in densities) {
            for (dp in listOf(BarMetrics.MIN_DP, BarMetrics.REFERENCE_DP, BarMetrics.MAX_DP)) {
                val h = dp * d
                val cy = h - BarMetrics.ornament(3.5f, h, d)
                val r = BarMetrics.ornament(1.5f, h, d)
                assertTrue("page dots clip the bottom at ${dp}dp/$d", cy + r <= h)
                val textBottom = h / 2f + BarMetrics.textSize(h) / 2f
                assertTrue("page dots reach the word at ${dp}dp/$d", cy - r >= textBottom - 1f)
                val badge = BarMetrics.ornament(3.2f, h, d) + BarMetrics.ornament(1.2f, h, d)
                assertTrue("badge taller than half the bar at ${dp}dp/$d", badge < h / 2f)
            }
        }
    }

    @Test
    fun textIsAConstantFractionOfTheBar() {
        for (dp in listOf(BarMetrics.MIN_DP, BarMetrics.REFERENCE_DP, BarMetrics.MAX_DP)) {
            val h = dp * 2.625f
            assertEquals(h * BarMetrics.TEXT_FRACTION, BarMetrics.textSize(h), 1e-4f)
        }
    }

    @Test
    fun degenerateHeightsDoNotProduceNonsense() {
        // A view measured at zero before first layout must not yield NaN or a
        // negative ornament that Canvas would draw as a filled quadrant.
        assertEquals(0f, BarMetrics.scale(0f, 2.625f), 1e-6f)
        assertEquals(1f, BarMetrics.scale(100f, 0f), 1e-6f)
        assertTrue(BarMetrics.ornament(3.2f, 0f, 2.625f) >= 0f)
        assertTrue(BarMetrics.textSize(0f) >= 0f)
    }

    @Test
    fun theFloorAndCeilingBracketTheDefault() {
        assertTrue(BarMetrics.MIN_DP < BarMetrics.REFERENCE_DP)
        assertTrue(BarMetrics.REFERENCE_DP < BarMetrics.MAX_DP)
        // The settings slider is authored against these; keep them in step.
        assertEquals(44f, BarMetrics.REFERENCE_DP, 1e-6f)
        assertEquals(24f, BarMetrics.MIN_DP, 1e-6f)
        assertEquals(72f, BarMetrics.MAX_DP, 1e-6f)
    }
}
