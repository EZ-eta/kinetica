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
 * A cursor moved by the user re-decides the shift from the text before the new position.
 *
 * The shift is otherwise decided only after the keyboard's own edits, so a period typed and the
 * cursor moved back into the sentence capitalized the next letter. `onUpdateSelection` needs a
 * live editor, so the guard is a source scan, like `SteppedSliderSourceTest`. The decision
 * itself is `startsNewSentence`, covered by `SentenceCapsTest`.
 *
 * Read off disk ([ImeSources]), so a fail-first check here needs `--rerun-tasks`.
 */
class CursorMoveCapsSourceTest {

    @Test
    fun aUserCursorMoveRedecidesTheShift() {
        val src = ImeSources.declaring("override fun onUpdateSelection(").text
        val start = src.indexOf("override fun onUpdateSelection(")
        val end = src.indexOf("\n    }\n", start)
        val body = src.substring(start, end)
        // Past the early return for the keyboard's own reports: only a user move re-decides.
        val userPath = body.substring(body.indexOf("return\n"))
        assertTrue(
            "onUpdateSelection's user path never calls updateAutoShift",
            userPath.contains("updateAutoShift()"),
        )
    }
}
