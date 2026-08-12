package com.kinetica.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The derived-palette half of theming. `fromResources` and `fromDynamic` need a
 * Context and are device-only; `fromPrimary` is pure, and it is the one that has
 * to work at an arbitrary user hue in both brightnesses.
 *
 * Colour arithmetic only, and it is testable at all because the maths moved into
 * [Hsv]: every static in android.graphics.Color throws "not mocked" in the JVM
 * test runtime, so a palette built on Color.HSVToColor could not be checked
 * without a device - which is how a near-white text constant on a near-white
 * light surface would have shipped unnoticed.
 */
class KeyboardThemeTest {

    /** A hue every 30 degrees, so no assertion passes on one lucky colour. */
    private val hues = (0..330 step 30).map { it.toFloat() }

    // The shipped helpers, so the test measures what the app measures.
    private fun luminance(color: Int) = Hsv.luminance(color)

    private fun contrast(a: Int, b: Int) = Hsv.contrast(a, b)

    @Test
    fun theDarkCustomPaletteIsUnchanged() {
        // The two near-white text roles are hard-coded on purpose: deriving them
        // from the hue would have been tidier and would have shifted every
        // existing custom dark theme. Locked so that stays a decision.
        for (h in hues) {
            val t = KeyboardTheme.fromPrimary(KeyboardTheme.primaryForHue(h), light = false)
            assertEquals("keyText at hue $h", 0xFFEDEDF2.toInt(), t.keyText)
            assertEquals("suggestionText at hue $h", 0xFFD8D8E0.toInt(), t.suggestionText)
            assertEquals("suggestionPrimary at hue $h", 0xFFFFFFFF.toInt(), t.suggestionPrimary)
            // The accent IS the primary in dark, untouched.
            assertEquals(KeyboardTheme.primaryForHue(h), t.accent)
        }
    }

    @Test
    fun textIsReadableOnItsOwnSurfaceInBothBrightnesses() {
        // The failure this guards is specific: a near-white text constant on a
        // near-white light surface. 4.5:1 is the WCAG AA body-text ratio.
        for (h in hues) {
            for (light in listOf(false, true)) {
                val t = KeyboardTheme.fromPrimary(KeyboardTheme.primaryForHue(h), light)
                val label = "hue $h light=$light"
                assertTrue(
                    "$label key text contrast ${contrast(t.keyText, t.key)}",
                    contrast(t.keyText, t.key) >= 4.5,
                )
                assertTrue(
                    "$label hint contrast ${contrast(t.keyHint, t.key)}",
                    contrast(t.keyHint, t.key) >= 2.0,
                )
                assertTrue(
                    "$label suggestion contrast ${contrast(t.suggestionText, t.suggestionBg)}",
                    contrast(t.suggestionText, t.suggestionBg) >= 4.5,
                )
                assertTrue(
                    "$label primary contrast ${contrast(t.suggestionPrimary, t.suggestionBg)}",
                    contrast(t.suggestionPrimary, t.suggestionBg) >= 4.5,
                )
            }
        }
    }

    @Test
    fun lightIsActuallyLightAndDarkIsActuallyDark() {
        for (h in hues) {
            val dark = KeyboardTheme.fromPrimary(KeyboardTheme.primaryForHue(h), light = false)
            val lightT = KeyboardTheme.fromPrimary(KeyboardTheme.primaryForHue(h), light = true)
            assertTrue("dark bg too bright at $h", luminance(dark.background) < 0.15)
            assertTrue("light bg too dark at $h", luminance(lightT.background) > 0.55)
            // A key must be distinguishable from the surface it sits on, or the
            // keyboard reads as one flat slab.
            assertNotEquals(lightT.key, lightT.background)
            assertTrue(
                "light key/background too close at $h",
                contrast(lightT.key, lightT.background) >= 1.08,
            )
        }
    }

    @Test
    fun theAccentStaysVisibleAgainstTheSurfaceItMarks() {
        // In light mode the accent must be darkened or the popup highlight and the
        // badge dots disappear into a pale key.
        for (h in hues) {
            val t = KeyboardTheme.fromPrimary(KeyboardTheme.primaryForHue(h), light = true)
            assertTrue(
                "accent invisible at hue $h: ${contrast(t.accent, t.suggestionBg)}",
                contrast(t.accent, t.suggestionBg) >= 2.0,
            )
        }
    }

    @Test
    fun aHueRoundTripsThroughThePrimary() {
        // The migration path depends on this: an existing install's stored hex is
        // turned into a hue, and that hue has to reproduce the same accent family.
        for (h in hues) {
            val recovered = KeyboardTheme.hueOf(KeyboardTheme.primaryForHue(h))
            assertEquals("hue $h", h.toDouble(), recovered.toDouble(), 1.0)
        }
    }

    @Test
    fun theShippedBlueMigratesToItsOwnHue() {
        // #5468FF is what every existing custom-theme user has stored; the slider
        // default and this must agree or upgrading visibly recolours the keyboard.
        assertEquals(233.0, KeyboardTheme.hueOf(0xFF5468FF.toInt()).toDouble(), 1.0)
    }

    @Test
    fun everyRoleDiffersFromTheBackground() {
        for (h in hues) {
            for (light in listOf(false, true)) {
                val t = KeyboardTheme.fromPrimary(KeyboardTheme.primaryForHue(h), light)
                for ((name, role) in listOf(
                    "key" to t.key, "keySpecial" to t.keySpecial, "keyPressed" to t.keyPressed,
                    "keyText" to t.keyText, "keyHint" to t.keyHint,
                    "suggestionText" to t.suggestionText, "chip" to t.chip,
                    "popupBg" to t.popupBg, "accent" to t.accent,
                )) {
                    assertNotEquals("$name == background at hue $h light=$light",
                        t.background, role)
                }
            }
        }
    }

    @Test
    fun theHueOnlyAffectsTheCustomPalette() {
        // What the settings preview caption is built on. A swatch that moved with
        // the slider under the bundled or Material You palettes would be claiming
        // an effect the hue does not have there.
        assertTrue(KeyboardTheme.hueAffects(KeyboardTheme.MODE_CUSTOM))
        assertFalse(KeyboardTheme.hueAffects(KeyboardTheme.MODE_DEFAULT))
        assertFalse(KeyboardTheme.hueAffects(KeyboardTheme.MODE_DYNAMIC))
        // A stale or misspelt stored mode falls through to the bundled palette in
        // resolve(), so it must report the same here.
        assertFalse(KeyboardTheme.hueAffects(""))
        assertFalse(KeyboardTheme.hueAffects("Custom"))
    }

    @Test
    fun movingTheHueMovesEverySurfaceOfTheCustomPalette() {
        // The preview is only worth showing if the hue visibly changes something.
        // Two hues a third of the wheel apart must differ in every derived role -
        // if any stayed put, the swatch would under-report what the slider does.
        for (light in listOf(false, true)) {
            val a = KeyboardTheme.fromPrimary(KeyboardTheme.primaryForHue(20f), light)
            val b = KeyboardTheme.fromPrimary(KeyboardTheme.primaryForHue(140f), light)
            for ((name, pair) in listOf(
                "background" to (a.background to b.background),
                "key" to (a.key to b.key),
                "keySpecial" to (a.keySpecial to b.keySpecial),
                "keyPressed" to (a.keyPressed to b.keyPressed),
                "suggestionBg" to (a.suggestionBg to b.suggestionBg),
                "chip" to (a.chip to b.chip),
                "popupBg" to (a.popupBg to b.popupBg),
                "accent" to (a.accent to b.accent),
            )) {
                assertNotEquals("$name does not follow the hue (light=$light)",
                    pair.first, pair.second)
            }
        }
    }

    @Test
    fun hueIsClampedRatherThanWrappedIntoNonsense() {
        // The slider is 0..360 but a stale preference can hold anything.
        for (h in listOf(-90f, -1f, 361f, 720f, Float.NaN)) {
            val c = KeyboardTheme.primaryForHue(h)
            assertEquals("alpha at hue $h", 0xFF, (c shr 24) and 0xFF)
        }
    }
}
