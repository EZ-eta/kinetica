package com.kinetica.keyboard.keys

/**
 * Select on the editing page: while it is on, a move goes out with Shift held, so it extends the
 * selection from the cursor, as Shift and an arrow do on a hardware keyboard. A toggle, not a held
 * key, because one thumb holds the phone.
 */
object SelectMode {

    /** [output] as sent while Select is on: a move gains Shift, anything else is unchanged. */
    fun withShift(output: String): String {
        EditorAction.of(output)?.let { action ->
            val key = SpecialKeys.comboKeyFor(action)?.takeIf { action in MOVES } ?: return output
            return KeyCombo(ctrl = false, shift = true, alt = false, key = key).encode()
        }
        val combo = KeyCombo.parse(output) ?: return output
        return if (combo.key in MOVE_KEYS) combo.copy(shift = true).encode() else output
    }

    /**
     * Whether Select ends after [action]: what the selection was for is done, and the next move
     * should move the cursor, not grow a selection that is gone.
     */
    fun clearsAfter(action: EditorAction): Boolean =
        action == EditorAction.COPY || action == EditorAction.CUT || action == EditorAction.PASTE

    private val MOVES = setOf(
        EditorAction.ARROW_LEFT, EditorAction.ARROW_RIGHT, EditorAction.ARROW_UP, EditorAction.ARROW_DOWN,
        EditorAction.HOME, EditorAction.END, EditorAction.PAGE_UP, EditorAction.PAGE_DOWN,
    )

    private val MOVE_KEYS = setOf("left", "right", "up", "down", "home", "end", "pageup", "pagedown")
}
