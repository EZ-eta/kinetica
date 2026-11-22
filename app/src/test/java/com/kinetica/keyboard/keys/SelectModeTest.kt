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

package com.kinetica.keyboard.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Select on the editing page: which outputs gain Shift, and what ends it. */
class SelectModeTest {

    @Test
    fun aMoveGainsShift() {
        assertEquals("combo:shift+left", SelectMode.withShift(EditorAction.ARROW_LEFT.output))
        assertEquals("combo:shift+down", SelectMode.withShift(EditorAction.ARROW_DOWN.output))
        assertEquals("combo:shift+home", SelectMode.withShift(EditorAction.HOME.output))
        assertEquals("combo:shift+pagedown", SelectMode.withShift(EditorAction.PAGE_DOWN.output))
        assertEquals("combo:ctrl+shift+left", SelectMode.withShift("combo:ctrl+left"))
        assertEquals("combo:shift+end", SelectMode.withShift("combo:end"))
    }

    @Test
    fun anythingElseIsSentAsItIs() {
        for (o in listOf(
            EditorAction.COPY.output, EditorAction.FORWARD_DELETE.output, EditorAction.TAB.output,
            "combo:ctrl+a", "combo:ctrl+delete", "x", "hello",
        )) {
            assertEquals(o, SelectMode.withShift(o))
        }
    }

    @Test
    fun copyCutAndPasteEndIt() {
        assertTrue(SelectMode.clearsAfter(EditorAction.COPY))
        assertTrue(SelectMode.clearsAfter(EditorAction.CUT))
        assertTrue(SelectMode.clearsAfter(EditorAction.PASTE))
        assertFalse(SelectMode.clearsAfter(EditorAction.SELECT_ALL))
        assertFalse(SelectMode.clearsAfter(EditorAction.UNDO))
    }
}
