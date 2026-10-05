package com.kinetica.keyboard.layout

import com.kinetica.keyboard.engine.Alphabet
import com.kinetica.keyboard.settings.Prefs
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The boards in other scripts, read off the shipped JSON as text (the JVM stubs
 * org.json): every letter key is a letter of the declared script, every letter of the script
 * is a key, and the rows are the ones the engine's goldens decode on (TestData).
 */
class NativeLayoutTest {

    private fun layoutLines(name: String): List<String> {
        val p = listOf(
            Paths.get("src/main/assets/layouts/$name.json"),
            Paths.get("app/src/main/assets/layouts/$name.json"),
        ).firstOrNull { Files.exists(it) }
        assumeTrue("$name not found", p != null)
        return Files.readAllLines(p!!)
    }

    private val keyLine = Regex(""""id": "([^"]+)", "type": "char", "label": "[^"]*", "output": "([^"]+)", "x": ([0-9.]+), "y": ([0-9.]+), "w": ([0-9.]+)""")

    private fun rows(name: String): List<Pair<String, Float>> {
        val keys = layoutLines(name).mapNotNull { keyLine.find(it)?.groupValues }
            .filter { it[1] != "comma" && it[1] != "period" }
        return keys.groupBy { it[4].toFloat() }.toSortedMap().values.map { row ->
            val sorted = row.sortedBy { it[3].toFloat() }
            val w = sorted.first()[5].toFloat()
            sorted.joinToString("") { it[2] } to sorted.first()[3].toFloat() / w
        }
    }

    private fun assertBoard(name: String, script: String, expected: List<Pair<String, Float>>) {
        val lines = layoutLines(name)
        assertEquals(true, lines.any { it.contains(""""script": "$script"""") })
        val alphabet = Alphabet.forScript(script)
        val got = rows(name)
        assertEquals(expected.map { it.first }, got.map { it.first })
        for ((e, g) in expected.zip(got)) assertEquals("${e.first} offset", e.second, g.second, 0.01f)
        assertEquals(alphabet.letters.toSet(), got.joinToString("") { it.first }.toSet())
    }

    @Test
    fun russianIsJcukenInCyrillic() = assertBoard(
        "native_ru", "cyrillic",
        listOf("йцукенгшщзхъ" to 0f, "фывапролджэ" to 0.5f, "ячсмитьбю" to 1.5f),
    )

    @Test
    fun ukrainianIsItsOwnJcuken() = assertBoard(
        "native_uk", "ukrainian",
        listOf("йцукенгшщзхї" to 0f, "фівапролджє" to 0.5f, "ячсмитьбю" to 1.5f),
    )

    @Test
    fun hebrewIsTheIsraeliStandard() = assertBoard(
        "native_he", "hebrew",
        listOf("קראטוןםפ" to 0f, "שדגכעיחלךף" to 0f, "זסבהנמצתץ" to 0.5f),
    )

    @Test
    fun arabicFollowsArabic101() = assertBoard(
        "native_ar", "arabic",
        listOf("ضصثقفغعهخحجد" to 0f, "شسيبلاتنمكط" to 0.5f, "ذئءؤرىةوزظ" to 0f),
    )

    @Test
    fun theXmlDefaultEnabledSetIsTheCodeDefault() {
        // Two sources of truth for a new install's languages: the XML one when Settings first
        // writes itself, the code one when KeyboardConfig reads an unset preference.
        val p: Path = listOf(
            Paths.get("src/main/res/values/arrays.xml"),
            Paths.get("app/src/main/res/values/arrays.xml"),
        ).first { Files.exists(it) }
        val xml = Files.newBufferedReader(p).use { it.readText() }
        val block = Regex("""<string-array name="language_default_values"[^>]*>(.*?)</string-array>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)!!.groupValues[1]
        val items = Regex("""<item>([^<]*)</item>""").findAll(block).map { it.groupValues[1] }.toList()
        assertEquals(Prefs.DEFAULT_ENABLED_LANGUAGES, items)
    }
}
