package com.kinetica.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The size picture stays inside the settings screen. Pixel 7: 1080 x 2400, a portrait keyboard
 * of about 1 000 px, the settings 1 600 px wide sideways.
 */
class SizePreviewFitTest {

    private val band = 84f

    @Test
    fun thePortraitPictureOnALandscapeScreenIsCappedAtHalfItsHeight() {
        val maxH = 1080f * SizePreviewFit.MAX_SCREEN_SHARE
        val w = SizePreviewFit.widthPx(1500f, 1080f, 1000f, band, maxH)
        assertTrue("not narrowed: $w", w < 1500f)
        assertEquals(maxH, SizePreviewFit.heightPx(w, 1080f, 1000f, band), 0.5f)
    }

    @Test
    fun aPictureThatFitsKeepsTheFullWidth() {
        // The landscape phone, drawn upright: short, so nothing to narrow.
        val maxH = 2400f * SizePreviewFit.MAX_SCREEN_SHARE
        assertEquals(1000f, SizePreviewFit.widthPx(1000f, 2400f, 500f, band, maxH), 0f)
    }

    @Test
    fun narrowingKeepsTheKeyboardsProportions() {
        val maxH = 600f
        val w = SizePreviewFit.widthPx(2000f, 1080f, 1000f, band, maxH)
        // Width over keyboard height is the phone's, the band aside.
        assertEquals(1080f / 1000f, w / (SizePreviewFit.heightPx(w, 1080f, 1000f, band) - band), 1e-3f)
    }

    @Test
    fun aDegenerateSpecDrawsAtTheViewWidth() {
        assertEquals(800f, SizePreviewFit.widthPx(800f, 0f, 1000f, band, 500f), 0f)
        assertEquals(0f, SizePreviewFit.widthPx(800f, 1080f, 1000f, band, 10f), 0f)
    }
}
