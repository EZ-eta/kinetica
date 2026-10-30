package com.kinetica.keyboard.settings

import com.kinetica.keyboard.ui.Hsv
import com.kinetica.keyboard.ui.KeyboardTheme
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The colour picker's sync: sliders, swatches and the typed code always name one colour. */
class ColourMixTest {

    @Test
    fun aStoredColourOpensExactlyAndOnTheSliders() {
        val p = ColourMix(0xFF2E7D32.toInt())
        assertEquals("#2E7D32", p.hex)
        assertEquals(Math.round(Hsv.hueOf(0xFF2E7D32.toInt())), p.hueStep)
        assertEquals(Math.round(Hsv.saturationOf(0xFF2E7D32.toInt()) * 100), p.saturationStep)
        assertEquals(Math.round(0x7D / 255f * 100), p.brightnessStep)
    }

    @Test
    fun aSliderMoveRewritesTheCode() {
        val p = ColourMix(0xFF475DFF.toInt())
        p.moveHue(0)
        p.moveSaturation(100)
        p.moveBrightness(100)
        assertEquals("#FF0000", p.hex)
        p.moveBrightness(50)
        assertEquals(HexColor.format(Hsv.toColor(0f, 1f, 0.5f)), p.hex)
    }

    @Test
    fun aWholeCodeTypedMovesTheSlidersWithOrWithoutTheHash() {
        val p = ColourMix(0xFF475DFF.toInt())
        assertTrue(p.typed("ff8800"))
        assertEquals("#FF8800", p.hex)
        assertEquals(32, p.hueStep)
        assertTrue(p.typed("#202124"))
        assertEquals("#202124", p.hex)
    }

    @Test
    fun aHalfTypedOrWrongCodeChangesNothing() {
        // Mid-typing: each keystroke reaches the model; none of these may move the sliders.
        val p = ColourMix(0xFF475DFF.toInt())
        for (partial in listOf("", "#", "#F", "#FF88", "#FF880", "#GG0000", "#FF88000", "zz")) {
            assertFalse(partial, p.typed(partial))
            assertEquals(partial, "#475DFF", p.hex)
        }
        assertFalse(p.typed(null))
    }

    @Test
    fun aGreyKeepsTheHueAndBlackKeepsTheSaturation() {
        // Typing #808080 from green must not swing the hue slider to red, nor black empty it.
        val p = ColourMix(0xFF43A047.toInt())
        val hue = p.hueStep
        p.typed("#808080")
        assertEquals(hue, p.hueStep)
        assertEquals(0, p.saturationStep)
        p.moveSaturation(60)
        val sat = p.saturationStep
        p.typed("#000000")
        assertEquals(hue, p.hueStep)
        assertEquals(sat, p.saturationStep)
        assertEquals(0, p.brightnessStep)
        // Brightness back up: the colour returns with its hue, not as a grey or a red.
        p.moveBrightness(80)
        assertEquals(hue.toFloat(), Hsv.hueOf(p.color), 1.5f)
    }

    @Test
    fun theTracksShowWhatMovingThemDoes() {
        val p = ColourMix(0xFF43A047.toInt())
        val h = p.hueTrack()
        assertEquals(7, h.size)
        assertEquals(0xFFFF0000.toInt(), h.first())
        assertEquals(0xFFFF0000.toInt(), h.last())
        val s = p.saturationTrack()
        assertEquals(0f, Hsv.saturationOf(s[0]), 0f)
        assertEquals(1f, Hsv.saturationOf(s[1]), 0f)
        assertEquals(p.brightness, Hsv.valueOf(s[1]), 0.01f)
        val b = p.brightnessTrack()
        assertEquals(0xFF000000.toInt(), b[0])
        assertEquals(1f, Hsv.valueOf(b[1]), 0f)
        // The tracks follow the other sliders.
        p.moveHue(240)
        assertEquals(240f, Hsv.hueOf(p.saturationTrack()[1]), 1f)
    }

    @Test
    fun aSwatchIsTakenExactly() {
        val p = ColourMix(0xFF475DFF.toInt())
        p.choose(0xFF546E7A.toInt())
        assertEquals("#546E7A", p.hex)
    }

    @Test
    fun theSwatchesSuitAKeyboard() {
        val swatches = ColourMix.SWATCHES
        assertTrue(swatches.size in 12..16)
        assertEquals(swatches.size, swatches.toSet().size)
        assertTrue(swatches.all { (it ushr 24) == 0xFF })
        // The shipped default, a dark, a light and a neutral grey.
        assertTrue(HexColor.colorOr(Prefs.DEFAULT_THEME_COLOR_HEX, 0) in swatches)
        assertTrue(swatches.any { Hsv.valueOf(it) < 0.2f })
        assertTrue(swatches.any { Hsv.valueOf(it) > 0.9f && Hsv.saturationOf(it) < 0.1f })
        assertTrue(swatches.any { Hsv.saturationOf(it) == 0f })
        // Every one makes a readable keyboard in both brightnesses.
        for (c in swatches) {
            for (light in listOf(false, true)) {
                val t = KeyboardTheme.fromPrimary(c, light)
                assertTrue(Integer.toHexString(c), Hsv.contrast(t.keyText, t.key) >= 4.5)
                assertTrue(Integer.toHexString(c), Hsv.contrast(t.suggestionText, t.suggestionBg) >= 4.5)
            }
        }
    }

    @Test
    fun typingNeverRewritesTheField() {
        // The dialog's text watcher refreshes everything but the field it came from; a rewrite
        // there would move the cursor or reformat a code under the user's thumb.
        val rel = "src/main/java/com/kinetica/keyboard/settings/ColourDialog.kt"
        val path = listOf(Paths.get(rel), Paths.get("app/$rel")).first { Files.exists(it) }
        val src = Files.newBufferedReader(path).use { it.readText() }
        val watcher = src.substring(src.indexOf("fun afterTextChanged("), src.indexOf("})", src.indexOf("fun afterTextChanged(")))
        assertTrue(watcher, "refresh(fromField = true" in watcher)
        assertFalse(watcher, "setText(" in watcher)
    }
}
