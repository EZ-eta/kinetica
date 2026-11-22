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
 * How a bar word too wide for its place is shortened. Every letter measures one unit here, so a
 * width is a letter count, the ellipsis included.
 */
class BarFitTest {

    private val units: (String) -> Float = { it.codePointCount(0, it.length).toFloat() }

    private fun fit(word: String, width: Float, middle: Boolean) = BarFit.fit(word, width, middle, units)

    @Test
    fun aWordThatFitsIsShownWhole() {
        assertEquals("organizzazione", fit("organizzazione", 14f, middle = false))
        assertEquals("organizzazione", fit("organizzazione", 14f, middle = true))
    }

    @Test
    fun theEndCutKeepsTheStartAsTheBarAlwaysDid() {
        assertEquals("organiz…", fit("organizzazione", 8f, middle = false))
        assertEquals("org…", fit("organizzazione", 1f, middle = false))
    }

    /** `organizzare` and `organizzazione` both read `organiz…` cut at the end; their endings tell them apart. */
    @Test
    fun theMiddleCutKeepsTheStartAndTheEnd() {
        val cut = fit("organizzazione", 12f, middle = true)
        assertEquals("organi…zione", cut)
        assertTrue(fit("organizzare", 8f, middle = true).endsWith("are"))
        assertTrue(fit("organizzazione", 8f, middle = true).endsWith("one"))
    }

    @Test
    fun theMiddleCutKeepsTwoLettersASideHoweverNarrow() {
        assertEquals("or…ne", fit("organizzazione", 1f, middle = true))
    }

    @Test
    fun aLetterOutsideTheBmpIsNeverSplit() {
        // Seven mathematical letters, each a surrogate pair.
        assertEquals("𝔞𝔟…𝔣𝔤", fit("𝔞𝔟𝔠𝔡𝔢𝔣𝔤", 5f, middle = true))
    }
}
