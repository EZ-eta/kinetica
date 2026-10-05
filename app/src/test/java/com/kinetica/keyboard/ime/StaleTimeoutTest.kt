package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How long an undecodable buffer stays open now the swipe delay can go to 10 ms (#2).
 * Twice the delay with no floor would close a word typed in pieces 20 ms after each piece,
 * and the next piece would start a new word.
 */
class StaleTimeoutTest {

    @Test
    fun everySettingReachableBeforeKeepsItsTimeout() {
        assertEquals(200L, staleTimeoutMs(100))
        assertEquals(600L, staleTimeoutMs(300))
        assertEquals(1600L, staleTimeoutMs(800))
    }

    @Test
    fun theNewLowSettingsStopAtTheOldMinimumsTimeout() {
        assertEquals(STALE_TIMEOUT_FLOOR_MS, staleTimeoutMs(10))
        assertEquals(STALE_TIMEOUT_FLOOR_MS, staleTimeoutMs(50))
        assertEquals(220L, staleTimeoutMs(110))
    }
}
