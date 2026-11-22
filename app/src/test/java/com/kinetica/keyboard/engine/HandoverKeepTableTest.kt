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

class HandoverKeepTableTest {
    private fun formula(back: Long): Float =
        Math.pow(KineticaConstants.HANDOVER_CHARGE_KEEP.toDouble(), KineticaConstants.handoverExcess(back).toDouble()).toFloat()

    @Test
    fun everyStepUpToTheChargeMaxReadsItsFormulaWithinOneUlp() {
        val from = KineticaConstants.HANDOVER_ALLOWANCE_MS + 1
        for (back in from..KineticaConstants.HANDOVER_CHARGE_MAX_MS) {
            val got = KineticaConstants.handoverKeep(back)
            assertTrue("$back ms: $got against ${formula(back)}", abs(got.toRawBits() - formula(back).toRawBits()) <= 1)
        }
    }

    @Test
    fun insideTheAllowanceKeepsAllAndPastTheTableTheFormulaRuns() {
        assertEquals(1f, KineticaConstants.handoverKeep(KineticaConstants.HANDOVER_ALLOWANCE_MS), 0f)
        assertEquals(1f, KineticaConstants.handoverKeep(0L), 0f)
        val past = KineticaConstants.HANDOVER_CHARGE_MAX_MS + 250
        assertEquals(formula(past), KineticaConstants.handoverKeep(past), 0f)
    }
}
