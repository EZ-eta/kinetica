package com.kinetica.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The derived-palette half of theming. `fromResources` and `fromDynamic` need a
 * Context and are device-only; `fromPrimary` is pure and has to work at any user hue in
 * both brightnesses.
 *
 * The maths lives in [Hsv] because every static in android.graphics.Color throws "not mocked"
 * in the JVM test runtime; built on Color.HSVToColor, near-white text on a near-white light
 * surface could ship unnoticed.
 */
class KeyboardThemeTest {

    /** A hue every 30 degrees, so no assertion passes on one lucky colour. */
    private val hues = (0..330 step 30).map { it.toFloat() }

    // The shipped helpers, so the test measures what the app measures.
    private fun luminance(color: Int) = Hsv.luminance(color)

    private fun contrast(a: Int, b: Int) = Hsv.contrast(a, b)

    @Test
    fun theDarkCustomPaletteIsUnchanged() {
        // The two near-white text roles are hard-coded: deriving them from the hue would shift
        // every existing custom dark theme.
        for (h in hues) {
            val t = KeyboardTheme.fromPrimary(KeyboardTheme.primaryForHue(h), light = false)
            assertEquals("keyText at hue $h", 0xFFEDEDF2.toInt(), t.keyText)
            assertEquals("suggestionText at hue $h", 0xFFD8D8E0.toInt(), t.suggestionText)
            assertEquals("suggestionPrimary at hue $h", 0xFFFFFFFF.toInt(), t.suggestionPrimary)
            // The accent is the primary in dark, untouched.
            assertEquals(KeyboardTheme.primaryForHue(h), t.accent)
        }
    }

    @Test
    fun textIsReadableOnItsOwnSurfaceInBothBrightnesses() {
        // Guards near-white text on a near-white light surface. 4.5:1 is the WCAG AA body-text
        // ratio.
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
    fun theColourOnlyAffectsTheCustomPalette() {
        // The settings preview caption relies on this: a swatch that moved with the colour
        // under the bundled or Material You palettes would claim an effect it does not have there.
        assertTrue(KeyboardTheme.colourAffects(KeyboardTheme.MODE_CUSTOM))
        assertFalse(KeyboardTheme.colourAffects(KeyboardTheme.MODE_DEFAULT))
        assertFalse(KeyboardTheme.colourAffects(KeyboardTheme.MODE_DYNAMIC))
        // A stale or misspelt stored mode falls through to the bundled palette in
        // resolve, so it must report the same here.
        assertFalse(KeyboardTheme.colourAffects(""))
        assertFalse(KeyboardTheme.colourAffects("Custom"))
    }

    @Test
    fun movingTheHueMovesEverySurfaceOfTheCustomPalette() {
        // Two hues a third of the wheel apart must differ in every derived role, or the preview
        // swatch would under-report what the slider does.
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

    /** Every 30 degrees, at four saturations and five values: what a typed colour can be. */
    private val typed: List<Int> = hues.flatMap { h ->
        listOf(0f, 0.3f, 0.72f, 1f).flatMap { sat ->
            listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { v -> Hsv.toColor(h, sat, v) }
        }
    }

    @Test
    fun aMigratedColourGivesThePaletteTheSliderGave() {
        // Hue 233, the shipped blue: every role as the slider drew it before colours were typed.
        val dark = KeyboardTheme.fromPrimary(KeyboardTheme.primaryForHue(233f), light = false)
        val light = KeyboardTheme.fromPrimary(KeyboardTheme.primaryForHue(233f), light = true)
        assertEquals(DARK_233, roles(dark))
        assertEquals(LIGHT_233, roles(light))
    }

    @Test
    fun anyTypedColourKeepsTheTextReadable() {
        for (c in typed) {
            for (light in listOf(false, true)) {
                val t = KeyboardTheme.fromPrimary(c, light)
                val label = "${Integer.toHexString(c)} light=$light"
                assertTrue("$label key text", contrast(t.keyText, t.key) >= 4.5)
                assertTrue("$label hint", contrast(t.keyHint, t.key) >= 2.0)
                assertTrue("$label bar text", contrast(t.suggestionText, t.suggestionBg) >= 4.5)
                assertTrue("$label bar word", contrast(t.suggestionPrimary, t.suggestionBg) >= 4.5)
                assertNotEquals("$label key on background", t.background, t.key)
            }
        }
    }

    @Test
    fun aDarkerTypedColourDimsTheSurfaces() {
        for (light in listOf(false, true)) {
            val full = KeyboardTheme.fromPrimary(Hsv.toColor(120f, 0.6f, 1f), light)
            val dim = KeyboardTheme.fromPrimary(Hsv.toColor(120f, 0.6f, 0.3f), light)
            assertTrue("light=$light", luminance(dim.background) < luminance(full.background))
            assertTrue("light=$light", luminance(dim.key) < luminance(full.key))
        }
    }

    @Test
    fun theDarkAccentIsTheTypedColourUnlessItWouldVanish() {
        val bright = 0xFFFF8800.toInt()
        assertEquals(bright, KeyboardTheme.fromPrimary(bright, light = false).accent)
        for (c in typed) {
            val a = KeyboardTheme.fromPrimary(c, light = false).accent
            assertTrue("accent for ${Integer.toHexString(c)}", Hsv.valueOf(a) >= 0.59f)
        }
    }

    @Test
    fun greyGivesANeutralPalette() {
        for (light in listOf(false, true)) {
            val t = KeyboardTheme.fromPrimary(0xFF808080.toInt(), light)
            for (role in listOf(t.background, t.key, t.keySpecial, t.keyPressed, t.suggestionBg, t.popupBg)) {
                assertEquals("light=$light", 0f, Hsv.saturationOf(role), 0f)
            }
        }
    }

    private fun roles(t: KeyboardTheme): List<String> = listOf(
        t.background, t.key, t.keySpecial, t.keyPressed, t.keyText, t.keyHint,
        t.suggestionBg, t.suggestionText, t.suggestionPrimary, t.chip, t.popupBg, t.accent,
    ).map { Integer.toHexString(it) }

    private companion object {
        // Read from the build before colours were typed, when a hue slider set them.
        val DARK_233 = listOf(
            "ff12131a", "ff272936", "ff1e1f29", "ff1f2659", "ffededf2", "ff8f92a8",
            "ff171821", "ffd8d8e0", "ffffffff", "ff1f244d", "ff323445", "ff475dff",
        )
        val LIGHT_233 = listOf(
            "ffcacde6", "fff1f3ff", "ffd5d8f0", "ff7d87d1", "ff1d1e29", "ff4b4f73",
            "ffdddff5", "ff2e3142", "ff12141f", "ff7984db", "ffeef0ff", "ff3241b3",
        )
    }
}
