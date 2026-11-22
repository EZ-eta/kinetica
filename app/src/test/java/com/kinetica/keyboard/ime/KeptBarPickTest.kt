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
import org.junit.Test

/**
 * A pick from a bar the stale timeout kept up.
 *
 * The timeout zeroes the word state but keeps a bar whose words came from the other language,
 * since the user is reading them. A pick then has no tentative length to trust, and counting
 * from a remembered one once ate text, so the span is re-proved against the editor or the pick is
 * refused.
 */
class KeptBarPickTest {

    @Test
    fun anUntouchedEditorGivesTheStaleWordsLength() {
        // A two-token buffer whose first decode put `impo` on screen, then went no-native.
        assertEquals(4, keptBarPickSpan("I said impo", "I said impo", "impo"))
    }

    @Test
    fun aGestureThatNeverDecodedReplacesNothing() {
        // One swipe, no earlier decode: nothing of it is on screen, so the pick inserts.
        assertEquals(0, keptBarPickSpan("I said ", "I said ", ""))
    }

    @Test
    fun anythingTypedOrDeletedSinceRefusesThePick() {
        assertEquals(-1, keptBarPickSpan("I said impo ", "I said impo", "impo"))
        assertEquals(-1, keptBarPickSpan("I said imp", "I said impo", "impo"))
        assertEquals(-1, keptBarPickSpan("I said ", "I said impo", "impo"))
    }

    @Test
    fun aStaleWordTheEditorDoesNotEndWithIsRefused() {
        // A word typed straight after the previous one with no space: trailingLetterRun
        // would read `helloimpo` and replace both. Only the stale word itself may go.
        assertEquals(4, keptBarPickSpan("helloimpo", "helloimpo", "impo"))
        assertEquals(-1, keptBarPickSpan("hello", "hello", "impo"))
    }
}
