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
 * A settings row follows a value the keyboard writes while the screen is open: a language change
 * sets the letter arrangement beside it, drag-resize the height. A stale row touched afterwards
 * writes the old value back. A preference cannot be inflated in a JVM test, so the wiring is a
 * source scan, like [SteppedSliderSourceTest]. Read off disk: a fail-first check needs `--rerun-tasks`.
 */
class RowSyncSourceTest {

    private fun source(name: String): String {
        val direct = Paths.get("src/main/java/com/kinetica/keyboard/settings/$name")
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/src/main/java/com/kinetica/keyboard/settings/$name")
        assumeTrue("$name not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }

    private fun body(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("$signature not found", start >= 0)
        return src.substring(start, src.indexOf("\n    }\n", start))
    }

    @Test
    fun theFragmentResyncsOnResumeAndListensUntilPause() {
        val src = source("KeyboardPrefsFragment.kt")
        val resume = body(src, "override fun onResume()")
        assertTrue("onResume must resync the screen", resume.contains("RowSync.applyAll("))
        assertTrue("onResume must listen", resume.contains("registerOnSharedPreferenceChangeListener(rowSync)"))
        assertTrue("onPause must stop listening", body(src, "override fun onPause()").contains("unregisterOnSharedPreferenceChangeListener(rowSync)"))
    }

    @Test
    fun everyKindOfRowIsSet() {
        val src = source("RowSync.kt")
        for (kind in listOf(
            "TwoStatePreference", "ListPreference", "SeekBarPreference", "EditTextPreference",
            "MultiSelectListPreference", "SteppedSliderPreference", "ColorHexPreference",
        )) {
            assertTrue("$kind rows are not followed", src.contains("is $kind ->"))
        }
    }
}
