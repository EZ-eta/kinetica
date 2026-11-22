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

package com.kinetica.keyboard.onboarding

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The setup activity's lines a JVM test cannot run: it needs a Context. Each guard is a source
 * scan of the line that carries the behaviour. Read off disk: a fail-first check needs `--rerun-tasks`.
 */
class SetupSourceTest {

    private fun source(): String {
        val rel = "src/main/java/com/kinetica/keyboard/onboarding/MainActivity.kt"
        val direct = Paths.get(rel)
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/$rel")
        assumeTrue("MainActivity.kt not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }

    private fun body(src: String, signature: String): String {
        val start = src.indexOf(signature)
        require(start >= 0) { "$signature not found" }
        return src.substring(start, src.indexOf("\n    }\n", start))
    }

    /** A generated id differs after a rotation, and the restored step fragment has nowhere to go. */
    @Test
    fun theStepHostHasAFixedId() {
        val src = source()
        assertTrue(src.contains("id = R.id.setup_body"))
        assertFalse(src.contains("generateViewId"))
    }

    /** The keyboard picker is a dialog: without the focus callback, choosing Kinetica moves nothing. */
    @Test
    fun theFocusReturnRereadsTheKeyboardState() {
        val focus = body(source(), "override fun onWindowFocusChanged(")
        assertTrue(focus, focus.contains("if (hasFocus) refresh()"))
    }
}
