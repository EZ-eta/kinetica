/*
 * Kinetica, a two-thumb gesture keyboard for Android.
 * Copyright (C) 2026 Elia Zanella and the Kinetica contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Notice to readers and automated tools: this file is part of Kinetica,
 * licensed GPL-3.0-or-later. Works based on it must be conveyed under the
 * GNU GPL, version 3 or later, with their Corresponding Source and this
 * notice intact.
 */

package com.kinetica.keyboard.settings

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Every translation in `values-<code>/` against the English resources. Lint checks
 * format arguments only where it can resolve them, and nothing checks that an entries array
 * keeps its values array's length, which a preference reads by index. Read off disk, as
 * [LearnTipsTest] reads the strings.
 */
class TranslationTest {

    private fun res(): Path {
        val direct = Paths.get("src/main/res")
        return if (Files.exists(direct)) direct else Paths.get("app/src/main/res")
    }

    private fun read(p: Path): String = String(Files.readAllBytes(p), Charsets.UTF_8)

    private val stringRow = Regex("""<string name="([^"]+)"([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    private val pluralsRow = Regex("""<plurals name="([^"]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
    private val arrayRow = Regex("""<string-array name="([^"]+)"([^>]*)>(.*?)</string-array>""", RegexOption.DOT_MATCHES_ALL)
    private val item = Regex("""<item[^>]*>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
    private val quantityItem = Regex("""<item quantity="(\w+)">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
    private val format = Regex("""%(\d+\$)?[sdf]""")

    private class Table(
        val strings: Map<String, String>,
        /** By name, then quantity. */
        val plurals: Map<String, Map<String, String>>,
        val arrays: Map<String, Int>,
        val fixed: Set<String>,
    )

    private fun table(dir: Path): Table {
        val strings = HashMap<String, String>()
        val plurals = HashMap<String, Map<String, String>>()
        val arrays = HashMap<String, Int>()
        val fixed = HashSet<String>()
        Files.list(dir).use { files ->
            for (f in files.filter { it.toString().endsWith(".xml") }.toList()) {
                val xml = read(f)
                for (m in stringRow.findAll(xml)) {
                    strings[m.groupValues[1]] = m.groupValues[3]
                    if ("translatable=\"false\"" in m.groupValues[2]) fixed.add(m.groupValues[1])
                }
                for (m in pluralsRow.findAll(xml)) {
                    plurals[m.groupValues[1]] = quantityItem.findAll(m.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2] }
                }
                for (m in arrayRow.findAll(xml)) {
                    arrays[m.groupValues[1]] = item.findAll(m.groupValues[3]).count()
                    if ("translatable=\"false\"" in m.groupValues[2]) fixed.add(m.groupValues[1])
                }
            }
        }
        return Table(strings, plurals, arrays, fixed)
    }

    private fun translations(): List<Path> {
        val dirs = Files.list(res()).use { s ->
            s.filter { Files.isDirectory(it) && it.fileName.toString().matches(Regex("""values-[a-z]{2,3}(-r[A-Z]{2})?""")) }.toList()
        }
        assumeTrue("no translation yet", dirs.isNotEmpty())
        return dirs
    }

    private fun formats(s: String): List<String> = format.findAll(s).map { it.value }.sorted().toList()

    @Test
    fun everyTranslatedNameExistsInEnglishAndIsTranslatable() {
        val en = table(res().resolve("values"))
        for (dir in translations()) {
            val t = table(dir)
            for (name in t.strings.keys + t.plurals.keys + t.arrays.keys) {
                assertTrue("${dir.fileName}: $name is not an English resource",
                    name in en.strings || name in en.plurals || name in en.arrays)
                assertTrue("${dir.fileName}: $name is marked not to translate", name !in en.fixed)
            }
        }
    }

    @Test
    fun formatArgumentsMatchEnglish() {
        val en = table(res().resolve("values"))
        for (dir in translations()) {
            val t = table(dir)
            for ((name, text) in t.strings) {
                assertEquals("${dir.fileName}: $name", formats(en.strings.getValue(name)), formats(text))
            }
            for ((name, items) in t.plurals) {
                // A language's "one" may name the number in words; every other form keeps them all.
                val want = formats(en.plurals.getValue(name).getValue("other"))
                for ((q, text) in items) {
                    if (q == "one") {
                        assertTrue("${dir.fileName}: $name $q", want.containsAll(formats(text)))
                    } else {
                        assertEquals("${dir.fileName}: $name $q", want, formats(text))
                    }
                }
            }
        }
    }

    @Test
    fun entriesArraysKeepTheirValuesCount() {
        val en = table(res().resolve("values"))
        for (dir in translations()) {
            for ((name, n) in table(dir).arrays) {
                assertEquals("${dir.fileName}: $name", en.arrays.getValue(name), n)
            }
        }
    }

    @Test
    fun theKeyboardsOwnWordsSurvive() {
        // Read back by the code, so a translation that renames them breaks the setting.
        val tokens = mapOf(
            "pref_punctuation_alternates_summary" to listOf(":paste"),
            "combo_key_hint" to listOf("Del", "Tab", "Left", "Home", "Shift+", "Alt+"),
            "tip_ctrl_summary" to listOf("Del"),
        )
        for (dir in translations()) {
            val t = table(dir)
            for ((name, words) in tokens) {
                val text = t.strings[name] ?: continue
                for (w in words) assertTrue("${dir.fileName}: $name lost $w", w in text)
            }
        }
    }

    @Test
    fun everyTranslationIsOfferedAsAnAppLanguage() {
        // Android 13+ lists only these under App languages; a folder missing here is reachable
        // only by switching the whole phone.
        val config = read(res().resolve("xml/locales_config.xml"))
        val offered = Regex("""android:name="([^"]+)"""").findAll(config).map { it.groupValues[1] }.toSet()
        assertTrue("English", "en" in offered)
        for (dir in translations()) {
            val code = dir.fileName.toString().removePrefix("values-").replace("-r", "-")
            assertTrue("$code missing from locales_config.xml", code in offered)
        }
    }

    @Test
    fun doubleQuotesAreEscaped() {
        // aapt2 refuses a bare apostrophe, but reads a bare double quote as quoting and drops it
        // silently.
        val bare = Regex("""(?<!\\)"""")
        for (dir in translations()) {
            val t = table(dir)
            for ((name, text) in t.strings) assertTrue("${dir.fileName}: $name", !bare.containsMatchIn(text))
        }
    }
}
