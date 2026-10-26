package com.kinetica.keyboard.keys

/**
 * What a key shows of its chord while the trigger is held: the action's symbol, a key
 * combination in symbols, or the start of the text.
 *
 * A key cap holds three or four characters before its label stops reading, so text is cut at
 * [MAX_TEXT] with an ellipsis, and a combination is symbols only, as on Apple-style keyboards:
 * `Ctrl+Delete` would spill past the key, `⌃⌦` fits.
 */
object ChordLabels {

    /** Characters of an expansion's text shown before the ellipsis. */
    const val MAX_TEXT = 3

    fun of(expansion: String): String {
        EditorAction.of(expansion)?.let { return ActionRow.glyph(it) }
        KeyCombo.parse(expansion)?.let { return combination(it) }
        if (expansion.length <= MAX_TEXT + 1) return expansion
        return expansion.take(MAX_TEXT) + "…"
    }

    /** The modifiers, then the key, with no `+`: `⌃⇧←`. Settings keep [KeyCombo.label]. */
    private fun combination(combo: KeyCombo): String =
        (if (combo.ctrl) "⌃" else "") + (if (combo.shift) "⇧" else "") + (if (combo.alt) "⌥" else "") +
            (KEY_GLYPHS[combo.key] ?: combo.key.uppercase())

    /**
     * A combination's key as the action row draws the action that presses it, so `⌦` means the
     * same on a key and in the bar. Backspace, Enter and Space press no special-key action.
     */
    private val KEY_GLYPHS: Map<String, String> =
        EditorAction.entries.mapNotNull { action -> SpecialKeys.comboKeyFor(action)?.let { it to ActionRow.glyph(action) } }
            .toMap() +
            mapOf(
                "backspace" to ActionRow.glyph(EditorAction.BACKSPACE),
                "enter" to ActionRow.glyph(EditorAction.ENTER),
                "space" to "␣",
            )
}
