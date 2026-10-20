package com.kinetica.keyboard.ime

import com.kinetica.keyboard.keys.ShiftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backspace taking back the last tap or swipe of the word in progress (#38): the word as it
 * stood before each gesture, and when a backspace takes one back instead of a letter.
 */
class GestureUndoTest {

    private fun state(text: String, tokens: Int, shift: ShiftState.State = ShiftState.State.NONE) =
        GestureUndo.State(text, tokens, shift, ShiftState.State.NONE, decodeEmpty = false)

    @Test
    fun eachGestureGivesBackTheWordAsItStoodBeforeIt() {
        // `Fa` tapped with shift, then a swipe that made `Fake`: the swipe goes first, then the
        // `a`, then the shifted `F` with its capital.
        val undo = GestureUndo()
        undo.push(state("", 0, ShiftState.State.SHIFT))
        undo.push(state("F", 1))
        undo.push(state("Fa", 2))
        assertEquals(3, undo.size)
        assertEquals(state("Fa", 2), undo.pop())
        assertEquals(state("F", 1), undo.pop())
        assertEquals(state("", 0, ShiftState.State.SHIFT), undo.pop())
        assertNull("nothing left: the next backspace deletes a letter", undo.pop())
    }

    @Test
    fun aClearedStackGivesNothingBack() {
        // A reload, a commit or an abandon: seeded letters and finished words are not gestures.
        val undo = GestureUndo()
        undo.push(state("hel", 3))
        undo.clear()
        assertEquals(0, undo.size)
        assertNull(undo.pop())
    }

    @Test
    fun onlyATapInsideAWordTakesAGestureBack() {
        assertTrue(undoesGesture(tap = true, enabled = true, tentativeLength = 4, selection = 0, gestures = 2))
        // The hold's repeat deletes letter by letter.
        assertFalse(undoesGesture(tap = false, enabled = true, tentativeLength = 4, selection = 0, gestures = 2))
        // The setting.
        assertFalse(undoesGesture(tap = true, enabled = false, tentativeLength = 4, selection = 0, gestures = 2))
        // After the commit, one letter, as on any keyboard.
        assertFalse(undoesGesture(tap = true, enabled = true, tentativeLength = 0, selection = 0, gestures = 2))
        // With a selection, backspace deletes the selection.
        assertFalse(undoesGesture(tap = true, enabled = true, tentativeLength = 4, selection = 3, gestures = 2))
        // Only seeded letters left.
        assertFalse(undoesGesture(tap = true, enabled = true, tentativeLength = 4, selection = 0, gestures = 0))
    }

    @Test
    fun theUndoIsOffByDefault() {
        assertFalse(com.kinetica.keyboard.settings.Prefs.DEFAULT_BACKSPACE_UNDO)
        val xml = com.kinetica.keyboard.settings.PrefsXml.read()
        val row = Regex("""android:key="pref_backspace_undo"[^>]*>""").find(xml)?.value
        requireNotNull(row) { "pref_backspace_undo not in keyboard_prefs.xml" }
        assertTrue(row, row.contains("android:defaultValue=\"false\""))
    }
}
