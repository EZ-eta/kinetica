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
 * One-handed for the left hand: the right-hand board mirrored, the same size and height (#29).
 * Left-aligned keeps the full height.
 */
class OneHandedLeftTest {

    @Test
    fun theLeftBoardMirrorsTheRightOne() {
        for (x in listOf(0f, 0.15f, 0.5f, 0.85f, 1f)) {
            val right = LayoutTransforms.oneHandedX(x, left = false)
            val mirrored = 1f - LayoutTransforms.oneHandedX(1f - x, left = true)
            assertEquals(right, mirrored, 1e-6f)
        }
        assertEquals(0f, LayoutTransforms.oneHandedX(0f, left = true), 0f)
        assertEquals(1f, LayoutTransforms.oneHandedX(1f, left = false), 1e-6f)
        // The same height either hand: anchored to the bottom.
        assertEquals(1f, LayoutTransforms.oneHandedY(1f), 1e-6f)
    }

    @Test
    fun keyWidthsAreTheSameEitherHand() {
        val w = LayoutTransforms.oneHandedX(0.6f, true) - LayoutTransforms.oneHandedX(0.5f, true)
        val h = LayoutTransforms.oneHandedY(0.5f) - LayoutTransforms.oneHandedY(0.25f)
        assertEquals(LayoutTransforms.oneHandedX(0.6f, false) - LayoutTransforms.oneHandedX(0.5f, false), w, 1e-6f)
        // Both axes scale alike, so row pitch in key widths is unchanged.
        assertEquals(0.25f / 0.1f, h / w, 1e-4f)
    }

    @Test
    fun theSettingListsIt() {
        assertEquals(LayoutMode.ONE_HANDED_LEFT, LayoutMode.fromPref("one_handed_left"))
        val direct = Paths.get("src/main/res/values/arrays.xml")
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/src/main/res/values/arrays.xml")
        assumeTrue(Files.exists(p))
        val xml = Files.newBufferedReader(p).use { it.readText() }
        val values = Regex("""<string-array name="layout_mode_values"[^>]*>(.*?)</string-array>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)!!.groupValues[1]
        val listed = Regex("""<item>([^<]*)</item>""").findAll(values).map { LayoutMode.fromPref(it.groupValues[1]) }.toSet()
        assertEquals(LayoutMode.entries.toSet(), listed)
    }
}
