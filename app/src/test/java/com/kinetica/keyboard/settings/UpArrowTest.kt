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
 * Every screen that shows the back arrow handles it. The arrow alone does nothing: Text
 * expansions showed it without the handler, and only the system back left the screen.
 *
 * Read off disk, so a fail-first check here needs `--rerun-tasks`.
 */
class UpArrowTest {

    private fun sources(): List<Path> {
        val rel = "src/main/java/com/kinetica/keyboard"
        val direct = Paths.get(rel)
        val root: Path = if (Files.exists(direct)) direct else Paths.get("app/$rel")
        assumeTrue("$rel not found", Files.exists(root))
        return Files.walk(root).use { s -> s.filter { it.toString().endsWith("Activity.kt") }.toList() }
    }

    @Test
    fun everyScreenWithTheArrowHandlesIt() {
        val shown = sources().filter { p ->
            Files.newBufferedReader(p).use { it.readText() }.contains("setDisplayHomeAsUpEnabled(true)")
        }
        assertTrue("no screen shows the arrow", shown.size >= 9)
        val unhandled = shown.filterNot { p ->
            Files.newBufferedReader(p).use { it.readText() }.contains("override fun onSupportNavigateUp()")
        }
        assertTrue("arrow without a handler: ${unhandled.map { it.fileName }}", unhandled.isEmpty())
    }

}
