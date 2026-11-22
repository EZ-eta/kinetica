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
 * The line COPY_LINE puts on the clipboard (#19: "copy whole line").
 *
 * Read either side of the cursor in one call, with no selection and no remembered offset, so
 * a remembered offset cannot eat text. A line that runs past the read is refused, never cut.
 */
class LineAroundCursorTest {

    private val read = 50

    @Test
    fun theLineIsBothSidesOfTheCursorUpToTheNewlines() {
        assertEquals("hello", lineAroundCursor("first\nhel", "", "lo\nthird", read))
        assertEquals("only line", lineAroundCursor("only ", "", "line", read))
    }

    @Test
    fun aSelectionInsideTheLineIsPartOfIt() {
        assertEquals("hello", lineAroundCursor("a\nh", "ell", "o\nb", read))
        assertNull(lineAroundCursor("a\nh", "ell\nx", "o", read))
    }

    @Test
    fun aLineRunningPastTheReadIsRefused() {
        val full = "x".repeat(read)
        assertNull(lineAroundCursor(full, "", "end", read))
        assertNull(lineAroundCursor("start", "", full, read))
        // A newline inside the read means the line's end was seen.
        assertEquals("y", lineAroundCursor("x".repeat(read - 2) + "\ny", "", "", read))
    }

    @Test
    fun anEmptyLineIsEmpty() {
        assertEquals("", lineAroundCursor("a\n", "", "\nb", read))
    }
}
