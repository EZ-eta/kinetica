package com.kinetica.keyboard.settings

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The enabled languages summary spells out the switch's order in every translation. A new
 * language once reached the English summary and not the Italian one.
 *
 * Read off disk, so a fail-first check here needs `--rerun-tasks`.
 */
class EnabledLanguagesSummaryTest {

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

    private fun summary(xml: String): String =
        Regex("""<string name="pref_enabled_languages_summary">([^<]*)</string>""")
            .find(xml)?.groupValues?.get(1).orEmpty()

    @Test
    fun everySummaryNamesEveryLanguageInTheSwitchOrder() {
        // The entries carry "(experimental)" after the name; the summary lists the bare name.
        val names = items(res("values/arrays.xml"), "language_entries").map { it.substringBefore(" (") }
        assertEquals(Prefs.ALL_LANGUAGES.size, names.size)
        val latin = names.take(Prefs.DEFAULT_ENABLED_LANGUAGES.size)
        for (dir in listOf("values", "values-it")) {
            val text = summary(res("$dir/strings.xml"))
            for (name in names) assertTrue("$dir summary lacks $name", name in text)
            val at = latin.map { text.indexOf(it) }
            assertEquals("$dir summary order", at.sorted(), at)
        }
    }
}
