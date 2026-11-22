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
