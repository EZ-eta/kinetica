package com.kinetica.keyboard.ime

import com.kinetica.keyboard.keys.EditorAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What firing an expansion does (#19: "replace the trigger with text, or do an action").
 *
 * Expandify wrote every target into the editor as text, so a stored `action:paste` came out
 * as those twelve characters. It now runs, as chords and edge swipes already do. Four actions
 * may not be fired by an expansion, and a misspelled one is refused before the trigger is
 * deleted, because a typo in a stored target must not cost the user their trigger.
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
