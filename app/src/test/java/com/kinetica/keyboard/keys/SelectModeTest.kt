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
