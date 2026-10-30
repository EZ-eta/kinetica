package com.kinetica.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The line round each key: off, scaled by density, and kept off a narrow key's label. */
class KeyBorderTest {

    @Test
    fun offIsNoLine() {
        assertEquals(0f, KeyBorder.widthPx(0, 2.75f, 80f), 0f)
    }

    @Test
    fun theWidthScalesWithDensity() {
        // A 29 dp portrait key at 2.75 px/dp: 4 dp is under the cap.
        assertEquals(11f, KeyBorder.widthPx(4, 2.75f, 29f * 2.75f), 1e-4f)
        assertEquals(2.75f, KeyBorder.widthPx(1, 2.75f, 29f * 2.75f), 1e-4f)
    }

    @Test
    fun aNarrowKeyCapsTheLine() {
        // The 19 dp landscape key: 4 dp asked, under 3 dp drawn.
        val side = 19f * 2.75f
        val w = KeyBorder.widthPx(4, 2.75f, side)
        assertEquals(side * KeyBorder.MAX_SHARE, w, 1e-4f)
        assert(w < 3f * 2.75f)
    }

    @Test
    fun beforeLayoutTheWidthIsUncapped() {
        assertEquals(8f, KeyBorder.widthPx(4, 2f, 0f), 0f)
    }

    @Test
    fun theLineSitsInsideTheKey() {
        assertEquals(1.5f, KeyBorder.inset(3f), 0f)
    }
}
