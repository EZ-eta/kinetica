package com.kinetica.keyboard.settings

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/** One choice for which languages suggest, and the migration from the two switches it replaced. */
class LanguageModeTest {

    private fun migrated(vararg stored: Pair<String, Any>): EditablePrefs =
        EditablePrefs(mapOf(*stored)).also { LanguageMode.migrate(it) }

    @Test
    fun bothOldSwitchesOnBecomeFollow() {
        // With both on, mix did nothing: follow alone ran.
        val p = migrated(Prefs.AUTO_DETECT_LANGUAGE to true, Prefs.NO_PRIMARY to true)
        assertEquals(LanguageMode.FOLLOW, MapPrefs.config(p.values).languageMode)
    }

    @Test
    fun mixAloneIsMixFollowAloneIsFollow() {
        assertEquals("mix", migrated(Prefs.AUTO_DETECT_LANGUAGE to true, Prefs.NO_PRIMARY to false).values[Prefs.LANGUAGE_MODE])
        assertEquals("follow", migrated(Prefs.NO_PRIMARY to true).values[Prefs.LANGUAGE_MODE])
        assertEquals("one", migrated(Prefs.AUTO_DETECT_LANGUAGE to false).values[Prefs.LANGUAGE_MODE])
    }

    @Test
    fun oldKeysAreRemoved() {
        val p = migrated(Prefs.AUTO_DETECT_LANGUAGE to true, Prefs.NO_PRIMARY to false)
        assertFalse(p.contains(Prefs.AUTO_DETECT_LANGUAGE))
        assertFalse(p.contains(Prefs.NO_PRIMARY))
        // A second run finds nothing to do and leaves the mode alone.
        LanguageMode.migrate(p)
        assertEquals("mix", p.values[Prefs.LANGUAGE_MODE])
    }

    @Test
    fun noOldKeysWritesNothing() {
        val p = migrated(Prefs.AUTOSPACE to true)
        assertFalse(p.contains(Prefs.LANGUAGE_MODE))
        assertEquals(LanguageMode.ONE, MapPrefs.config(p.values).languageMode)
    }

    @Test
    fun anOldBackupRestoredOverTheNewKeyWins() {
        // A restore writes the backup's old switches beside the mode chosen since.
        val p = migrated(Prefs.LANGUAGE_MODE to "one", Prefs.AUTO_DETECT_LANGUAGE to true, Prefs.NO_PRIMARY to false)
        assertEquals("mix", p.values[Prefs.LANGUAGE_MODE])
    }

    @Test
    fun configReadsEachModeAndAnUnknownValueIsOne() {
        for (mode in LanguageMode.entries) {
            assertEquals(mode, MapPrefs.config(mapOf(Prefs.LANGUAGE_MODE to mode.value)).languageMode)
        }
        assertEquals(LanguageMode.ONE, MapPrefs.config(mapOf(Prefs.LANGUAGE_MODE to "both")).languageMode)
        assertEquals(LanguageMode.ONE, MapPrefs.config().languageMode)
    }

    @Test
    fun theListOffersEveryModeAndShipsTheCodeDefault() {
        val arrays = res("values/arrays.xml")
        val values = items(arrays, "language_mode_values")
        assertEquals(LanguageMode.entries.map { it.value }, values)
        assertEquals(values.size, items(arrays, "language_mode_entries").size)
        val prefs = res("xml/keyboard_prefs.xml")
        val default = Regex("""android:key="${Prefs.LANGUAGE_MODE}"[^>]*?android:defaultValue="([^"]+)"""")
            .find(prefs)?.groupValues?.get(1)
        assertEquals(Prefs.DEFAULT_LANGUAGE_MODE, default)
    }

    private fun res(rel: String): String {
        val direct = Paths.get("src/main/res/$rel")
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/src/main/res/$rel")
        assumeTrue("$rel not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }

    private fun items(xml: String, name: String): List<String> {
        val block = Regex("""<string-array name="$name"[^>]*>(.*?)</string-array>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1) ?: return emptyList()
        return Regex("""<item>([^<]*)</item>""").findAll(block).map { it.groupValues[1] }.toList()
    }
}
