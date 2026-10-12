package com.kinetica.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The suggestion bar's ornament scaling: the 44dp reference renders as it did before sizes
 * became relative, and the furniture still fits at the floor.
 */
class BarMetricsTest {

    private val densities = listOf(1f, 1.5f, 2f, 2.625f, 3f, 3.5f, 4f)

    @Test
    fun theShippedHeightIsUnchangedToThePixel() {
        // scale == 1 at the reference height, at every density, so every
        // `dp * density * scale` equals the `dp * density` it replaced.
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
        // The property a fixed dp size lacks: the badge is the word's own annotation, so it
        // must read at the same weight whatever the bar height.
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
    fun theTextSliderIsTheSettableRange() {
        val xml = com.kinetica.keyboard.settings.PrefsXml.read()
        val row = Regex("""android:key="pref_bar_text_pct"[^>]*>""").find(xml)?.value
        requireNotNull(row) { "pref_bar_text_pct not in keyboard_prefs.xml" }
        assertTrue(row, row.contains("android:max=\"${BarMetrics.TEXT_PCT_MAX}\""))
        assertTrue(row, row.contains("app:min=\"${BarMetrics.TEXT_PCT_MIN}\""))
        assertTrue(row, row.contains("android:defaultValue=\"${BarMetrics.TEXT_PCT_DEFAULT}\""))
    }

    @Test
    fun theLargestTextStillClearsThePageDots() {
        val scale = BarMetrics.textScale(BarMetrics.TEXT_PCT_MAX)
        for (d in densities) {
            for (dp in listOf(BarMetrics.MIN_DP, BarMetrics.REFERENCE_DP, BarMetrics.MAX_DP)) {
                val h = dp * d
                val dotTop = h - BarMetrics.ornament(3.5f, h, d) - BarMetrics.ornament(1.5f, h, d)
                val textBottom = h / 2f + BarMetrics.textSize(h, scale) / 2f
                assertTrue("text reaches the dots at ${dp}dp/$d", dotTop >= textBottom - 1f)
            }
        }
        // 150 by default: large enough to read at arm's length.
        assertEquals(1.5f, BarMetrics.textScale(BarMetrics.TEXT_PCT_DEFAULT), 0f)
        assertEquals(BarMetrics.TEXT_PCT_MAX / 100f, BarMetrics.textScale(400), 0f)
        assertEquals(BarMetrics.TEXT_PCT_MIN / 100f, BarMetrics.textScale(0), 0f)
    }

    @Test
    fun theFurnitureFitsInsideTheBarAtEveryHeight() {
        // An invariant, not a regression: a fixed ornament never clipped either, so this
        // guards against a future change to the fractions.
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

    @Test
    fun retypeDpClampsToTheSettableRange() {
        assertEquals(BarMetrics.RETYPE_MIN_DP, BarMetrics.retypeDp(0))
        assertEquals(BarMetrics.RETYPE_MIN_DP, BarMetrics.retypeDp(BarMetrics.RETYPE_MIN_DP - 1))
        assertEquals(BarMetrics.RETYPE_MAX_DP, BarMetrics.retypeDp(9999))
        assertEquals(BarMetrics.RETYPE_MAX_DP, BarMetrics.retypeDp(BarMetrics.RETYPE_MAX_DP + 1))
        assertEquals(34, BarMetrics.retypeDp(34))
    }

    @Test
    fun theRetypeDefaultIsTheWidthTheButtonShippedAt() {
        // The slider must not change anyone's bar until they move it: the user
        // who asked for the button asked for it small.
        assertEquals(34, BarMetrics.RETYPE_DEFAULT_DP)
        assertEquals(BarMetrics.RETYPE_DEFAULT_DP, BarMetrics.retypeDp(BarMetrics.RETYPE_DEFAULT_DP))
    }

    @Test
    fun theRetypeRangeStaysInsideTheViewsOwnQuarterWidthCap() {
        // The view caps the button at a quarter of the bar regardless, so the
        // ceiling only has to be sane on a narrow phone: 72dp is under a
        // quarter of any keyboard wider than 288dp.
        assertTrue(BarMetrics.RETYPE_MAX_DP * 4 <= 288)
        assertTrue(BarMetrics.RETYPE_MIN_DP < BarMetrics.RETYPE_DEFAULT_DP)
        assertTrue(BarMetrics.RETYPE_DEFAULT_DP < BarMetrics.RETYPE_MAX_DP)
    }

    @Test
    fun theRecentColumnsHaveTheirOwnTextSize() {
        // Apart from the live words: the largest suggestion text no longer moves them.
        assertEquals(1.5f, BarMetrics.recentTextScale(BarMetrics.RECENT_PCT_DEFAULT), 0f)
        assertEquals(BarMetrics.RECENT_PCT_MAX / 100f, BarMetrics.recentTextScale(400), 0f)
        assertEquals(BarMetrics.RECENT_PCT_MIN / 100f, BarMetrics.recentTextScale(0), 0f)
        val xml = com.kinetica.keyboard.settings.PrefsXml.read()
        val row = Regex("""android:key="pref_recent_text_pct"[^>]*>""").find(xml)?.value
        requireNotNull(row) { "pref_recent_text_pct not in keyboard_prefs.xml" }
        assertTrue(row, row.contains("android:max=\"${BarMetrics.RECENT_PCT_MAX}\""))
        assertTrue(row, row.contains("app:min=\"${BarMetrics.RECENT_PCT_MIN}\""))
        assertTrue(row, row.contains("android:defaultValue=\"${BarMetrics.RECENT_PCT_DEFAULT}\""))
    }

    @Test
    fun theLargestRecentTextKeepsItsRowsApart() {
        // A row is a third of the bar; the text is TEXT_FRACTION of it times the view's 1.25, and
        // a font box is about 1.172 of the size. At the ceiling three boxes still fit their rows.
        val size = BarMetrics.textSize(1f, BarMetrics.recentTextScale(BarMetrics.RECENT_PCT_MAX)) *
            BarMetrics.RECENT_TEXT_SCALE
        assertTrue("font box ${size * 1.172f} of a row", size * 1.172f <= 1f)
    }

    @Test
    fun compactSuggestionsAreOnByDefault() {
        assertTrue(com.kinetica.keyboard.settings.Prefs.DEFAULT_BAR_COMPACT)
        val xml = com.kinetica.keyboard.settings.PrefsXml.read()
        val row = Regex("""android:key="pref_bar_compact"[^>]*>""").find(xml)?.value
        requireNotNull(row) { "pref_bar_compact not in keyboard_prefs.xml" }
        assertTrue(row, row.contains("android:defaultValue=\"true\""))
    }
}
