package com.kinetica.keyboard.settings

import com.kinetica.keyboard.engine.KineticaConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keyboard height bounds, under test for the first time. The invariant in
 * [minNeverExceedsMax] is the one that matters historically: an inverted range
 * fed to `coerceIn` threw and killed the whole app process the moment the
 * keyboard opened.
 */
class KeyboardHeightsTest {

    // Pixel 7: 1080x2400 at density 2.625. The developer's device.
    private val phoneH = 2400
    private val phoneD = 2.625f

    // An unfolded foldable, held wide: short in pixels, so the dp floor is what
    // binds there. This is the reporter's shape.
    private val foldH = 1840
    private val foldD = 2.625f

    @Test
    fun minNeverExceedsMax() {
        // Swept over every screen height and density a real device or a
        // split-screen window can present, including absurdly short ones.
        for (h in listOf(0, 100, 320, 480, 640, 800, 1080, 1600, 1840, 2400, 3200)) {
            for (d in listOf(1f, 1.5f, 2f, 2.625f, 3.5f, 4f)) {
                val min = KeyboardHeights.minPx(h, d)
                val max = KeyboardHeights.maxPx(h)
                assertTrue("min $min > max $max at h=$h d=$d", min <= max)
                // And the range coerceIn actually receives must contain its result.
                for (pct in Prefs.MIN_HEIGHT_PCT..Prefs.MAX_HEIGHT_PCT) {
                    val px = KeyboardHeights.targetPx(h, d, pct)
                    assertTrue("target $px outside [$min,$max] at h=$h d=$d pct=$pct",
                        px in min..max)
                }
            }
        }
    }

    @Test
    fun theFloorIsTheLARGERofTwoAndEitherCanBind() {
        // On a tall phone the dp floor decides: 96dp * 2.625 = 252px against
        // 10% of 2400 = 240px.
        assertEquals(252, KeyboardHeights.minPx(phoneH, phoneD))
        // On the shorter foldable display it decides by more: 252 against 184.
        assertEquals(252, KeyboardHeights.minPx(foldH, foldD))
        // At density 1 the percentage takes over: 96px against 240px.
        assertEquals(240, KeyboardHeights.minPx(phoneH, 1f))
    }

    @Test
    fun theFloorFellWhereTheReportSaidItWasStuck() {
        // The report is that 25% was still too tall, on a device where 180dp was
        // what actually bound. Both floors moved, so the reachable minimum has to
        // have dropped on that geometry - and by a lot, which is what was asked.
        val nowPct = 100f * KeyboardHeights.minPx(foldH, foldD) / foldH
        assertTrue("floor is $nowPct% of screen", nowPct < 15f)
        val oldFloorPx = minOf(
            maxOf((180f * foldD).toInt(), foldH * 25 / 100),
            KeyboardHeights.maxPx(foldH),
        )
        assertTrue(
            "floor must have dropped from $oldFloorPx to ${KeyboardHeights.minPx(foldH, foldD)}",
            KeyboardHeights.minPx(foldH, foldD) < oldFloorPx,
        )
    }

    @Test
    fun theDpFloorKeepsARowAboveTheTapDisplacementTolerance() {
        // Why 96dp and not a rounder number: four rows of 24dp, and 24dp is twice
        // TAP_MAX_DISP_DP, so a tap starting on a key centre reaches its row edge
        // and no further. Below that a legal tap can leave the row it began in.
        val rowDp = KeyboardHeights.MIN_KEYBOARD_DP / 4f
        assertEquals(2f * KineticaConstants.TAP_MAX_DISP_DP, rowDp, 1e-4f)
    }

    @Test
    fun targetHonoursThePercentageBetweenTheBounds() {
        // 35% of 2400 = 840, comfortably inside [252, 1200].
        assertEquals(840, KeyboardHeights.targetPx(phoneH, phoneD, 35))
        // Below the floor it clamps up, above the ceiling it clamps down.
        assertEquals(252, KeyboardHeights.targetPx(phoneH, phoneD, 10))
        assertEquals(1200, KeyboardHeights.targetPx(phoneH, phoneD, 50))
    }

    @Test
    fun pctForIsTheInverseAndStaysInsideTheSliderRange() {
        assertEquals(35, KeyboardHeights.pctFor(840, phoneH))
        assertEquals(Prefs.MIN_HEIGHT_PCT, KeyboardHeights.pctFor(1, phoneH))
        assertEquals(Prefs.MAX_HEIGHT_PCT, KeyboardHeights.pctFor(phoneH, phoneH))
        // A zero-height screen is a rotation race, not a reason to divide by it.
        assertEquals(Prefs.DEFAULT_HEIGHT_PCT, KeyboardHeights.pctFor(500, 0))
    }

    // ---------------------------------------------------------- handle height

    @Test
    fun anUpgradeKeepsTheHandleTheUserAlreadyHad() {
        // Nothing written yet: the pre-1.0.4 switch decides, both ways.
        assertEquals(KeyboardHeights.MAX_HANDLE_DP, KeyboardHeights.handleDp(null, legacyHandleOn = true))
        assertEquals(0, KeyboardHeights.handleDp(null, legacyHandleOn = false))
    }

    @Test
    fun aWrittenHeightOverridesTheOldSwitch() {
        assertEquals(8, KeyboardHeights.handleDp(8, legacyHandleOn = false))
        assertEquals(8, KeyboardHeights.handleDp(8, legacyHandleOn = true))
    }

    @Test
    fun zeroIsTheOffStateAndIsReachable() {
        assertEquals(0, KeyboardHeights.handleDp(0, legacyHandleOn = true))
    }

    @Test
    fun theHeightIsHeldInsideItsBounds() {
        assertEquals(0, KeyboardHeights.handleDp(-5, legacyHandleOn = true))
        assertEquals(KeyboardHeights.MAX_HANDLE_DP, KeyboardHeights.handleDp(999, legacyHandleOn = false))
    }
}
