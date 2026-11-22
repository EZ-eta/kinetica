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
 * The retraction asks the engine whether the other thumb's swipe began the word.
 *
 * The rule is `retractsAutospace`, covered by `TapAutospaceTest`; the engine's answer by
 * `GestureEngineTest`. The call between them needs a live editor, so the guard is a source scan.
 *
 * Read off disk ([ImeSources]), so a fail-first check here needs `--rerun-tasks`.
 */
class RetractSwipeHeldSourceTest {

    @Test
    fun theRetractionPassesTheHeldSwipe() {
        val src = ImeSources.declaring("fun retractBeforeLetter(").text
        val start = src.indexOf("fun retractBeforeLetter(")
        val body = src.substring(start, src.indexOf("\n    }\n", start))
        assertTrue(
            "retractBeforeLetter never asks swipeHeldSince",
            body.contains("val held = ime.engine.swipeHeldSince(tStart)") && body.contains("swipeStartedFirst = held"),
        )
    }
}
