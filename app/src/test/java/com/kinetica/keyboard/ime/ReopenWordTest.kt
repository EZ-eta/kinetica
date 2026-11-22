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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape half of reopening a word when the cursor is moved into it.
 *
 * reloadWordUnderCursor decides whether a word is there; this decides only whether the
 * selection change could reopen one. It is pure, like hugsPreviousWord and startsNewSentence,
 * because the service around it has no JVM reach.
 */
class ReopenWordTest {

    @Test
    fun aCollapsedCursorAfterSomeTextCanReopen() {
        assertTrue(reopensWordUnderCursor(selectionLength = 0, selStart = 5, selEnd = 5))
        assertTrue(reopensWordUnderCursor(selectionLength = 0, selStart = 1, selEnd = 1))
    }

    @Test
    fun aSelectionIsNeverAReopen() {
        // The user is acting on a range, not appending to a word. Reopening here would
        // seed a composer whose text the next edit is about to replace.
        assertFalse(reopensWordUnderCursor(selectionLength = 4, selStart = 2, selEnd = 6))
    }

    @Test
    fun theStartOfTheFieldIsNeverAReopen() {
        // Nothing precedes offset 0, so there is no word to come back to.
        assertFalse(reopensWordUnderCursor(selectionLength = 0, selStart = 0, selEnd = 0))
    }

    @Test
    fun aDisagreeingSelectionIsNotCollapsed() {
        // selectionLength is computed from the cached, normalized offsets while the
        // callback carries the editor's raw ones; if they disagree the cursor is not
        // where this rule assumes, so it declines instead of guessing.
        assertFalse(reopensWordUnderCursor(selectionLength = 0, selStart = 3, selEnd = 7))
    }
}
