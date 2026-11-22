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

package com.kinetica.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `?123` menu drawn on the spacebar while chord labels are on: on the bar, a thumb's width
 * per cell, and inside the view however many cells the menu holds.
 */
class StripOverKeyTest {

    @Test
    fun aFewCellsShareTheSpacebar() {
        // 1080px at 2.75: a spacebar half the view holds four cells of its own width.
        val density = 2.75f
        val cell = stripCellOver(540f, 4, 48f * density, 1080f, 4f * density)
        assertEquals(135f, cell, 0.01f)
        assertEquals(270f, popupLeft(270f, cell * 4, 1080f, 4f * density), 0f)
    }

    @Test
    fun aLongMenuKeepsThumbCellsInsideTheView() {
        for (px in 600..1600 step 13) {
            for (dpi in 260..640 step 20) {
                val density = dpi / 160f
                val viewW = px.toFloat()
                val margin = 4f * density
                val spaceLeft = viewW * 0.25f
                for (n in 1..12) {
                    val cell = stripCellOver(viewW * 0.5f, n, 48f * density, viewW, margin)
                    val left = popupLeft(spaceLeft, cell * n, viewW, margin)
                    val fits = 48f * density * n <= viewW - 2f * margin
                    if (fits) assertTrue("px=$px dpi=$dpi n=$n cell=$cell", cell >= 48f * density - 0.01f)
                    assertTrue("px=$px dpi=$dpi n=$n left=$left", left >= margin - 0.01f)
                    assertTrue("px=$px dpi=$dpi n=$n", left + cell * n <= viewW - margin + 0.01f)
                }
            }
        }
    }
}
