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

import com.kinetica.keyboard.keys.EditorAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home or End pressed again goes on a line; at a line's edge a second press did nothing. */
class LineEdgeRepeatTest {

    private val home = EditorAction.HOME
    private val end = EditorAction.END

    @Test
    fun aSecondAndThirdPressGoOn() {
        val r = LineEdgeRepeat()
        assertFalse(r.press(home, now = 0))
        assertTrue(r.press(home, now = 900))
        assertTrue(r.press(home, now = 1_800))
    }

    @Test
    fun theOtherEdgeStartsAgain() {
        val r = LineEdgeRepeat()
        r.press(home, now = 0)
        assertFalse(r.press(end, now = 500))
        assertTrue(r.press(end, now = 1_000))
    }

    @Test
    fun anotherCommandOrKeyBetweenEndsTheRun() {
        val r = LineEdgeRepeat()
        r.press(home, now = 0)
        r.press(null, now = 300)
        assertFalse(r.press(home, now = 600))
        r.clear()
        assertFalse(r.press(home, now = 900))
    }

    @Test
    fun theEdgeKeysOwnReportKeepsTheRun() {
        // A key event's move is not expected by the ledger, so the editor's report reads as the user's.
        val r = LineEdgeRepeat()
        r.press(home, now = 0)
        r.onUserMove(now = 40)
        r.onUserMove(now = 120)
        assertTrue(r.press(home, now = 700))
    }

    @Test
    fun aMoveByTheUserEndsTheRun() {
        val r = LineEdgeRepeat()
        r.press(end, now = 0)
        r.onUserMove(now = 40)
        r.onUserMove(now = 1_500)
        assertFalse(r.press(end, now = 2_000))
    }

    @Test
    fun onlyHomeAndEndAreEdges() {
        assertEquals(home, LineEdgeRepeat.edgeOf(home.output))
        assertEquals(end, LineEdgeRepeat.edgeOf(end.output))
        assertNull(LineEdgeRepeat.edgeOf(EditorAction.ARROW_LEFT.output))
        assertNull(LineEdgeRepeat.edgeOf("combo:shift+home"))
        assertNull(LineEdgeRepeat.edgeOf("h"))
    }

    @Test
    fun theStepGoesBackForHomeAheadForEnd() {
        assertEquals(EditorAction.ARROW_LEFT, LineEdgeRepeat.stepPast(home, "first line\n", "second"))
        assertEquals(EditorAction.ARROW_RIGHT, LineEdgeRepeat.stepPast(end, "first line", "\nsecond"))
    }

    @Test
    fun rightToLeftTextTurnsTheStepRound() {
        assertEquals(EditorAction.ARROW_RIGHT, LineEdgeRepeat.stepPast(home, "שלום\n", "עולם"))
        assertEquals(EditorAction.ARROW_LEFT, LineEdgeRepeat.stepPast(end, "שלום", "\nעולם"))
    }
}
