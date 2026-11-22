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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The Add button sits above the list on both shortcut screens, so a long list needs no scroll to
 * add one. An Activity needs a Context, so the order is read off the source.
 *
 * Read off disk, so a fail-first check here needs `--rerun-tasks`.
 */
class AddButtonPlacementTest {

    private fun source(file: String): String {
        val rel = "src/main/java/com/kinetica/keyboard/settings/$file"
        val direct = Paths.get(rel)
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/$rel")
        assumeTrue("$file not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }

    private fun assertAddAboveList(file: String, addString: String) {
        val src = source(file)
        val add = src.indexOf("R.string.$addString")
        val list = src.indexOf("root.addView(listContainer)")
        require(add >= 0 && list >= 0) { "$file: markers not found" }
        assertTrue("$file adds its Add button below the list", add < list)
    }

    @Test
    fun chordsAddAboveTheList() = assertAddAboveList("ChordSettingsActivity.kt", "chord_add")

    @Test
    fun expansionsAddAboveTheList() = assertAddAboveList("ExpansionSettingsActivity.kt", "expansion_add")
}
