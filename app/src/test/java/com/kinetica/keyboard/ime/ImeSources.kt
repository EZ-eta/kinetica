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

package com.kinetica.keyboard.ime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assume.assumeTrue

/**
 * Every Kotlin file of the `ime` package, read off disk, for the guards that need a live editor
 * and scan the source instead.
 *
 * The whole package, not one file: code moves between the service and its collaborators, and a
 * guard pinned to one path skipped silently once its code left. A symbol a guard asks for and
 * finds nowhere fails it.
 */
internal object ImeSources {

    class Source(val name: String, val text: String) {
        fun lineOf(offset: Int): Int = text.substring(0, offset).count { it == '\n' } + 1
    }

    fun all(): List<Source> {
        val rel = "src/main/java/com/kinetica/keyboard/ime"
        val direct = Paths.get(rel)
        val dir: Path = if (Files.isDirectory(direct)) direct else Paths.get("app/$rel")
        assumeTrue("ime sources not found", Files.isDirectory(dir))
        return Files.list(dir).use { files ->
            files.filter { it.toString().endsWith(".kt") }
                .sorted()
                .map { p -> Source(p.fileName.toString(), Files.newBufferedReader(p).use { it.readText() }) }
                .toList()
        }
    }

    /** The one file that declares [declaration]; fails when none or several do. */
    fun declaring(declaration: String): Source {
        val found = all().filter { it.text.contains(declaration) }
        require(found.size == 1) { "$declaration found in ${found.map { it.name }}" }
        return found.single()
    }
}
