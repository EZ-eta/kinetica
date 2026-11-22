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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How much of the last commit backspace has eaten, the reading behind the trace's
 * `backspace into commit` line. The fixture is a captured `provarne` that retype missed.
 */
class BackspaceLeftTest {

    private val max = 8

    @Test
    fun theWordIsWholeWhileOnlyItsSpaceGoes() {
        assertEquals(-1, backspaceLeft("il proverbio provarne ", "provarne", max))
        assertEquals(-1, backspaceLeft("il proverbio provarne", "provarne", max))
    }

    @Test
    fun eachLetterEatenCountsDownToZero() {
        assertEquals(7, backspaceLeft("il proverbio provarn", "provarne", max))
        assertEquals(1, backspaceLeft("il proverbio p", "provarne", max))
        assertEquals(0, backspaceLeft("il proverbio ", "provarne", max))
    }

    @Test
    fun theCursorLeavingTheWordEndsTheCount() {
        // The space before it has gone too: the run is now the previous word.
        assertNull(backspaceLeft("il proverbio", "provarne", max))
        // A different word typed in its place is not the commit being eaten.
        assertNull(backspaceLeft("il proverbio x", "provarne", max))
    }
}
