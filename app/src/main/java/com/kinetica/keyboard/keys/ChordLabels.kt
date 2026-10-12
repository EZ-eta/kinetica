package com.kinetica.keyboard.keys

/**
 * What a key shows of its chord while the trigger is held: the action's symbol, a key
 * combination in modifier symbols, or the start of the text.
 *
 * A key cap holds three or four characters before its label stops reading, so text is cut at
 * [MAX_TEXT] with an ellipsis and a combination drops its words for the symbols on Apple-style
 * keyboards.
 */
object ChordLabels {

    /** Characters of an expansion's text shown before the ellipsis. */
    const val MAX_TEXT = 3

    fun of(expansion: String): String {
        EditorAction.of(expansion)?.let { return ActionRow.glyph(it) }
        KeyCombo.parse(expansion)?.let {
            return it.label().replace("Ctrl+", "⌃").replace("Shift+", "⇧").replace("Alt+", "⌥")
        }
        if (expansion.length <= MAX_TEXT + 1) return expansion
        return expansion.take(MAX_TEXT) + "…"
    }
}
