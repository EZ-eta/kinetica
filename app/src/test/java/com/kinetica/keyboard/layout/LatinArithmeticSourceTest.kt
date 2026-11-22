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

package com.kinetica.keyboard.layout

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * No code turns a character into a letter code by subtracting `'a'`.
 *
 * That arithmetic assumes a-z: on a Cyrillic board `й - 'a'` is 984, and the key burst indexed
 * an array of 63 with it, crashing Russian, Hebrew and Arabic on the first touch. A view's touch
 * path has no JVM reach, so the guard is a source scan like `SteppedSliderSourceTest`. A letter's
 * code comes from its alphabet (`Key.letterCode`, `Alphabet.codeOf`); a chord from
 * `Key.chordChar`, the character itself.
 *
 * Read off disk, so a fail-first check here needs `--rerun-tasks`.
 */
class LatinArithmeticSourceTest {

    private fun mainSources(): List<Path> {
        val root = listOf(Paths.get("src/main/java"), Paths.get("app/src/main/java")).firstOrNull { Files.isDirectory(it) }
        assumeTrue("main sources not found", root != null)
        return Files.walk(root!!).use { s -> s.filter { it.toString().endsWith(".kt") }.toList() }
    }

    private val arithmetic = Regex("""-\s*'a'|\.minus\(\s*'a'\s*\)""")

    @Test
    fun noLetterCodeIsCountedFromA() {
        val hits = ArrayList<String>()
        for (p in mainSources()) {
            val lines = Files.readAllLines(p)
            for ((i, line) in lines.withIndex()) {
                if (line.trimStart().startsWith("//") || line.trimStart().startsWith("*")) continue
                if (arithmetic.containsMatchIn(line)) hits.add("${p.fileName}:${i + 1}: ${line.trim()}")
            }
        }
        assertEquals("letter codes counted from 'a':\n" + hits.joinToString("\n"), emptyList<String>(), hits)
    }
}
