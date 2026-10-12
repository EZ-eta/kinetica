package com.kinetica.keyboard.settings

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The comma and period settings each list their own key. The period once borrowed the comma's
 * list, so its default read "Comma (default)".
 *
 * Read off disk, so a fail-first check here needs `--rerun-tasks`.
 */
class PunctuationModeArraysTest {

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

    private fun entriesOf(prefs: String, key: String): String? =
        Regex("""android:key="$key"[^>]*?android:entries="@array/([^"]+)"""")
            .find(prefs)?.groupValues?.get(1)

    @Test
    fun thePeriodListsItsOwnOptions() {
        val prefs = res("xml/keyboard_prefs.xml")
        assertEquals("comma_mode_entries", entriesOf(prefs, Prefs.COMMA_MODE))
        assertEquals("period_mode_entries", entriesOf(prefs, Prefs.PERIOD_MODE))
    }

    @Test
    fun everyTranslationNamesTheRightKeyForEveryValue() {
        for (dir in listOf("values", "values-it")) {
            val arrays = res("$dir/arrays.xml")
            val values = items(res("values/arrays.xml"), "comma_mode_values")
            val period = items(arrays, "period_mode_entries")
            assertEquals("$dir period entries", values.size, period.size)
            assertEquals("$dir comma entries", values.size, items(arrays, "comma_mode_entries").size)
            assertTrue("$dir period default", !period.first().contains("omma") && !period.first().contains("irgola"))
        }
    }
}
