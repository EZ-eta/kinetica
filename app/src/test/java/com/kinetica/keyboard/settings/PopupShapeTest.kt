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
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The long-press shape setting (#8): each stored value, and the migration from the grid switch. */
class PopupShapeTest {

    @Test
    fun eachShapeIsAGridWidthAndRowIsTheStrip() {
        assertEquals(0, KeyboardConfig.popupColumns("row", legacyGrid = false))
        assertEquals(3, KeyboardConfig.popupColumns("3", legacyGrid = false))
        assertEquals(4, KeyboardConfig.popupColumns("4", legacyGrid = false))
        assertEquals(5, KeyboardConfig.popupColumns("5", legacyGrid = false))
        assertEquals(0, KeyboardConfig.popupColumns("7", legacyGrid = false))
    }

    @Test
    fun aGridChosenEarlierStaysAGrid() {
        assertEquals(3, KeyboardConfig.popupColumns(null, legacyGrid = true))
        assertEquals(0, KeyboardConfig.popupColumns(null, legacyGrid = false))
        // Once a shape is chosen the old switch says nothing.
        assertEquals(0, KeyboardConfig.popupColumns("row", legacyGrid = true))
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

    @Test
    fun theSettingOffersEveryShapeAndShipsTheCodeDefault() {
        val arrays = res("values/arrays.xml")
        val values = items(arrays, "popup_shape_values")
        assertEquals(listOf(0, 3, 4, 5), values.map { KeyboardConfig.popupColumns(it, legacyGrid = false) })
        assertEquals(values.size, items(arrays, "popup_shape_entries").size)
        val prefs = res("xml/keyboard_prefs.xml")
        val default = Regex("""android:key="${Prefs.POPUP_SHAPE}"[^>]*?android:defaultValue="([^"]+)"""")
            .find(prefs)?.groupValues?.get(1)
        assertEquals(Prefs.DEFAULT_POPUP_SHAPE, default)
    }
}
