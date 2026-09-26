package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How long an undecodable buffer stays open once the swipe delay can go to 10 ms (#2).
 *
 * The timeout was twice the swipe delay with nothing under it, which was safe while the
 * delay could not go below 100 ms. At 10 ms it would close a word typed in pieces 20 ms
 * after every piece, and the next piece would start a new word (item 29 in reverse).
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
