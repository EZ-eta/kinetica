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

import com.kinetica.keyboard.engine.HandoverDp.Contact
import com.kinetica.keyboard.engine.HandoverDp.Mode
import com.kinetica.keyboard.engine.models.StreamId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two-cursor reachability measure, on hand-built timelines. */
class HandoverDpTest {

    /**
     * `world` as the corpus delivers it: the right thumb enters `l` 30 ms before the left
     * thumb enters `r`, so the contacts read `wolrd` in entry order. Held keys resolve it:
     * `r` is left before `l` is.
     */
    private val wolrd = mapOf(
        StreamId.LEFT to listOf(Contact('w', 0, 60), Contact('r', 150, 250), Contact('d', 300, 420)),
        StreamId.RIGHT to listOf(Contact('o', 50, 120), Contact('l', 120, 280)),
    )

    @Test
    fun aWordWhoseContactsInvertIsNotSpelledInTimeOrder() {
        assertFalse(HandoverDp.spelledInTimeOrder("world", wolrd, Mode.ENTRY))
        assertTrue(HandoverDp.spelledInTimeOrder("world", wolrd, Mode.EXIT))
    }

    @Test
    fun theSlackIsTheLargestStepBackAHandOverNeeds() {
        assertEquals(30.0, HandoverDp.minSlack("world", wolrd, Mode.ENTRY), 1e-9)
        assertEquals(0.0, HandoverDp.minSlack("world", wolrd, Mode.EXIT), 1e-9)
    }

    @Test
    fun aLetterNoThumbTouchedIsUnreachableWithoutAMiss() {
        val streams = mapOf(StreamId.LEFT to listOf(Contact('w', 0, 60), Contact('d', 300, 420)))
        assertEquals(0.0, HandoverDp.minSlack("wd", streams, Mode.ENTRY, 0), 1e-9)
        assertTrue(HandoverDp.minSlack("wad", streams, Mode.ENTRY, 0).isInfinite())
        assertEquals(0.0, HandoverDp.minSlack("wad", streams, Mode.ENTRY, 1), 1e-9)
    }

    /**
     * The earliest `c` on the left thumb is 250 ms before the right thumb's `b`; the later one
     * is after it. Earliest-match takes the first and pays 250; the exact search takes the
     * second and pays nothing, so the greedy answer would overstate the cost.
     */
    @Test
    fun theExactSearchFindsAReadingEarliestMatchMisses() {
        val streams = mapOf(
            StreamId.LEFT to listOf(
                Contact('a', 0, 20), Contact('c', 50, 70), Contact('a', 200, 220), Contact('c', 400, 420),
            ),
            StreamId.RIGHT to listOf(Contact('b', 300, 320)),
        )
        assertEquals(250.0, HandoverDp.greedySlack("abc", streams, Mode.ENTRY), 1e-9)
        assertEquals(0.0, HandoverDp.minSlack("abc", streams, Mode.ENTRY), 1e-9)
    }

    @Test
    fun aDoubleLetterNeedsOneContact() {
        assertEquals("helo", HandoverDp.normalise("hello"))
        assertEquals("perche", HandoverDp.normalise("perché"))
    }
}
