package com.kinetica.keyboard.settings

import android.content.res.Configuration
import com.kinetica.keyboard.ui.SwipeLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The day and night looks: which one the keyboard draws, what a look holds, how the night one starts. */
class LookSetsTest {

    private val night = Prefs.nightTwin(Prefs.THEME_MODE)

    private fun prefs(vararg extra: Pair<String, Any>) = EditablePrefs(
        mapOf(Prefs.THEME_MODE to "default", night to "custom", Prefs.ZEN_MODE to true) + extra,
    )

    @Test
    fun theNightLookIsDrawnOnlyWithTheSwitchOnAndThePhoneDark() {
        assertEquals("default", KeyboardConfig.from(prefs(), systemNight = true).themeMode)
        val on = prefs(Prefs.LOOK_SETS to true)
        assertEquals("default", KeyboardConfig.from(on, systemNight = false).themeMode)
        val drawn = KeyboardConfig.from(on, systemNight = true)
        assertEquals("custom", drawn.themeMode)
        assertTrue(drawn.nightLook)
        assertFalse(KeyboardConfig.from(on, systemNight = false).nightLook)
        // Not part of a look: the same either way.
        assertTrue(drawn.zenMode)
    }

    @Test
    fun everyLookRowReadsItsNightTwin() {
        val p = EditablePrefs(
            mapOf(
                Prefs.LOOK_SETS to true,
                Prefs.nightTwin(Prefs.THEME_BRIGHTNESS) to "light",
                Prefs.nightTwin(Prefs.THEME_COLOR_HEX) to "#112233",
                Prefs.nightTwin(Prefs.BACKDROP) to "42",
                Prefs.nightTwin(Prefs.BACKDROP_CROP) to "0.1,0.1,0.9,0.9",
                Prefs.nightTwin(Prefs.BACKDROP_CROP_LANDSCAPE) to "0.2,0.2,0.8,0.8",
                Prefs.nightTwin(Prefs.BACKDROP_ALPHA) to 30,
                Prefs.nightTwin(Prefs.KEY_ALPHA) to 40,
                Prefs.nightTwin(Prefs.SWIPE_LOOK) to "keys",
                Prefs.nightTwin(Prefs.TRAIL_COLOR) to "green",
                Prefs.nightTwin(Prefs.KEY_BORDERS) to true,
                Prefs.nightTwin(Prefs.KEY_BORDER_WIDTH) to 3,
            ),
        )
        val c = KeyboardConfig.from(p, systemNight = true)
        assertEquals("light", c.themeBrightness)
        assertEquals(0xFF112233.toInt(), c.themeColor)
        assertEquals("42", c.backdropStamp)
        assertEquals("0.1,0.1,0.9,0.9", c.backdropCrop)
        assertEquals("0.2,0.2,0.8,0.8", KeyboardConfig.from(p, landscape = true, systemNight = true).backdropCrop)
        assertEquals(30, c.backdropAlphaPct)
        assertEquals(40, c.keyAlphaPct)
        assertEquals(SwipeLook.fromPref("keys"), c.swipeLook)
        assertEquals("green", c.trailColorMode)
        assertEquals(120f, c.trailBaseHue, 0f)
        assertEquals(3, c.keyBorderDp)
        // The day look is untouched by the night one.
        val day = KeyboardConfig.from(p, systemNight = false)
        assertEquals(Prefs.DEFAULT_THEME_BRIGHTNESS, day.themeBrightness)
        assertEquals("", day.backdropStamp)
        assertEquals(0, day.keyBorderDp)
    }

    @Test
    fun theSeedCopiesTheDayLookOverTheStoredDefaultsOnce() {
        // Inflating the settings stores each row's default under its twin before the switch is on.
        val p = EditablePrefs(
            mapOf(
                Prefs.THEME_MODE to "custom",
                Prefs.KEY_BORDER_WIDTH to 3,
                Prefs.KEY_BORDERS to true,
                Prefs.nightTwin(Prefs.THEME_MODE) to "default",
                Prefs.nightTwin(Prefs.KEY_BORDER_WIDTH) to 1,
                Prefs.nightTwin(Prefs.SWIPE_LOOK) to "keys",
            ),
        )
        assertTrue(LookSets.seed(p))
        assertEquals("custom", p.values[Prefs.nightTwin(Prefs.THEME_MODE)])
        assertEquals(3, p.values[Prefs.nightTwin(Prefs.KEY_BORDER_WIDTH)])
        assertEquals(true, p.values[Prefs.nightTwin(Prefs.KEY_BORDERS)])
        // A day row never written: its twin goes back to the default too.
        assertFalse(p.contains(Prefs.nightTwin(Prefs.SWIPE_LOOK)))
        // Edited afterwards, the night look stays as edited when the switch comes back on.
        p.edit().putString(Prefs.nightTwin(Prefs.THEME_MODE), "dynamic").apply()
        assertFalse(LookSets.seed(p))
        assertEquals("dynamic", p.values[Prefs.nightTwin(Prefs.THEME_MODE)])
    }

    @Test
    fun theSystemIsNightOnlyOnItsNightBit() {
        assertTrue(LookSets.systemNight(Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL))
        assertFalse(LookSets.systemNight(Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_NORMAL))
        assertFalse(LookSets.systemNight(Configuration.UI_MODE_NIGHT_UNDEFINED))
    }

    @Test
    fun aNightColourRowHasItsDayRowsDefault() {
        assertEquals(Prefs.DEFAULT_TRAIL_COLOR_HEX, Prefs.colorHexDefault(Prefs.nightTwin(Prefs.TRAIL_COLOR_HEX)))
        assertEquals(Prefs.DEFAULT_THEME_COLOR_HEX, Prefs.colorHexDefault(Prefs.nightTwin(Prefs.THEME_COLOR_HEX)))
        val twin = Prefs.nightTwin(Prefs.TRAIL_COLOR_HEX)
        assertEquals(Prefs.DEFAULT_TRAIL_COLOR_HEX, ChangedSettings.defaultOf(twin, emptyMap()))
        // Only a look key has a twin to read back.
        assertEquals("pref_zen_mode_night", Prefs.dayKey("pref_zen_mode_night"))
    }

    @Test
    fun theImageTravelsInItsOwnRecordNotAsAStamp() {
        for (key in listOf(Prefs.BACKDROP, Prefs.BACKDROP_CROP, Prefs.BACKDROP_CROP_LANDSCAPE)) {
            assertFalse(key, Backup.carried(Prefs.nightTwin(key)))
        }
        for (key in Prefs.NIGHT_TWINS - setOf(Prefs.BACKDROP, Prefs.BACKDROP_CROP, Prefs.BACKDROP_CROP_LANDSCAPE)) {
            assertTrue(key, Backup.carried(Prefs.nightTwin(key)))
        }
        assertTrue(Backup.carried(Prefs.LOOK_SETS))
        assertTrue(Backup.carried(Prefs.LOOK_NIGHT_SEEDED))
    }
}
