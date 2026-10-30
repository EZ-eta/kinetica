package com.kinetica.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The keys a swipe crosses: lit, fading, lit again, and gone with the layout. */
class KeyWakeTest {

    private fun wake(keys: Int = 30) = KeyWake().apply { resize(keys) }

    @Test
    fun aCrossedKeyFadesToNothingByTheFadeTime() {
        val w = wake()
        w.mark(4, 120f, 1_000)
        assertEquals(1f, w.lightAt(4, 1_000), 0f)
        assertEquals(0.5f, w.lightAt(4, 1_000 + KeyWake.FADE_MS / 2), 1e-4f)
        assertEquals(0f, w.lightAt(4, 1_000 + KeyWake.FADE_MS), 0f)
        assertEquals(0f, w.lightAt(5, 1_000), 0f)
    }

    @Test
    fun aKeyCrossedAgainStartsOver() {
        // `level`: the l is crossed twice; the second crossing must not finish the first's fade.
        val w = wake()
        w.mark(8, 0f, 1_000)
        w.mark(8, 30f, 1_300)
        assertEquals(1f, w.lightAt(8, 1_300), 0f)
        assertTrue(w.lightAt(8, 1_000 + KeyWake.FADE_MS) > 0f)
        assertEquals(30f, w.hueOf(8), 0f)
    }

    @Test
    fun eachKeyKeepsTheHueItWasCrossedIn() {
        // Two thumbs, one on each half, each in its own hue; and rainbow steps per key.
        val w = wake()
        w.mark(1, 0f, 1_000)
        w.mark(20, 60f, 1_010)
        w.mark(2, 30f, 1_050)
        assertEquals(0f, w.hueOf(1), 0f)
        assertEquals(60f, w.hueOf(20), 0f)
        assertEquals(30f, w.hueOf(2), 0f)
    }

    @Test
    fun anyLitOnlyWhileAKeyIsLit() {
        val w = wake()
        assertFalse(w.anyLit(1_000))
        w.mark(3, 0f, 1_000)
        w.mark(9, 0f, 1_200)
        assertTrue(w.anyLit(1_000 + KeyWake.FADE_MS))
        assertFalse(w.anyLit(1_200 + KeyWake.FADE_MS))
    }

    @Test
    fun aNewLayoutClearsWhatWasLit() {
        val w = wake(30)
        w.mark(3, 0f, 1_000)
        w.resize(30)
        assertEquals(0f, w.lightAt(3, 1_000), 0f)
        assertFalse(w.anyLit(1_000))
        w.resize(10)
        w.mark(25, 0f, 1_000)
        assertFalse("a key past the layout lights nothing", w.anyLit(1_000))
    }

    @Test
    fun theEdgeNarrowsAsItFades() {
        assertEquals(KeyWake.WIDTH_LIT_DP, KeyWake.widthDp(1f), 0f)
        assertEquals(KeyWake.WIDTH_FADED_DP, KeyWake.widthDp(0f), 0f)
        assertTrue(KeyWake.widthDp(0.5f) < KeyWake.WIDTH_LIT_DP)
    }

    @Test
    fun theLookDefaultsToTheTrail() {
        assertEquals(SwipeLook.TRAIL, SwipeLook.fromPref(null))
        assertEquals(SwipeLook.TRAIL, SwipeLook.fromPref("glitter"))
        assertEquals(SwipeLook.KEYS, SwipeLook.fromPref("keys"))
        assertEquals(SwipeLook.BOTH, SwipeLook.fromPref("both"))
        assertFalse(SwipeLook.KEYS.drawsTrail)
        assertTrue(SwipeLook.BOTH.drawsTrail && SwipeLook.BOTH.drawsKeys)
    }
}
