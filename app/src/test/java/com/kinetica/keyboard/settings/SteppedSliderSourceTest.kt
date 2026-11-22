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
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * `SteppedSliderPreference.onGetDefaultValue` must not read the `steps` field.
 *
 * The androidx `Preference` base constructor calls `onGetDefaultValue` before this subclass's
 * `init` block has set `steps`, so reading it there throws an NPE on every settings open. A
 * preference cannot be inflated in a JVM test (it needs a Context and real resources), so the
 * guard is a source scan, like `PreferenceTreeTest` and `InitOrderTest`.
 *
 * Read off disk, so a fail-first check here needs `--rerun-tasks`.
 */
class SteppedSliderSourceTest {

    private fun source(): String {
        val direct = Paths.get("src/main/java/com/kinetica/keyboard/settings/SteppedSliderPreference.kt")
        val p: Path = if (Files.exists(direct)) {
            direct
        } else {
            Paths.get("app/src/main/java/com/kinetica/keyboard/settings/SteppedSliderPreference.kt")
        }
        assumeTrue("SteppedSliderPreference.kt not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }

    @Test
    fun onGetDefaultValueNeverReadsTheStepTable() {
        val src = source()
        // The single-expression body: everything between the signature and the end of its line.
        val line = Regex("""fun onGetDefaultValue\([^)]*\)[^\n]*""").find(src)?.value
        requireNotNull(line) { "onGetDefaultValue not found" }
        assertFalse(
            "onGetDefaultValue reads steps, which is null when the base constructor calls it",
            line.contains("steps"),
        )
    }
}
