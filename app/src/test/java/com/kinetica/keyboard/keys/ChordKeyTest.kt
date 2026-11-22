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
import org.junit.Assert.assertNull
import org.junit.Test

/** A chord named by its trigger and its key, stored in a form older builds skip safely. */
class ChordKeyTest {

    @Test
    fun everyChordAnEarlierBuildWroteIsAQuestionMarkChord() {
        assertEquals(ChordKey(ChordTrigger.MODE, 'l'), ChordKey.decode("l"))
        assertEquals(ChordKey(ChordTrigger.MODE, 'й'), ChordKey.decode("Й"))
        assertEquals(ChordKey(ChordTrigger.MODE, ':'), ChordKey.decode(":"))
    }

    @Test
    fun aSpacebarChordRoundTrips() {
        for (k in listOf(ChordKey(ChordTrigger.SPACE, 't'), ChordKey(ChordTrigger.SPACE, ':'), ChordKey(ChordTrigger.MODE, '1'))) {
            assertEquals(k, ChordKey.decode(k.encode()))
        }
        assertEquals("space:t", ChordKey(ChordTrigger.SPACE, 't').encode())
        assertEquals("t", ChordKey(ChordTrigger.MODE, 't').encode())
    }

    @Test
    fun aSpacebarChordIsNoChordToAnOlderRead() {
        // Earlier builds stored one character, so they skip a spacebar row, not misread it.
        assertNull(ChordKey(ChordTrigger.SPACE, 't').encode().singleOrNull())
    }

    @Test
    fun anythingElseIsNoChord() {
        for (s in listOf("", "ab", "space:", "space:ab", "spac:t", "mode:t")) assertNull(s, ChordKey.decode(s))
    }
}
