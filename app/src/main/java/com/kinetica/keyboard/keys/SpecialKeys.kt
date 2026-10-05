package com.kinetica.keyboard.keys

import android.view.KeyEvent

/**
 * The key event each special-key action sends. A key event, not text, so the editor decides what
 * Tab, Home or an arrow means in it, as it does for a hardware keyboard.
 */
object SpecialKeys {

    /** The key code [action] sends, or null when it is not a special key. */
    fun keyCodeFor(action: EditorAction): Int? = when (action) {
        EditorAction.TAB -> KeyEvent.KEYCODE_TAB
        EditorAction.ESCAPE -> KeyEvent.KEYCODE_ESCAPE
        EditorAction.FORWARD_DELETE -> KeyEvent.KEYCODE_FORWARD_DEL
        EditorAction.HOME -> KeyEvent.KEYCODE_MOVE_HOME
        EditorAction.END -> KeyEvent.KEYCODE_MOVE_END
        EditorAction.ARROW_UP -> KeyEvent.KEYCODE_DPAD_UP
        EditorAction.ARROW_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        EditorAction.ARROW_LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        EditorAction.ARROW_RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        EditorAction.PAGE_UP -> KeyEvent.KEYCODE_PAGE_UP
        EditorAction.PAGE_DOWN -> KeyEvent.KEYCODE_PAGE_DOWN
        else -> null
    }

    /** The key a special-key action names in a combination, for the one-shot Ctrl. */
    fun comboKeyFor(action: EditorAction): String? = when (action) {
        EditorAction.TAB -> "tab"
        EditorAction.ESCAPE -> "escape"
        EditorAction.FORWARD_DELETE -> "delete"
        EditorAction.HOME -> "home"
        EditorAction.END -> "end"
        EditorAction.ARROW_UP -> "up"
        EditorAction.ARROW_DOWN -> "down"
        EditorAction.ARROW_LEFT -> "left"
        EditorAction.ARROW_RIGHT -> "right"
        EditorAction.PAGE_UP -> "pageup"
        EditorAction.PAGE_DOWN -> "pagedown"
        else -> null
    }
}
