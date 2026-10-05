package com.kinetica.keyboard.settings

/**
 * The words people use for a setting that the setting is not called.
 *
 * Curated, because the useful entries are the ones no rule would produce: "dark" for the theme
 * mode, "size" for the height, "tall" and "short" for the same row, "swipe" for gesture typing.
 * A row whose title already holds the obvious word gets nothing here.
 *
 * Every term is a word a user might type, not a description of the feature, and [SettingsIndex]
 * ranks synonym hits below title and summary hits so a generous entry cannot push a better row
 * down.
 */
object SettingsSynonyms {

    /**
     * Settings with no row in `keyboard_prefs.xml` at all, so no walk of the preference
     * tree can find them.
     *
     * Both lead-ins live in [ChordSettingsActivity], which holds every chord setting on one
     * screen. They are still preferences someone would search for, so the index adds them by
     * hand. An entry here with no terms is unreachable, which `SettingsSynonymsTest` pins.
     */
    val EXTRA_KEYS: List<String> = listOf(
        Prefs.CHORD_ARM_MS,
        Prefs.SPACE_CHORD_ARM_MS,
    )

    /** Extra words that should find the row [key], or an empty list. */
    fun termsFor(key: String): List<String> = TERMS[key] ?: emptyList()

    /** The row key of the expansions screen; it is an intent row, not a preference. */
    const val EXPANSIONS_ROW = "pref_expansions_screen"

    /** The long-press screen; its per-letter rows are built in code and are not searchable. */
    const val LONGPRESS_GROUP = "pref_group_longpress"

    private val TERMS: Map<String, List<String>> = mapOf(
        EXPANSIONS_ROW to listOf(
            "expand", "expandify", "shortcut", "macro", "snippet", "abbreviation",
            "replace", "template", "autotext",
        ),
        // Look
        Prefs.THEME_MODE to listOf("dark", "light", "night", "colour", "color", "material"),
        Prefs.THEME_BRIGHTNESS to listOf("dark", "light", "contrast", "dim"),
        Prefs.THEME_HUE to listOf("colour", "color", "accent", "tint"),
        Prefs.TRAIL_COLOR to listOf("swipe", "gesture", "line", "path", "rainbow"),
        Prefs.ZEN_MODE to listOf("minimal", "clean", "hide", "distraction", "plain"),
        Prefs.VIBRATION to listOf("haptic", "buzz", "feedback"),
        Prefs.VIBRATION_INTENSITY to listOf("haptic", "buzz", "strength"),

        // Size, keys and the bar
        Prefs.KEYBOARD_HEIGHT_PCT to listOf("size", "tall", "short", "big", "small", "bigger"),
        Prefs.KEYBOARD_HEIGHT_PCT_LANDSCAPE to
            listOf("landscape", "rotate", "rotated", "sideways", "horizontal", "size", "tall"),
        Prefs.LANDSCAPE_SPLIT_GAP_PCT to
            listOf("gap", "apart", "space", "split", "landscape", "rotate", "sideways", "wider", "bigger"),
        Prefs.LANDSCAPE_ARRANGEMENT to
            listOf("landscape", "rotate", "rotated", "sideways", "horizontal", "split", "centre", "center"),
        Prefs.BAR_ACTIONS to listOf(
            "toolbar", "shortcut", "button", "undo", "clipboard", "one handed",
            "menu", "action", "language",
        ),
        Prefs.NEXT_WORD to listOf("predict", "prediction", "next word", "suggest", "autocomplete", "context"),
        Prefs.EDIT_FROM_MENU to listOf("long press", "long-press", "menu", "edit", "alternates", "accents"),
        Prefs.NO_PRIMARY to listOf("language", "languages", "mix", "multilingual", "primary", "equal", "detect"),
        Prefs.TYPING_SPEED to listOf("wpm", "speed", "words per minute", "typing speed", "fast"),
        Prefs.ENTER_ACTION to listOf("enter", "return", "search", "send", "go", "newline", "action"),
        Prefs.TIDY_SPACES to listOf("space", "spaces", "double space", "compress", "quote", "autospace"),
        Prefs.NUMBER_ROW to listOf("digits", "numbers", "number row", "numeric", "1234"),
        Prefs.RECENT_WORDS to listOf("thicc", "thick", "multi-row", "rows", "history", "previous word", "topbar", "swap"),
        Prefs.MENU_ACTIONS to listOf(
            "?123", "123", "hold", "long press", "shortcut", "gear", "undo", "menu",
            "action", "popup",
        ),
        Prefs.BADGES_WHILE_ADJUSTING to listOf("badge", "rank", "dots", "tier", "weight", "clean"),
        Prefs.SUGGESTION_BAR_DP to listOf("size", "topbar", "toolbar", "candidates", "strip"),
        Prefs.HOME_ROW_SPREAD_PCT to listOf("home row", "asdf", "spread", "wider", "edge", "indent"),
        Prefs.SIDE_PAD_DP to listOf("margin", "gap", "inset", "narrow", "edge", "width"),
        Prefs.BOTTOM_PAD_DP to listOf("margin", "gap", "inset", "navigation", "thumb"),
        Prefs.DRAG_HANDLE_DP to listOf("handle", "grip", "resize"),
        Prefs.KEY_ARRANGEMENT to listOf("qwerty", "qwertz", "qzerty", "azerty", "french", "german"),
        Prefs.LAYOUT_MODE to listOf("split", "one handed", "onehanded", "thumb", "compact"),
        Prefs.PLAIN_LETTER_ALTERNATES to listOf("accent", "long press", "popup", "alternates"),
        Prefs.POPUP_SHAPE to
            listOf("grid", "square", "3x3", "4x3", "5x3", "shape", "row", "long press", "popup", "menu", "accent"),
        LONGPRESS_GROUP to listOf(
            "accent", "accents", "diacritics", "long press", "hold", "secondary", "corner", "hint",
            "popup", "symbols",
        ),
        Prefs.NUMBER_PRIORITY to listOf("digits", "number row", "top row", "swipe", "1234", "accent"),
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
        Prefs.SYNC_SYSTEM_LANGUAGE to listOf("android", "subtype", "locale", "globe", "picker"),
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
        "pref_chords" to listOf("shortcut", "expansion", "macro", "abbreviation", "language switch", "peck", "spacebar"),
        "pref_edge_swipes_screen" to listOf("shortcut", "edge", "border", "gesture"),
        "pref_dictionary_screen" to listOf("words", "learned", "personal", "export", "import", "blocked"),
        "pref_backup_screen" to listOf("backup", "export", "import", "restore", "undo", "snapshot", "phone", "move"),
        "pref_licenses_screen" to listOf("legal", "attribution", "open source", "credits"),
        "pref_contribute_screen" to listOf("donate", "donation", "coffee", "ko-fi", "kofi", "tip", "support"),
        "pref_group_learn" to listOf("help", "tutorial", "how to", "practice", "practise", "tips"),
        "pref_group_bar" to listOf("topbar", "toolbar", "candidates", "strip", "bar"),

        // The two that have no row of their own
        Prefs.CHORD_ARM_MS to listOf("chord", "lead in", "timing", "delay", "shortcut"),
        Prefs.SPACE_CHORD_ARM_MS to listOf("chord", "spacebar", "space", "lead in", "timing", "delay", "shortcut"),
    )
}
