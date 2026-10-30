package com.kinetica.keyboard.settings

import com.kinetica.keyboard.ui.Hsv
import com.kinetica.keyboard.ui.KeyboardTheme
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hue sliders become typed colours, the same colours, from an install or an old backup. */
class ColorPrefsTest {

    private fun migrated(vararg stored: Pair<String, Any>): EditablePrefs =
        EditablePrefs(mapOf(*stored)).also { ColorPrefs.migrate(it) }

    @Test
    fun aStoredHueBecomesTheColourItWasDrawnIn() {
        val p = migrated(Prefs.THEME_HUE to 120, Prefs.TRAIL_COLOR_CUSTOM_HUE to 300)
        assertEquals(HexColor.format(KeyboardTheme.primaryForHue(120f)), p.values[Prefs.THEME_COLOR_HEX])
        assertEquals(HexColor.format(Hsv.toColor(300f, 0.85f, 1f)), p.values[Prefs.TRAIL_COLOR_HEX])
    }

    @Test
    fun theRetiredColourListMigratesByItsHue() {
        // Before the slider: a pick from the thirteen colours, no hue stored.
        val p = migrated(Prefs.THEME_COLOR to "#E91E63")
        val hue = Hsv.hueOf(0xFFE91E63.toInt())
        assertEquals(HexColor.format(KeyboardTheme.primaryForHue(hue)), p.values[Prefs.THEME_COLOR_HEX])
    }

    @Test
    fun theHueWinsOverTheOlderListPick() {
        val p = migrated(Prefs.THEME_HUE to 30, Prefs.THEME_COLOR to "#E91E63")
        assertEquals(HexColor.format(KeyboardTheme.primaryForHue(30f)), p.values[Prefs.THEME_COLOR_HEX])
    }

    @Test
    fun oldKeysAreRemoved() {
        val p = migrated(Prefs.THEME_HUE to 120, Prefs.THEME_COLOR to "#5468FF", Prefs.TRAIL_COLOR_CUSTOM_HUE to 10)
        for (k in listOf(Prefs.THEME_HUE, Prefs.THEME_COLOR, Prefs.TRAIL_COLOR_CUSTOM_HUE)) {
            assertFalse(k, k in p.values)
        }
    }

    @Test
    fun noOldKeysWritesNothing() {
        val p = migrated(Prefs.THEME_COLOR_HEX to "#123456")
        assertEquals(mapOf(Prefs.THEME_COLOR_HEX to "#123456"), p.values)
    }

    @Test
    fun anOldBackupRestoredOverTheNewKeyWins() {
        // The restore writes the backup's hue beside the colour typed since; the hue was the
        // last thing the backup's install held.
        val p = migrated(Prefs.THEME_COLOR_HEX to "#123456", Prefs.THEME_HUE to 200)
        assertEquals(HexColor.format(KeyboardTheme.primaryForHue(200f)), p.values[Prefs.THEME_COLOR_HEX])
    }

    @Test
    fun theDefaultsAreWhatTheSlidersShowedUntouched() {
        // Untouched, the theme hue came from the shipped blue and the trail slider sat at 200.
        val shipped = KeyboardTheme.primaryForHue(Hsv.hueOf(0xFF5468FF.toInt()))
        assertEquals(HexColor.format(shipped), Prefs.DEFAULT_THEME_COLOR_HEX)
        assertEquals(HexColor.format(ColorPrefs.oldTrailColor(200)), Prefs.DEFAULT_TRAIL_COLOR_HEX)
    }

    @Test
    fun everyPlaceThatMigratesTheLanguageMigratesTheColours() {
        // Keyboard start, settings before inflating, and a restore: missing one shows the default
        // over the old colour, or brings an old backup's hue back unread.
        for (rel in listOf("ime/KineticaIME.kt", "settings/KeyboardPrefsFragment.kt", "settings/BackupRun.kt")) {
            val src = read(rel)
            assertTrue("$rel migrates the language", "LanguageMode.migrate" in src)
            assertTrue("$rel migrates the colours", "ColorPrefs.migrate" in src)
        }
    }

    private fun read(rel: String): String {
        val path = "src/main/java/com/kinetica/keyboard/$rel"
        val p = listOf(Paths.get(path), Paths.get("app/$path")).first { Files.exists(it) }
        return Files.newBufferedReader(p).use { it.readText() }
    }
}
