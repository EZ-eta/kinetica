package com.kinetica.keyboard.ui

import com.kinetica.keyboard.settings.KeyboardConfig
import com.kinetica.keyboard.settings.MapPrefs
import com.kinetica.keyboard.settings.Prefs
import com.kinetica.keyboard.settings.PrefsXml
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The background image fills the keyboard uncut where it can, unstretched, the right way up. */
class BackdropFitTest {

    @Test
    fun aWidePhotoLosesItsSidesOnATallKeyboard() {
        // 4000 x 3000 under a 1080 x 900 portrait keyboard: full height, the middle 3600.
        assertArrayEquals(intArrayOf(200, 0, 3800, 3000), BackdropFit.cropRect(4000, 3000, 1080, 900))
    }

    @Test
    fun aTallPhotoLosesTopAndBottomOnALandscapeKeyboard() {
        // 1080 x 2400 under a 2400 x 600 landscape keyboard: full width, a band of 270.
        assertArrayEquals(intArrayOf(0, 1065, 1080, 1335), BackdropFit.cropRect(1080, 2400, 2400, 600))
    }

    @Test
    fun theCropKeepsTheKeyboardsShape() {
        for ((src, dst) in listOf(
            (4000 to 3000) to (1080 to 900), (1080 to 2400) to (2400 to 600),
            (500 to 500) to (1080 to 700), (3000 to 4000) to (2400 to 1000),
        )) {
            val r = BackdropFit.cropRect(src.first, src.second, dst.first, dst.second)
            val w = r[2] - r[0]
            val h = r[3] - r[1]
            assertTrue("inside the image", r[0] >= 0 && r[1] >= 0 && r[2] <= src.first && r[3] <= src.second)
            assertEquals("$src on $dst", dst.first.toFloat() / dst.second, w.toFloat() / h, 0.01f)
            // Centred: equal cuts on both sides, to the pixel.
            assertTrue(kotlin.math.abs(r[0] - (src.first - r[2])) <= 1)
            assertTrue(kotlin.math.abs(r[1] - (src.second - r[3])) <= 1)
        }
    }

    @Test
    fun aDegenerateSizeDrawsTheWholeImage() {
        assertArrayEquals(intArrayOf(0, 0, 400, 300), BackdropFit.cropRect(400, 300, 0, 0))
    }

    @Test
    fun theDecodeStepNeverDropsBelowTheScreen() {
        assertEquals(1, BackdropFit.sampleSize(2000, 1500, 2400))
        assertEquals(2, BackdropFit.sampleSize(4800, 3600, 2400))
        assertEquals(2, BackdropFit.sampleSize(8000, 6000, 2400))
        assertEquals(4, BackdropFit.sampleSize(9600, 7200, 2400))
        for (long in listOf(2400, 3000, 5000, 9999, 12000)) {
            val s = BackdropFit.sampleSize(long, 100, 2400)
            assertTrue("$long / $s", long / s >= 2400)
        }
        assertEquals(1, BackdropFit.sampleSize(0, 0, 2400))
    }

    @Test
    fun theStoredImageIsNoLargerThanTheScreen() {
        // A 4000 x 3000 photo decodes whole at step 1 (half would be under 2400); it is then
        // scaled to the screen's long edge, a quarter of the memory in the keyboard.
        assertEquals(1, BackdropFit.sampleSize(4000, 3000, 2400))
        assertEquals(2400 to 1800, BackdropFit.scaledSize(4000, 3000, 2400))
        assertEquals(1800 to 2400, BackdropFit.scaledSize(3000, 4000, 2400))
        assertEquals(1000 to 800, BackdropFit.scaledSize(1000, 800, 2400))
    }

    @Test
    fun aCameraPhotoTurnsUpright() {
        assertEquals(0, BackdropFit.degreesFor(1))
        assertEquals(180, BackdropFit.degreesFor(3))
        assertEquals(90, BackdropFit.degreesFor(6))
        assertEquals(270, BackdropFit.degreesFor(8))
        assertEquals(0, BackdropFit.degreesFor(0))
        assertEquals(3000 to 4000, BackdropFit.rotatedSize(4000, 3000, 90))
        assertEquals(4000 to 3000, BackdropFit.rotatedSize(4000, 3000, 180))
    }

    @Test
    fun theOpacitySlidersAreTheSettableRange() {
        val xml = PrefsXml.read()
        for ((key, min, default) in listOf(
            Triple("pref_backdrop_alpha", 0, BackdropFit.IMAGE_ALPHA_DEFAULT),
            Triple("pref_key_alpha", BackdropFit.KEY_ALPHA_MIN, BackdropFit.KEY_ALPHA_DEFAULT),
        )) {
            val row = Regex("""android:key="$key"[^>]*>""").find(xml)?.value
            requireNotNull(row) { "$key not in keyboard_prefs.xml" }
            assertTrue(row, row.contains("app:min=\"$min\""))
            assertTrue(row, row.contains("android:max=\"100\""))
            assertTrue(row, row.contains("android:defaultValue=\"$default\""))
        }
    }

    @Test
    fun aStoredOpacityOutsideTheRangeIsHeldInside() {
        val prefs = MapPrefs(
            mapOf(Prefs.KEY_ALPHA to 0, Prefs.BACKDROP_ALPHA to 250),
        )
        val config = KeyboardConfig.from(prefs)
        assertEquals(BackdropFit.KEY_ALPHA_MIN, config.keyAlphaPct)
        assertEquals(100, config.backdropAlphaPct)
        assertEquals("", config.backdropStamp)
    }

    @Test
    fun opacityIsAPaintAlpha() {
        assertEquals(0, BackdropFit.alpha255(0))
        assertEquals(255, BackdropFit.alpha255(100))
        assertEquals(153, BackdropFit.alpha255(60))
        assertEquals(255, BackdropFit.alpha255(140))
        assertEquals(0, BackdropFit.alpha255(-5))
    }
}
