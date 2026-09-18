package com.kinetica.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The block gesture is a step past the bottom of the existing weight slide, so
 * the whole safety of it is arithmetic: a user pushing a word to zero must not
 * fall into a block, and a user who keeps pushing must land on one.
 */
class BarAdjustTest {

    @Test
    fun deltaMatchesTheShippedSlideArithmetic() {
        // Release in place is one step up, so upward travel starts at +2.
        assertEquals(1, BarAdjust.delta(0, 1))
        assertEquals(2, BarAdjust.delta(1, 1))
        assertEquals(5, BarAdjust.delta(0, 5))
        assertEquals(-1, BarAdjust.delta(-1, 1))
        assertEquals(-10, BarAdjust.delta(-2, 5))
    }

    @Test
    fun theCountFloorsAtZeroAsTheStoreFloorsIt() {
        assertEquals(0, BarAdjust.effectiveCount(3, -9, 1))
        assertEquals(0, BarAdjust.effectiveCount(0, -1, 1))
        assertEquals(1, BarAdjust.effectiveCount(3, -2, 1))
    }

    @Test
    fun reachingZeroDoesNotBlock() {
        // The step that zeroes a word must not also block it, or every
        // de-reinforce to nothing would become a block.
        for (count in 0..12) {
            for (inc in listOf(1, 5, 10)) {
                val zeroing = (0 downTo -40).first {
                    BarAdjust.effectiveCount(count, it, inc) == 0
                }
                assertFalse(
                    "count=$count inc=$inc blocked at the zeroing step $zeroing",
                    BarAdjust.blockArmed(count, zeroing, inc),
                )
                assertTrue(
                    "count=$count inc=$inc did not block one step past $zeroing",
                    BarAdjust.blockArmed(count, zeroing - 1, inc),
                )
            }
        }
    }

    @Test
    fun upwardTravelNeverBlocks() {
        for (steps in 0..20) {
            assertFalse(BarAdjust.blockArmed(0, steps, 1))
            assertFalse(BarAdjust.blockArmed(7, steps, 5))
        }
    }

    @Test
    fun aWordWithNoPersonalWeightStillNeedsDeliberateTravel() {
        // The common case: a junk suggestion the user has never picked, so
        // there is no weight to take away first. It must still take more than
        // one step, or a stray downward slide would blacklist it.
        assertEquals(BarAdjust.MIN_BLOCK_STEPS, BarAdjust.blockStep(0, 1))
        assertFalse(BarAdjust.blockArmed(0, 0, 1))
        assertFalse("one step down must not block", BarAdjust.blockArmed(0, -1, 1))
        assertTrue(BarAdjust.blockArmed(0, -2, 1))
    }

    @Test
    fun theStepFloorAppliesAtEveryIncrement() {
        // A large increment zeroes a word in one step, so the floor is what
        // keeps the block deliberate there too.
        for (inc in listOf(1, 5, 10)) {
            assertFalse(BarAdjust.blockArmed(1, -1, inc))
            assertTrue(BarAdjust.blockArmed(1, -2, inc))
        }
    }

    @Test
    fun clampStopsTheSlideAtTheBlockingStep() {
        assertEquals(BarAdjust.MIN_BLOCK_STEPS, BarAdjust.clampSteps(0, -8, 1))
        assertEquals(-4, BarAdjust.clampSteps(3, -30, 1))
        // Upward travel is not clamped here; the tier scale bounds it.
        assertEquals(9, BarAdjust.clampSteps(3, 9, 1))
    }

    @Test
    fun aZeroOrNegativeIncrementCannotDivideByZero() {
        assertEquals(BarAdjust.MIN_BLOCK_STEPS, BarAdjust.blockStep(0, 0))
        assertEquals(-4, BarAdjust.blockStep(3, 0))
        assertTrue(BarAdjust.blockArmed(0, -2, 0))
    }
}
