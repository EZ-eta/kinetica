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
import com.kinetica.keyboard.keys.KeyCombo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What firing an expansion does (#19: "replace the trigger with text, or do an action").
 *
 * A stored `action:paste` runs, as it does from a chord or an edge swipe, instead of being typed
 * as text. Four actions may not be fired by an expansion, and a misspelled one is refused before
 * the trigger is deleted, so a typo in a stored target never costs the user their trigger.
 */
class ExpansionEffectTest {

    @Test
    fun anActionTargetRuns() {
        assertEquals(ExpansionEffect.Action(EditorAction.PASTE), expansionEffect("action:paste"))
        assertEquals(ExpansionEffect.Action(EditorAction.DATE), expansionEffect("action:date"))
        assertEquals(ExpansionEffect.Action(EditorAction.ENTER), expansionEffect("action:enter"))
        assertEquals(ExpansionEffect.Action(EditorAction.COPY_LINE), expansionEffect("action:copy_line"))
    }

    @Test
    fun aKeyCombinationTargetIsSentNotTyped() {
        // An expansion once wrote its stored `combo:` text into the editor.
        assertEquals(ExpansionEffect.Combo(KeyCombo.parse("combo:ctrl+a")!!), expansionEffect("combo:ctrl+a"))
        assertEquals(ExpansionEffect.Text("combo text"), expansionEffect("combo text"))
    }

    @Test
    fun theFourThatWouldMisfireAreRefused() {
        // EXPANDIFY finds its own trigger again forever; RETYPE deletes the word before the
        // trigger; UNDO and REDO take back the trigger's own removal.
        for (a in listOf(EditorAction.EXPANDIFY, EditorAction.RETYPE, EditorAction.UNDO, EditorAction.REDO)) {
            assertTrue(a.name, a in EditorAction.NOT_EXPANSION_TARGETS)
            assertEquals(ExpansionEffect.Refused(a.name), expansionEffect(a.output))
        }
    }

    @Test
    fun aMisspelledActionIsRefusedNotTyped() {
        assertEquals(ExpansionEffect.Refused("unknown"), expansionEffect("action:pate"))
        assertEquals(ExpansionEffect.Refused("unknown"), expansionEffect("action:paste\n"))
    }

    @Test
    fun anythingElseIsTextAsBefore() {
        assertEquals(ExpansionEffect.Text("•"), expansionEffect("•"))
        assertEquals(ExpansionEffect.Text("my action:paste"), expansionEffect("my action:paste"))
        assertEquals(ExpansionEffect.Text("Today:\n• \n"), expansionEffect("Today:\n• \n"))
    }
}
