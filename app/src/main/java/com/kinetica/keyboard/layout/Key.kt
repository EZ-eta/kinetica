package com.kinetica.keyboard.layout

enum class KeyType {
    CHAR, SHIFT, BACKSPACE, ENTER, SPACE,
    MODE_SYMBOLS, MODE_SYMBOLS2, MODE_ALPHA, MODE_NUMPAD, EMOJI;

    companion object {
        fun fromJson(s: String): KeyType = when (s) {
            "char" -> CHAR
            "shift" -> SHIFT
            "backspace" -> BACKSPACE
            "enter" -> ENTER
            "space" -> SPACE
            "mode_symbols" -> MODE_SYMBOLS
            "mode_symbols2" -> MODE_SYMBOLS2
            "mode_alpha" -> MODE_ALPHA
            "mode_numpad" -> MODE_NUMPAD
            "emoji" -> EMOJI
            else -> throw IllegalArgumentException("unknown key type: $s")
        }
    }
}

/**
 * One key definition with normalized (0..1) geometry over the keyboard area.
 * Pixel rects are computed per layout mode at measure time; this class stays
 * immutable across size changes.
 */
data class Key(
    val id: String,
    val type: KeyType,
    val label: String,
    val output: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val hint: String? = null,
    /** Long-press choices; the first entry is the plain-long-press default. */
    val alternates: List<String> = emptyList(),
    /**
     * Rendered without a key background/border (and no press highlight): only
     * the label is painted on the keyboard background. The hit target is
     * unchanged. Used for the optional apostrophe key, Nintype-style.
     */
    val chromeless: Boolean = false,
) {
    val isLetter: Boolean =
        type == KeyType.CHAR && output.length == 1 && output[0] in 'a'..'z'

    /** Character painted small in the top-right corner of the key. */
    val hintChar: String? = hint ?: alternates.firstOrNull()
}
