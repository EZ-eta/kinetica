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

package com.kinetica.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln

class BeamPullTableTest {
    @Test
    fun everyByteReadsItsFormulaWithinOneUlp() {
        assertEquals(256, FREQ_PULL_LN.size)
        for (b in 0..255) {
            val fw = KineticaConstants.FREQ_WEIGHT_FLOOR + (1f - KineticaConstants.FREQ_WEIGHT_FLOOR) * b / 255f
            val want = ln(fw)
            assertTrue("byte $b: ${FREQ_PULL_LN[b]} against $want", abs(FREQ_PULL_LN[b].toRawBits() - want.toRawBits()) <= 1)
        }
    }
}
