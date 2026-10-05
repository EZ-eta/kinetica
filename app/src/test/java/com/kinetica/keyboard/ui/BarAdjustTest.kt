package com.kinetica.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The block gesture is a step past the bottom of the weight slide, so its safety is
 * arithmetic: a user pushing a word to zero must not fall into a block, and a user who keeps
 * pushing must land on one. Each step is one badge tier, so a heavy word reaches it too.
 */
class BarAdjustTest {

    @Test
    fun eachStepIsOneBadgeTier() {
        // By design: down halves, up doubles what a release in place gives.
        assertEquals(305, BarAdjust.effectiveCount(300, 0, 5))
        assertEquals(610, BarAdjust.effectiveCount(300, 1, 5))
        assertEquals(150, BarAdjust.effectiveCount(300, -1, 5))
        assertEquals(75, BarAdjust.effectiveCount(300, -2, 5))
        assertEquals(10, BarAdjust.effectiveCount(0, 1, 5))
        assertEquals(-150, BarAdjust.delta(300, -1, 5))
        assertEquals(5, BarAdjust.delta(0, 0, 5))
    }

    @Test
    fun aHeavyWordReachesTheBlockWithinOneSlide() {
        // The report: a full-height slide moved a heavy word three places and never reached the
        // block. Linear steps of 5 needed 61 steps for a count of 300; a tier per step needs 10.
        assertEquals(-10, BarAdjust.blockStep(300))
        assertEquals(-15, BarAdjust.blockStep(10_000))
        for (count in listOf(2, 50, 300, 4_000, 10_000)) {
            assertTrue("count=$count", -BarAdjust.blockStep(count) <= 15)
        }
    }

    @Test
    fun theCountFloorsAtZeroAsTheStoreFloorsIt() {
        assertEquals(0, BarAdjust.effectiveCount(3, -9, 1))
        assertEquals(0, BarAdjust.effectiveCount(0, -1, 1))
        assertEquals(0, BarAdjust.effectiveCount(3, -40, 1))
        assertEquals(1, BarAdjust.effectiveCount(3, -1, 1))
    }

    @Test
    fun upwardStepsStopAtTheCap() {
        assertEquals(BarAdjust.MAX_COUNT, BarAdjust.effectiveCount(3_000, 5, 5))
        assertEquals(BarAdjust.MAX_COUNT, BarAdjust.effectiveCount(9_999, 12, 10))
    }

    @Test
    fun reachingZeroDoesNotBlock() {
        // The step that zeroes a word must not also block it, or every
        // de-reinforce to nothing would become a block.
        for (count in listOf(0, 1, 2, 3, 7, 8, 64, 300, 9_999)) {
            val zeroing = (0 downTo -40).first { BarAdjust.effectiveCount(count, it, 5) == 0 }
            if (count > 0) {
                assertFalse("count=$count blocked at the zeroing step $zeroing", BarAdjust.blockArmed(count, zeroing))
            }
            val block = minOf(zeroing - 1, BarAdjust.MIN_BLOCK_STEPS)
            assertTrue("count=$count did not block at $block", BarAdjust.blockArmed(count, block))
            assertFalse("count=$count blocked before $block", BarAdjust.blockArmed(count, block + 1))
        }
    }

    @Test
    fun upwardTravelNeverBlocks() {
        for (steps in 0..20) {
            assertFalse(BarAdjust.blockArmed(0, steps))
            assertFalse(BarAdjust.blockArmed(7, steps))
        }
    }

    @Test
    fun aWordWithNoPersonalWeightStillNeedsDeliberateTravel() {
        // The common case: a junk suggestion the user has never picked, so
        // there is no weight to take away first. It must still take more than
        // one step, or a stray downward slide would blacklist it.
        assertEquals(BarAdjust.MIN_BLOCK_STEPS, BarAdjust.blockStep(0))
        assertFalse(BarAdjust.blockArmed(0, 0))
        assertFalse("one step down must not block", BarAdjust.blockArmed(0, -1))
        assertTrue(BarAdjust.blockArmed(0, -2))
        // A word at 1 zeroes in one step, so the floor is what keeps its block deliberate.
        assertFalse(BarAdjust.blockArmed(1, -1))
        assertTrue(BarAdjust.blockArmed(1, -2))
    }

    @Test
    fun clampStopsTheSlideAtTheBlockingStep() {
        assertEquals(BarAdjust.MIN_BLOCK_STEPS, BarAdjust.clampSteps(0, -8))
        assertEquals(-3, BarAdjust.clampSteps(3, -30))
        assertEquals(9, BarAdjust.clampSteps(3, 9))
        assertEquals(12, BarAdjust.clampSteps(3, 40))
    }

    @Test
    fun aRecentColumnSwapIsVerticalAndPastItsTravel() {
        // A column's swap cancels the pending arm, so the slide and the swap never
        // read the same travel. Sideways or short travel is neither.
        assertTrue(BarAdjust.recentSwap(dx = 2f, dy = 20f, swipePx = 16f))
        assertTrue(BarAdjust.recentSwap(dx = 2f, dy = -20f, swipePx = 16f))
        assertFalse(BarAdjust.recentSwap(dx = 2f, dy = 10f, swipePx = 16f))
        assertFalse(BarAdjust.recentSwap(dx = 30f, dy = 20f, swipePx = 16f))
    }

    // ---- A deliberate tap on the correction strip must not be eaten ---------------
    //
    // The arm fires at 450ms for any zone in either mode. A user aiming at a word to swap in
    // is slower than that, so on the strip a lift with no slide must swap, not reinforce.

    @Test
    fun aHoldThatNeverSlidHandsTheLiftBackInCorrectionMode() {
        assertFalse(BarAdjust.appliesOnLift(correctionMode = true, steps = 0))
    }

    @Test
    fun aSlideStillAdjustsInCorrectionMode() {
        assertTrue(BarAdjust.appliesOnLift(correctionMode = true, steps = 1))
        assertTrue(BarAdjust.appliesOnLift(correctionMode = true, steps = -1))
        // The block step is the far end of a downward slide and must stay reachable
        // from the strip, where a junk word is easiest to spot.
        assertTrue(BarAdjust.appliesOnLift(correctionMode = true, steps = BarAdjust.MIN_BLOCK_STEPS))
        assertTrue(BarAdjust.blockArmed(count = 0, steps = BarAdjust.MIN_BLOCK_STEPS))
    }

    @Test
    fun compositionModeKeepsThePlainLongPressReinforce() {
        // The original behaviour, kept in this mode: +1 on release in place, with no slide.
        assertTrue(BarAdjust.appliesOnLift(correctionMode = false, steps = 0))
        assertEquals(1, BarAdjust.delta(0, 0, 1))
    }

    @Test
    fun ranksShowOnlyOnTheWordBeingSlid() {
        // A clean bar by default; the setting off brings every rank back.
        assertFalse(BarAdjust.badgeShown(whileAdjusting = true, adjustingThis = false))
        assertTrue(BarAdjust.badgeShown(whileAdjusting = true, adjustingThis = true))
        assertTrue(BarAdjust.badgeShown(whileAdjusting = false, adjustingThis = false))
    }

    @Test
    fun onlyTheCommittedWordOfARecentColumnSlides() {
        // Three rows per column: the word it beat above, the committed word, the next below.
        val rows = 3
        assertTrue(BarAdjust.recentCellAdjustable(1, rows))
        assertTrue(BarAdjust.recentCellAdjustable(4, rows))
        for (cell in listOf(0, 2, 3, 5, -1)) assertFalse("cell $cell", BarAdjust.recentCellAdjustable(cell, rows))
    }
}
