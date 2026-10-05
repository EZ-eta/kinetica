package com.kinetica.keyboard.keys

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tab and the other keys the keyboard has no key for, offered as actions. */
class SpecialKeysTest {

    private val special = listOf(
        EditorAction.TAB, EditorAction.ESCAPE, EditorAction.FORWARD_DELETE, EditorAction.HOME,
        EditorAction.END, EditorAction.ARROW_UP, EditorAction.ARROW_DOWN, EditorAction.ARROW_LEFT,
        EditorAction.ARROW_RIGHT, EditorAction.PAGE_UP, EditorAction.PAGE_DOWN,
    )

    @Test
    fun eachSpecialKeySendsItsOwnKeyCode() {
        assertEquals(KeyEvent.KEYCODE_TAB, SpecialKeys.keyCodeFor(EditorAction.TAB))
        assertEquals(KeyEvent.KEYCODE_ESCAPE, SpecialKeys.keyCodeFor(EditorAction.ESCAPE))
        assertEquals(KeyEvent.KEYCODE_FORWARD_DEL, SpecialKeys.keyCodeFor(EditorAction.FORWARD_DELETE))
        assertEquals(KeyEvent.KEYCODE_MOVE_HOME, SpecialKeys.keyCodeFor(EditorAction.HOME))
        assertEquals(KeyEvent.KEYCODE_MOVE_END, SpecialKeys.keyCodeFor(EditorAction.END))
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, SpecialKeys.keyCodeFor(EditorAction.ARROW_UP))
        assertEquals(KeyEvent.KEYCODE_DPAD_DOWN, SpecialKeys.keyCodeFor(EditorAction.ARROW_DOWN))
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT, SpecialKeys.keyCodeFor(EditorAction.ARROW_LEFT))
        assertEquals(KeyEvent.KEYCODE_DPAD_RIGHT, SpecialKeys.keyCodeFor(EditorAction.ARROW_RIGHT))
        assertEquals(KeyEvent.KEYCODE_PAGE_UP, SpecialKeys.keyCodeFor(EditorAction.PAGE_UP))
        assertEquals(KeyEvent.KEYCODE_PAGE_DOWN, SpecialKeys.keyCodeFor(EditorAction.PAGE_DOWN))
        assertEquals(special.size, special.map { SpecialKeys.keyCodeFor(it) }.toSet().size)
    }

    @Test
    fun noOtherActionIsAKeyEvent() {
        for (a in EditorAction.entries) if (a !in special) assertNull(a.name, SpecialKeys.keyCodeFor(a))
    }

    @Test
    fun theyReachEveryShortcutSurface() {
        // The chord, edge-swipe and bar choosers all list ActionRow.ALL or the enum; a name in
        // a letter list resolves too, except the arrows, which a list types as text.
        for (a in special) {
            assertTrue(a.name, a in ActionRow.ALL)
            assertEquals(a, EditorAction.of(a.output))
            assertTrue(a.name, a !in EditorAction.NOT_EXPANSION_TARGETS)
        }
        assertEquals("⇥", ActionRow.glyphForToken(":tab"))
        assertEquals("⎋", ActionRow.glyphForToken(":escape"))
        assertNull(ActionRow.glyphForToken(":arrow_left"))
        assertEquals(ActionRow.Notice.Sent(EditorAction.TAB), ActionRow.notice(EditorAction.TAB, ActionRow.KeyboardState(true, listOf("en"), "en", "full", null)))
    }
}
