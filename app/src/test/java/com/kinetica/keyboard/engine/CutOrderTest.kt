package com.kinetica.keyboard.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order the in-search cut resumes a tail in, against the order the finished-sequence
 * generators use (`MergeAlternatives.orderByTime`): a gesture starting at an instant goes
 * before a piece resuming at it, because the cut was placed at that start.
 */
class CutOrderTest {

    @Test
    fun aTokenStartingAtTheCutGoesBetweenTheHalves() {
        // Every cut at another token's own start ties: a swipe's first contact enters at its
        // tStart, and cutCandidates offers that instant. Reading tail-first there spells the
        // swipe whole and the other thumb after it, which no generator order would build.
        assertFalse("the token starting at the cut must come first", tailResumesFirst(1_000, 1_000))
    }

    @Test
    fun aTokenStartingEarlierStillGoesFirstAndALaterOneWaits() {
        assertFalse(tailResumesFirst(nextStart = 900, cutAt = 1_000))
        assertTrue(tailResumesFirst(nextStart = 1_100, cutAt = 1_000))
    }
}
