package com.kinetica.keyboard.keys

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A key with modifiers, such as Ctrl+A or Ctrl+Del, as a shortcut's target. */
class KeyComboTest {

    @Test
    fun aCombinationRoundTripsThroughItsText() {
        for (text in listOf("combo:ctrl+a", "combo:ctrl+backspace", "combo:ctrl+shift+left", "combo:alt+tab", "combo:delete")) {
            assertEquals(text, KeyCombo.parse(text)!!.encode())
        }
        // Read in any order and any case; written in one.
        assertEquals("combo:ctrl+shift+z", KeyCombo.parse("combo:Shift+CTRL+Z")!!.encode())
    }

    @Test
    fun aShiftedMoveHoldsShiftAsAKeyOfItsOwn() {
        // Select on the editing page: the cursor moved in a TextView and nothing was selected.
        val left = KeyCombo.parse(SelectMode.withShift(EditorAction.ARROW_LEFT.output))!!
        val s = KeyCombo.strokes(left.keyCode(), left.metaState())
        assertEquals(
            listOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_SHIFT_LEFT),
            s.map { it.keyCode },
        )
        assertEquals(listOf(true, true, false, false), s.map { it.down })
        assertTrue(s.dropLast(1).all { it.metaState and KeyEvent.META_SHIFT_ON != 0 })
        assertEquals(0, s.last().metaState)
        // A chord's own combo takes the same path; Ctrl stays in the meta to the end.
        val word = KeyCombo.parse("combo:ctrl+shift+right")!!
        val w = KeyCombo.strokes(word.keyCode(), word.metaState())
        assertEquals(KeyEvent.KEYCODE_SHIFT_LEFT, w.first().keyCode)
        assertTrue(w.last().metaState and KeyEvent.META_CTRL_ON != 0)
        assertFalse(w.last().metaState and KeyEvent.META_SHIFT_ON != 0)
        // No Shift, no extra key.
        val undo = KeyCombo.parse("combo:ctrl+z")!!
        assertEquals(listOf(KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_Z), KeyCombo.strokes(undo.keyCode(), undo.metaState()).map { it.keyCode })
    }

    @Test
    fun theKeyEventIsTheOneAHardwareKeyboardSends() {
        val selectAll = KeyCombo.parse("combo:ctrl+a")!!
        assertEquals(KeyEvent.KEYCODE_A, selectAll.keyCode())
        assertTrue(selectAll.metaState() and KeyEvent.META_CTRL_ON != 0)
        assertFalse(selectAll.metaState() and KeyEvent.META_SHIFT_ON != 0)
        assertEquals(KeyEvent.KEYCODE_DEL, KeyCombo.parse("combo:ctrl+backspace")!!.keyCode())
        assertEquals(KeyEvent.KEYCODE_FORWARD_DEL, KeyCombo.parse("combo:ctrl+delete")!!.keyCode())
        assertEquals(KeyEvent.KEYCODE_Z, KeyCombo.parse("combo:ctrl+z")!!.keyCode())
        assertEquals(KeyEvent.KEYCODE_7, KeyCombo.parse("combo:ctrl+7")!!.keyCode())
    }

    @Test
    fun itReadsAsAPersonWritesIt() {
        assertEquals("Ctrl+A", KeyCombo.parse("combo:ctrl+a")!!.label())
        assertEquals("Ctrl+Shift+Left", KeyCombo.parse("combo:ctrl+shift+left")!!.label())
        assertEquals("Ctrl+Backspace", KeyCombo.parse("combo:ctrl+backspace")!!.label())
        assertEquals("Page up", KeyCombo.parse("combo:pageup")!!.label())
    }

    @Test
    fun whatCannotBeSentIsNoCombination() {
        for (text in listOf("", "ctrl+a", "combo:", "combo:ctrl+", "combo:ctrl+ab", "combo:meta+a", "combo:ctrl+ctrl+a", "combo:ctrl+й", "action:paste")) {
            assertNull(text, KeyCombo.parse(text))
        }
    }

    @Test
    fun aKeyTypedAfterCtrlNamesItsCombination() {
        // "Ctrl + key" takes the key typed in the field, by character or by name.
        assertEquals("combo:ctrl+c", KeyCombo.typed("c")!!.encode())
        assertEquals("combo:ctrl+a", KeyCombo.typed(" A ")!!.encode())
        assertEquals("combo:ctrl+5", KeyCombo.typed("5")!!.encode())
        // Del is forward delete, as on a PC; Backspace keeps its name.
        assertEquals("combo:ctrl+delete", KeyCombo.typed("DEL")!!.encode())
        assertEquals("combo:ctrl+backspace", KeyCombo.typed("Backspace")!!.encode())
        assertEquals("combo:ctrl+pageup", KeyCombo.typed("Page up")!!.encode())
        assertEquals("combo:ctrl+shift+left", KeyCombo.typed("shift+left")!!.encode())
        for (text in listOf("", " ", "ab", "ctrl", "ctrl+a", "meta+a", "shift+shift+a", "é", "+a", "a+")) {
            assertNull(text, KeyCombo.typed(text))
        }
    }

    @Test
    fun theFieldShowsWhatItWouldReadBack() {
        for (stored in listOf("combo:ctrl+a", "combo:ctrl+delete", "combo:ctrl+backspace", "combo:ctrl+shift+left", "combo:ctrl+pagedown")) {
            val combo = KeyCombo.parse(stored)!!
            assertEquals(stored, KeyCombo.typed(KeyCombo.typedOf(combo))!!.encode())
        }
        assertEquals("Del", KeyCombo.typedOf(KeyCombo.parse("combo:ctrl+delete")!!))
    }

    @Test
    fun theOneShotCtrlSendsWhatItCan() {
        assertEquals("combo:ctrl+a", KeyCombo.ctrlOf("a")!!.encode())
        assertEquals("combo:ctrl+backspace", KeyCombo.ctrlOf("backspace")!!.encode())
        // A letter of another script has no key code to send: it is typed as itself.
        assertNull(KeyCombo.ctrlOf("ж"))
        assertEquals("left", SpecialKeys.comboKeyFor(EditorAction.ARROW_LEFT))
        assertNull(SpecialKeys.comboKeyFor(EditorAction.PASTE))
    }
}
