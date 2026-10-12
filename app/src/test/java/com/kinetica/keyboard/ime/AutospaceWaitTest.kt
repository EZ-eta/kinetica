package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The autospace delay is counted from the thumb's lift.
 *
 * Counted from the moment the decode lands, a 10 ms setting would wait 10 ms plus the decode,
 * tens of milliseconds more, and the slider's low end would say less than it does.
 */
class AutospaceWaitTest {

    @Test
    fun theDecodeTimeComesOffTheWait() {
        assertEquals(260L, autospaceWait(delay = 300L, liftAt = 1_000L, now = 1_040L))
    }

    @Test
    fun aDecodeSlowerThanTheDelaySpacesAtOnce() {
        assertEquals(0L, autospaceWait(delay = 10L, liftAt = 1_000L, now = 1_069L))
    }

    @Test
    fun anUnknownOrFutureLiftWaitsTheWholeDelay() {
        assertEquals(300L, autospaceWait(delay = 300L, liftAt = -1L, now = 5L))
        assertEquals(300L, autospaceWait(delay = 300L, liftAt = 2_000L, now = 1_000L))
    }
}
