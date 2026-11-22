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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reserved-output parsing shared by the comma key and the chord shortcuts. It keeps the
 * chord path from inserting `action:paste` into the document as literal text.
 */
class EditorActionTest {

    @Test
    fun everyActionRoundTripsThroughItsOutput() {
        for (a in EditorAction.entries) {
            assertEquals(a, EditorAction.of(a.output))
        }
    }

    @Test
    fun ordinaryTextIsNotAnAction() {
        // Including text that mentions one, and text that would be a plausible chord expansion.
        for (s in listOf(
            "", " ", "paste", "copy", "cut", "select_all", "Paste",
            "action", "actionpaste", "my action: paste", "https://example.com",
            "brb", "kind regards,\nElia",
        )) {
            assertNull("'$s' read as an action", EditorAction.of(s))
            assertFalse("'$s' read as a malformed action", EditorAction.isUnknownAction(s))
        }
    }

    @Test
    fun aMisspeltActionIsRecognizedAsOneRatherThanTyped() {
        // isUnknownAction swallows a typo in a chord expansion, so someone who meant to paste
        // never gets "action:pate" in the middle of their message.
        for (s in listOf("action:", "action:pate", "action:PASTE", "action:select all")) {
            assertNull(EditorAction.of(s))
            assertTrue("'$s' should be a malformed action", EditorAction.isUnknownAction(s))
        }
    }

    @Test
    fun theTwoShippedOutputsAreUnchanged() {
        // The comma key has stored these in preferences since the feature shipped;
        // renaming either would silently turn a configured key back into text.
        assertEquals("action:paste", EditorAction.PASTE.output)
        assertEquals("action:select_all", EditorAction.SELECT_ALL.output)
    }

    @Test
    fun anExpansionCannotReCase() {
        // The word before the trigger would change, never the expansion.
        for (a in listOf(EditorAction.CASE_LOWER, EditorAction.CASE_TITLE, EditorAction.CASE_UPPER)) {
            assertTrue(a.name, a in EditorAction.NOT_EXPANSION_TARGETS)
        }
    }

    @Test
    fun everyOutputCarriesThePrefixAndIsDistinct() {
        val outputs = EditorAction.entries.map { it.output }
        assertEquals(outputs.size, outputs.toSet().size)
        for (o in outputs) {
            assertTrue("$o lacks the prefix", o.startsWith(EditorAction.PREFIX))
            // The prefix keeps an output from colliding with typeable text.
            assertTrue("$o is not longer than the prefix", o.length > EditorAction.PREFIX.length)
        }
    }
}
