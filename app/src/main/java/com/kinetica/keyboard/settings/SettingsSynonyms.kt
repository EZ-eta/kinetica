package com.kinetica.keyboard.settings

/**
 * The words people use for a setting that the setting is not called.
 *
 * Curated rather than derived, because the useful entries are the ones no rule would
 * produce: "dark" for the theme mode, "size" for the height, "tall" and "short" for the
 * same row, "swipe" for gesture typing. A row whose title already contains the obvious
 * word gets nothing here - a synonym that duplicates the title only makes the list longer.
 *
 * Kept short on purpose. Every term is a word a user might type, not a description of the
 * feature, and [SettingsIndex] ranks synonym hits below title and summary hits so a
 * generous entry cannot push a better row down.
 */
object SettingsSynonyms {

    /**
     * Settings with no row in `keyboard_prefs.xml` at all, so no walk of the preference
     * tree can find them.
     *
     * All three moved into [ChordSettingsActivity] when the chord settings were gathered
     * onto one screen (a user had reported three places to look for one feature). They are
     * still stored as preferences and are still what someone would search for, so the
     * index adds them by hand. **An entry here with no terms is unreachable**, which is
     * what `SettingsSynonymsTest` pins.
     */
    val EXTRA_KEYS: List<String> = listOf(
        Prefs.CHORD_ARM_MS,
        Prefs.LANG_CYCLE_KEY,
        Prefs.PECK_CHORD_KEY,
    )

    /** Extra words that should find the row [key], or an empty list. */
    fun termsFor(key: String): List<String> = TERMS[key] ?: emptyList()

    private val TERMS: Map<String, List<String>> = mapOf(
        // Look
        Prefs.THEME_MODE to listOf("dark", "light", "night", "colour", "color", "material"),
        Prefs.THEME_BRIGHTNESS to listOf("dark", "light", "contrast", "dim"),
        Prefs.THEME_HUE to listOf("colour", "color", "accent", "tint"),
        Prefs.TRAIL_COLOR to listOf("swipe", "gesture", "line", "path", "rainbow"),
        Prefs.ZEN_MODE to listOf("minimal", "clean", "hide", "distraction", "plain"),
        Prefs.VIBRATION to listOf("haptic", "buzz", "feedback"),
        Prefs.VIBRATION_INTENSITY to listOf("haptic", "buzz", "strength"),

        // Size and keys
        Prefs.KEYBOARD_HEIGHT_PCT to listOf("size", "tall", "short", "big", "small", "bigger"),
        Prefs.SUGGESTION_BAR_DP to listOf("size", "topbar", "toolbar", "candidates", "strip"),
        Prefs.SIDE_PAD_DP to listOf("margin", "gap", "inset", "narrow", "edge", "width"),
        Prefs.BOTTOM_PAD_DP to listOf("margin", "gap", "inset", "navigation", "thumb"),
        Prefs.DRAG_HANDLE_DP to listOf("handle", "grip", "resize"),
        Prefs.KEY_ARRANGEMENT to listOf("qwerty", "qwertz", "qzerty", "azerty", "french", "german"),
        Prefs.LAYOUT_MODE to listOf("split", "one handed", "onehanded", "thumb", "compact"),
        Prefs.PLAIN_LETTER_ALTERNATES to listOf("accent", "long press", "popup", "alternates"),
        Prefs.NUMBER_PRIORITY to listOf("digits", "number row", "top row"),
        Prefs.EMOJI_KEY to listOf("smiley", "emoticon"),
        Prefs.APOSTROPHE_KEY to listOf("quote", "contraction"),
        Prefs.COMMA_MODE to listOf("punctuation", "remove", "rebind", "replace"),
        Prefs.COMMA_ALTERNATES to listOf("punctuation", "long press", "popup"),
        Prefs.PERIOD_MODE to listOf("full stop", "dot", "punctuation", "remove", "rebind", "replace"),
        Prefs.PERIOD_ALTERNATES to listOf("full stop", "dot", "punctuation", "long press"),
        Prefs.ENTER_ALTERNATES to listOf("return", "newline", "long press"),
        Prefs.LONG_PRESS_MS to listOf("hold", "popup", "delay", "accent"),

        // Spacing
        Prefs.AUTOSPACE to listOf("space", "automatic", "gap between words"),
        Prefs.AUTOSPACE_DELAY_MS to listOf("space", "timing", "wait", "pause"),
        Prefs.AUTOSPACE_TAP_DELAY_MS to listOf("space", "timing", "wait", "pause", "typing"),
        Prefs.AUTOSPACE_RETRACT_MS to listOf("space", "undo", "take back", "timing"),
        Prefs.AUTOSPACE_TAPPED_WORDS to listOf("space", "typing", "pecking"),
        Prefs.WORD_ENDS_ON_SPACE to listOf("space", "commit", "finish"),
        Prefs.SPACELESS_SPACE to listOf("space", "no space", "compound", "join"),
        Prefs.DOUBLE_SPACE_PERIOD to listOf("space", "full stop", "period", "dot", "double", "sentence"),

        // Typing
        Prefs.AUTOCORRECT_LEVEL to listOf("correction", "spelling", "fix", "aggressive"),
        Prefs.AUTO_CAPITALIZE to listOf("capital", "uppercase", "shift", "sentence"),
        Prefs.LANGUAGE to listOf("dictionary", "locale", "keyboard language"),
        Prefs.BRITISH_SPELLING to listOf("uk", "gb", "english", "colour", "spelling"),
        Prefs.ENABLED_LANGUAGES to listOf("multilingual", "bilingual", "dictionary", "locale"),
        Prefs.AUTO_DETECT_LANGUAGE to listOf("multilingual", "bilingual", "switch"),
        Prefs.REINFORCE_INCREMENT to listOf("learn", "weight", "boost", "personal"),
        Prefs.LEARN_PHRASES to listOf("bigram", "context", "pairs", "personal"),
        Prefs.RETYPE_AVOIDS_REJECTED to listOf("retype", "again", "reject"),
        Prefs.RETYPE_BUTTON to listOf("redo", "again", "undo", "try again"),
        Prefs.RETYPE_BUTTON_DP to listOf("redo", "again", "size", "width"),
        Prefs.PECK_MODE to listOf("literal", "no prediction", "raw", "typing"),

        // Gestures
        Prefs.ALTERNATE_SWIPES to listOf("flick", "up", "accent", "gesture"),
        Prefs.BACKSPACE_CHAR_SLIDE to listOf("delete", "slide", "erase"),
        Prefs.SPACEBAR_WORD_SLIDE to listOf("cursor", "slide", "move", "arrow"),
        Prefs.SPACEBAR_STEP_DP to listOf("cursor", "slide", "speed", "sensitivity"),

        // Rows that open another screen
        "pref_chords" to listOf("shortcut", "expansion", "macro", "abbreviation"),
        "pref_edge_swipes_screen" to listOf("shortcut", "edge", "border", "gesture"),
        "pref_dictionary_screen" to listOf(
            "words", "learned", "personal", "backup", "export", "import", "blocked",
            "restore", "undo", "snapshot",
        ),
        "pref_licenses_screen" to listOf("legal", "attribution", "open source", "credits"),

        // The three that have no row of their own
        Prefs.CHORD_ARM_MS to listOf("chord", "lead in", "timing", "delay", "shortcut"),
        Prefs.LANG_CYCLE_KEY to listOf("chord", "language", "switch", "shortcut", "cycle"),
        Prefs.PECK_CHORD_KEY to listOf("chord", "peck", "literal", "shortcut"),
    )
}
