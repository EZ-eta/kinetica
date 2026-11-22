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
 * The spacebar's cursor slide ticks once per step, as the backspace slide ticks once per unit, and
 * only where it moves the field's cursor. The haptic call needs a live service, so the guard reads
 * the source ([ImeSources]); a fail-first check here needs `--rerun-tasks`.
 */
class CursorSlideTickSourceTest {

    @Test
    fun eachCursorStepTicksUnderTheVibrationSetting() {
        val src = ImeSources.declaring("override fun onCursorMove(").text
        val start = src.indexOf("override fun onCursorMove(")
        val body = src.substring(start, src.indexOf("\n        }\n", start))
        val search = body.indexOf("if (emojiSearch.active) return")
        val tick = body.indexOf("views.vibrateForKeyPress()")
        assertTrue("a cursor step does not tick", tick >= 0)
        assertTrue("a step under the emoji search ticks", search in 0 until tick)
        val backspace = ImeSources.declaring("fun stage(units: Int, granularity: DeleteSpan.Granularity)").text
        assertTrue("the backspace slide no longer ticks", backspace.contains("ime.views.vibrateForKeyPress()"))
    }
}
