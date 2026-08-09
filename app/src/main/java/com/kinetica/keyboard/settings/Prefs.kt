package com.kinetica.keyboard.settings

/** Single source of truth for SharedPreferences keys and defaults. */
object Prefs {
    const val KEYBOARD_HEIGHT_PCT = "pref_keyboard_height_pct"
    const val LAYOUT_MODE = "pref_layout_mode"
    const val AUTOSPACE = "pref_autospace"
    const val AUTOSPACE_DELAY_MS = "pref_autospace_delay_ms"
    const val ZEN_MODE = "pref_zen_mode"
    const val VIBRATION = "pref_vibration"
    const val VIBRATION_INTENSITY = "pref_vibration_intensity"
    const val TRAIL_COLOR = "pref_trail_color"
    const val TRAIL_COLOR_CUSTOM_HUE = "pref_trail_color_custom_hue"
    const val LANGUAGE = "pref_language"
    const val LONG_PRESS_MS = "pref_long_press_ms"
    const val AUTOCORRECT_LEVEL = "pref_autocorrect_level"
    const val REINFORCE_INCREMENT = "pref_reinforce_increment"
    const val EMOJI_KEY = "pref_emoji_key"
    const val NUMBER_PRIORITY = "pref_number_priority"

    /** Comma-key role: keep | remove | char | text | paste | select_all. */
    const val COMMA_MODE = "pref_comma_mode"

    /** Custom character/text backing the comma-key char and text modes. */
    const val COMMA_CUSTOM = "pref_comma_custom"
    /** JSON array of edge-swipe bindings; absent = built-in defaults. */
    const val EDGE_SWIPES = "pref_edge_swipes"

    /**
     * Implicit directional alternate swipes: top-row letter
     * up-swipe inserts its digit, bottom-row letter down-swipe its symbol.
     * Off by first ship because letter-key swipes share the typing surface.
     */
    const val ALTERNATE_SWIPES = "pref_alternate_swipes"

    /**
     * Backspace slide stages single characters instead of whole words. Off by
     * default: whole-word deletion is the shipped behavior and the faster edit
     * for the common case, and both share one gesture, so this only changes the
     * granularity of an existing slide rather than adding a mechanism.
     */
    const val BACKSPACE_CHAR_SLIDE = "pref_backspace_char_slide"

    /**
     * Enter's slide-up/hold popup symbols: a space-separated list;
     * the first is the primary committed by a straight up-slide, the rest are
     * reached by sliding left. Blank falls back to the built-in "? ! ,".
     */
    const val ENTER_ALTERNATES = "pref_enter_alternates"

    /**
     * Optional apostrophe key: a narrow tappable "'" in the free
     * home-row padding right of "L", for writing elided/contracted words
     * (nell'immagine, don't) without the symbols layer. Off by default.
     */
    const val APOSTROPHE_KEY = "pref_apostrophe_key"

    /**
     * Bumped whenever dictionary state changes outside the IME (imported or
     * removed base wordlist, personal dictionary reset/import): the IME
     * reloads its dictionary when this value moves.
     */
    const val DICT_GENERATION = "pref_dict_generation"

    /** StringSet of enabled language codes; cycle order is canonical (en, it). */
    const val ENABLED_LANGUAGES = "pref_enabled_languages"

    /** Letter for the ?123-chord language cycle ("none" disables). */
    const val LANG_CYCLE_KEY = "pref_lang_cycle_key"

    /** Experimental per-word language auto-detection (swipe words only). */
    const val AUTO_DETECT_LANGUAGE = "pref_auto_detect_language"

    /**
     * Peck-type mode: swipes and predictions off, taps commit literally.
     * For out-of-dictionary text (slang, codes) the engine would mangle.
     */
    const val PECK_MODE = "pref_peck_mode"

    /** Letter for the ?123-chord peck-mode toggle ("none" disables). */
    const val PECK_CHORD_KEY = "pref_peck_chord_key"
    const val THEME_MODE = "pref_theme_mode"
    const val THEME_COLOR = "pref_theme_color"

    const val DEFAULT_HEIGHT_PCT = 35
    const val MIN_HEIGHT_PCT = 25
    const val MAX_HEIGHT_PCT = 50
    const val DEFAULT_AUTOSPACE = true
    const val DEFAULT_AUTOSPACE_DELAY_MS = 300
    const val DEFAULT_ZEN = false
    const val DEFAULT_VIBRATION = true
    const val DEFAULT_VIBRATION_INTENSITY = 2
    const val DEFAULT_TRAIL_COLOR = "rainbow"
    const val DEFAULT_LANGUAGE = "en"
    const val DEFAULT_LONG_PRESS_MS = 500
    const val DEFAULT_AUTOCORRECT_LEVEL = "normal"
    const val DEFAULT_REINFORCE_INCREMENT = "medium"
    const val DEFAULT_EMOJI_KEY = false
    const val DEFAULT_NUMBER_PRIORITY = false
    const val DEFAULT_ALTERNATE_SWIPES = false
    const val DEFAULT_BACKSPACE_CHAR_SLIDE = false
    const val DEFAULT_ENTER_ALTERNATES = "? ! ,"
    const val DEFAULT_APOSTROPHE_KEY = false
    const val DEFAULT_COMMA_MODE = "keep"
    const val DEFAULT_COMMA_CUSTOM = ""
    const val DEFAULT_THEME_MODE = "default"
    const val DEFAULT_THEME_COLOR = "#5468FF"
    const val DEFAULT_LANG_CYCLE_KEY = "l"
    const val DEFAULT_AUTO_DETECT_LANGUAGE = false
    const val DEFAULT_PECK_MODE = false
    const val DEFAULT_PECK_CHORD_KEY = "none"

    /** Canonical order of all bundled languages; cycling follows this order. */
    val ALL_LANGUAGES = listOf("en", "it", "es")
}
