package com.kinetica.keyboard.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cut each orientation draws: chosen in a frame of the keyboard's shape, kept inside the image. */
class BackdropCropTest {

    private val w = 2400
    private val h = 1800

    private fun aspectOf(f: FloatArray) = (f[2] - f[0]) / (f[3] - f[1])

    private fun inside(f: FloatArray) =
        f[0] >= -0.01f && f[1] >= -0.01f && f[2] <= w + 0.01f && f[3] <= h + 0.01f

    @Test
    fun aCutSurvivesStorage() {
        val cut = floatArrayOf(0.1f, 0.25f, 0.9f, 0.5f)
        assertArrayEquals(cut, BackdropCrop.parse(BackdropCrop.format(cut)), 1e-4f)
    }

    @Test
    fun aBrokenCutIsNone() {
        for (s in listOf(null, "", "0.1,0.2,0.3", "a,b,c,d", "0.5,0.1,0.4,0.9", "0,0,1.5,1", "0,0.5,1,0.5")) {
            assertNull(s, BackdropCrop.parse(s))
        }
    }

    @Test
    fun theFrameOpensAsLargeAsTheImageHolds() {
        // A landscape keyboard, 4:1, on a 4:3 photo: full width, a centred band.
        val f = BackdropCrop.largest(w, h, 4f)
        assertArrayEquals(floatArrayOf(0f, 600f, 2400f, 1200f), f, 0.01f)
        // A portrait keyboard, 1.2:1: full height.
        val p = BackdropCrop.largest(w, h, 1.2f)
        assertEquals(1800f, p[3] - p[1], 0.01f)
        assertEquals(1.2f, aspectOf(p), 1e-4f)
    }

    @Test
    fun aDraggedFrameStaysOnThePicture() {
        val f = BackdropCrop.largest(w, h, 4f)
        assertArrayEquals(floatArrayOf(0f, 1200f, 2400f, 1800f), BackdropCrop.moved(f, 500f, 5_000f, w, h), 0.01f)
        assertArrayEquals(floatArrayOf(0f, 0f, 2400f, 600f), BackdropCrop.moved(f, -500f, -5_000f, w, h), 0.01f)
    }

    @Test
    fun aPinchKeepsTheKeyboardsShape() {
        val f = BackdropCrop.largest(w, h, 1.2f)
        for (factor in listOf(0.01f, 0.5f, 0.9f, 1.3f, 10f)) {
            val s = BackdropCrop.scaled(f, factor, w, h)
            assertEquals("factor $factor", 1.2f, aspectOf(s), 1e-3f)
            assertTrue("factor $factor", inside(s))
            assertTrue("factor $factor", s[2] - s[0] >= w * BackdropCrop.MIN_WIDTH_FRACTION - 0.01f)
        }
        // Halved about its centre.
        val half = BackdropCrop.scaled(f, 0.5f, w, h)
        assertEquals((f[0] + f[2]) / 2f, (half[0] + half[2]) / 2f, 0.01f)
        assertEquals((f[2] - f[0]) / 2f, half[2] - half[0], 0.01f)
    }

    @Test
    fun theKeyboardDrawsTheCutItWasGiven() {
        val cut = floatArrayOf(0.25f, 0.5f, 0.75f, 0.625f)  // 1200 x 225 px, 5.33:1
        val r = BackdropCrop.source(cut, w, h, 1200, 225)
        assertArrayEquals(intArrayOf(600, 900, 1800, 1125), r)
    }

    @Test
    fun aResizedKeyboardKeepsTheCutsCentre() {
        // The keyboard dragged taller after the cut: the cut is trimmed at its sides, not moved.
        val cut = floatArrayOf(0.25f, 0.5f, 0.75f, 0.625f)
        val r = BackdropCrop.source(cut, w, h, 1200, 450)
        assertEquals(2400, (r[0] + r[2]))
        assertEquals(2025, (r[1] + r[3]))
        assertEquals(1200f / 450f, (r[2] - r[0]).toFloat() / (r[3] - r[1]), 0.02f)
        assertTrue(r[0] >= 600 && r[2] <= 1800 && r[1] >= 900 && r[3] <= 1125)
    }

    @Test
    fun noCutFillsFromTheCentre() {
        assertArrayEquals(BackdropFit.cropRect(w, h, 1080, 900), BackdropCrop.source(null, w, h, 1080, 900))
    }

    @Test
    fun theScreenReopensOnTheStoredCut() {
        val cut = floatArrayOf(0f, 0f, 0.5f, 0.25f)  // 1200 x 450, 2.67:1
        val f = BackdropCrop.opening(cut, w, h, 1200f / 450f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1200f, 450f), f, 1f)
        assertArrayEquals(BackdropCrop.largest(w, h, 2f), BackdropCrop.opening(null, w, h, 2f), 0.01f)
    }
}
