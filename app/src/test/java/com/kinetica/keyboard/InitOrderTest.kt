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

package com.kinetica.keyboard

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * No class-level property may be declared below its own `init` block.
 *
 * Kotlin runs property initializers and `init` blocks in declaration order, so a property
 * written below `init` is still null while `init` runs and anything `init` calls that reads it
 * throws out of the constructor. On the key-handling path that kills the keyboard:
 * `EmojiPickerView.glyphPaint` sat eleven lines too low and the emoji panel took the whole IME
 * down.
 *
 * Reads the source off disk, like [com.kinetica.keyboard.ui.EmojiDataTest] and
 * [com.kinetica.keyboard.settings.PreferenceTreeTest], so a fail-first check here needs
 * `--rerun-tasks`.
 *
 * A heuristic: a property never touched from `init` is safe anywhere, so a harmless case may
 * trip it. Move the property above `init` or make it a function; relaxing the test needs a
 * stated reason, because what it prevents is a dead keyboard.
 */
class InitOrderTest {

    private fun sourceRoot(): Path {
        val direct = Paths.get("src/main/java")
        return if (Files.exists(direct)) direct else Paths.get("app/src/main/java")
    }

    /** Class-member `init`, at exactly one indent level. */
    private val initBlock = Regex("""^ {4}init\s*\{""")

    /**
     * A class-member property whose value is computed at construction, so its position matters.
     * Two shapes qualify: an `=` initializer and a `by` delegate, whose delegate object is itself
     * built by an initializer.
     *
     * The type may not contain brackets, which excludes `val x: Boolean get = ...`: a computed
     * property with no backing field is safe anywhere.
     */
    private val initialisedProperty =
        Regex("""^ {4}(?:private |internal |protected |public )?(?:@\w+\s+)?(?:val|var)\s+(\w+)\s*(?::\s*[\w<>?.,\s\[\]]+?)?\s*(?:=|\bby\b)\s""")

    @Test
    fun noPropertyIsDeclaredBelowItsOwnInitBlock() {
        val root = sourceRoot()
        assumeTrue("source tree not found", Files.exists(root))
        val files = Files.walk(root).use { s ->
            s.filter { it.toString().endsWith(".kt") }.collect(Collectors.toList())
        }
        assumeTrue("no sources found", files.isNotEmpty())

        val offenders = ArrayList<String>()
        for (f in files) {
            val lines = Files.readAllLines(f)
            val init = lines.indexOfFirst { initBlock.containsMatchIn(it) }
            if (init < 0) continue
            for (i in init until lines.size) {
                val m = initialisedProperty.find(lines[i]) ?: continue
                offenders += "${f.fileName}:${i + 1} ${m.groupValues[1]} is below init at line ${init + 1}"
            }
        }
        assertTrue(
            "a property below its own init block is null while init runs:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun theCheckActuallyLooksAtSomething() {
        // A regex that matched nothing would pass the test above forever.
        val root = sourceRoot()
        assumeTrue("source tree not found", Files.exists(root))
        val withInit = Files.walk(root).use { s ->
            s.filter { it.toString().endsWith(".kt") }
                .filter { f -> Files.readAllLines(f).any { initBlock.containsMatchIn(it) } }
                .count()
        }
        assertTrue("no class with an init block was found, so the walk is broken", withInit > 0)
    }
}
