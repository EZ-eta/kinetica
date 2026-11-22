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

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tapped word's space that a letter does not take back leaves one line naming why.
 *
 * The reason is `retractRefusal`, covered by `TapAutospaceTest`; the line needs a live editor, so
 * the guard is a source scan. Read off disk ([ImeSources]), so a fail-first check here needs
 * `--rerun-tasks`.
 */
class RetractKeepTraceSourceTest {

    @Test
    fun aKeptSpaceIsTracedWithItsGate() {
        val src = ImeSources.declaring("fun retractBeforeLetter(").text
        val start = src.indexOf("fun retractBeforeLetter(")
        val body = src.substring(start, src.indexOf("\n    }\n", start))
        assertTrue("retractBeforeLetter asks no reason", body.contains("val why = retractRefusal("))
        assertTrue(
            "a kept space leaves no line",
            body.contains("autospace keep why=\$why elapsed=\$elapsed window="),
        )
        assertTrue(
            "the line is not scoped to a tapped word's space",
            body.contains("} else if (fromTaps && inserted && ime.wordState.tentativeLength == 0) {"),
        )
    }
}
