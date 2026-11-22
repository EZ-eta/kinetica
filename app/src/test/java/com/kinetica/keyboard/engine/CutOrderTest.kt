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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order the in-search cut resumes a tail in, against the order the finished-sequence
 * generators use (`MergeAlternatives.orderByTime`): a gesture starting at an instant goes
 * before a piece resuming at it, because the cut was placed at that start.
 */
class CutOrderTest {

    @Test
    fun aTokenStartingAtTheCutGoesBetweenTheHalves() {
        // Every cut at another token's own start ties: a swipe's first contact enters at its
        // tStart, and cutCandidates offers that instant. Reading tail-first there spells the
        // swipe whole and the other thumb after it, which no generator order would build.
        assertFalse("the token starting at the cut must come first", tailResumesFirst(1_000, 1_000))
    }

    @Test
    fun aTokenStartingEarlierStillGoesFirstAndALaterOneWaits() {
        assertFalse(tailResumesFirst(nextStart = 900, cutAt = 1_000))
        assertTrue(tailResumesFirst(nextStart = 1_100, cutAt = 1_000))
    }
}
