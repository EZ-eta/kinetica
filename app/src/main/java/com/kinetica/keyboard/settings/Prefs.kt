package com.kinetica.keyboard.settings

/** Single source of truth for SharedPreferences keys and defaults. */
object Prefs {
    const val KEYBOARD_HEIGHT_PCT = "pref_keyboard_height_pct"

    /** Height in landscape, stored apart so turning the phone does not reuse the portrait one. */
    const val KEYBOARD_HEIGHT_PCT_LANDSCAPE = "pref_keyboard_height_pct_landscape"

    /** Where the letter block sits in landscape: split | centered | stretch. */
    const val LANDSCAPE_ARRANGEMENT = "pref_landscape_arrangement"

    /** How far apart the split halves sit, percent of the width. */
    const val LANDSCAPE_SPLIT_GAP_PCT = "pref_landscape_split_gap_pct"

    /**
     * Suggestion-strip height in dp. Its text and every ornament scale off it (BarMetrics), so
     * this one value governs how thin the strip reads, and there is no separate font size.
     */
    const val SUGGESTION_BAR_DP = "pref_suggestion_bar_dp"

    /**
     * The old on/off switch for the resize handle, superseded by [DRAG_HANDLE_DP] and read only
     * to derive it on upgrade, so a user who turned the strip off keeps it off.
     */
    const val DRAG_HANDLE = "pref_drag_handle"
    const val DRAG_HANDLE_DP = "pref_drag_handle_dp"
    const val LAYOUT_MODE = "pref_layout_mode"

    /**
     * The non-full layout mode the one-handed toggle returns to.
     *
     * [LAYOUT_MODE] carries the hand inside its own value ("left" against "right"), so remembering
     * which one a user prefers needs a second key; otherwise the toggle would take a left-hander
     * back to the right-hand default every time.
     */
    const val LAYOUT_MODE_ONE_HANDED = "pref_layout_mode_one_handed"

    /**
     * Shortcut actions offered in the suggestion bar, as a set of [EditorAction] names.
     * Their order is [BAR_ACTIONS_ORDER]; see [ActionRow.ordered].
     */
    const val BAR_ACTIONS = "pref_bar_actions"

    /** The bar's shortcuts in the user's order, comma-joined names. Unset: canonical order. */
    const val BAR_ACTIONS_ORDER = "pref_bar_actions_order"

    /**
     * Offer the words that usually follow the last one while nothing is being typed. They
     * take the shortcut row's place; the correction strip still comes first after a commit.
     */
    const val NEXT_WORD = "pref_next_word"

    /**
     * The last two words stay on the bar, each with the two candidates it beat, so a wrong
     * word a few words back is one tap from fixed. Off by default: it makes the
     * bar taller and takes two fifths of its width.
     */
    const val RECENT_WORDS = "pref_recent_words"

    /** A row of digits above the letters, taps only; the board grows a row for it. */
    const val NUMBER_ROW = "pref_number_row"

    /**
     * The same, for the ?123 hold menu. A set of its own, so taking the gear off the bar does not
     * also take the hold menu's route to settings.
     */
    const val MENU_ACTIONS = "pref_menu_actions"

    /** The ?123 menu's shortcuts in the user's order, as [BAR_ACTIONS_ORDER]. */
    const val MENU_ACTIONS_ORDER = "pref_menu_actions_order"

    /**
     * Letter arrangement: qwerty | qwertz | qzerty | azerty. Distinct from [LAYOUT_MODE], which is
     * where the keys sit on screen (full, split, one-handed); this is which letter is on which key.
     */
    const val KEY_ARRANGEMENT = "pref_key_arrangement"

    /**
     * True while the value in [KEY_ARRANGEMENT] is one this keyboard wrote for the active language,
     * not one the user chose. Internal, with no settings row: leaving French hands the
     * arrangement back, and an arrangement the user picked is never overwritten.
     */
    const val ARRANGEMENT_AUTO_APPLIED = "pref_arrangement_auto_applied"
    const val AUTOSPACE = "pref_autospace"

    /**
     * Capitalize the first word of a sentence. On by default; the editor still has to ask for it
     * (TYPE_TEXT_FLAG_CAP_SENTENCES), this only lets the user say no. Unrelated to the lone
     * English "i", a spelling rule in AutoCapitalization that stays on.
     */
    const val AUTO_CAPITALIZE = "pref_auto_capitalize"
    /**
     * How long a swiped word waits before its automatic space arrives.
     *
     * Keeps the key of the old single delay: a user who moved that slider was most likely tuning
     * the swipe autospace, the older path and the one on by default. The tap delay reads this as
     * its own default, so an untouched keyboard keeps its old timing.
     */
    const val AUTOSPACE_DELAY_MS = "pref_autospace_delay_ms"

    /**
     * How long a tapped word waits before its automatic space arrives.
     *
     * Separate from the swipe delay because swiping an awkward word leaves bigger gaps
     * mid-gesture. Measured intra-word gaps between tokens: tapping median 186 ms, p95 471, p99
     * 569; swiping median 56 ms, p95 410, p99 878. The swipe tail is longer only in the last 5%,
     * so both default to 300 ms and the two sliders let a user find the difference.
     */
    const val AUTOSPACE_TAP_DELAY_MS = "pref_autospace_tap_delay_ms"

    /**
     * How long after an automatic space a letter still takes it back.
     *
     * The space lands at lastTap + delay and a letter retracts it within this window, so a new
     * word starting inside it fuses with the previous one; at a fixed 2x delay that band was
     * 300-900 ms and caught about one word transition in eight. The dictionary gate in
     * retractsAutospace removes 70% of those, all of the ones that produced garbage; this is the
     * control for the short-first-word residue. Defaults to twice the tap delay.
     */
    const val AUTOSPACE_RETRACT_MS = "pref_autospace_retract_ms"

    /**
     * The floor on how long a one-letter word waits for its automatic space. Not a preference:
     * there is no key for it; it sits here beside the delays it relates to.
     *
     * One-letter words like `a` and `I` were 12% of the words in a prose capture, but one letter
     * is weaker evidence than a word, so it waits longer. Premature fires are flat across
     * 275-350 ms while the one-letter words spaced fall as the delay rises; 300 is the middle of
     * that plateau, and 275 sits one millisecond above a measured premature fire.
     *
     * A floor over AUTOSPACE_TAP_DELAY_MS, so lowering the word delay for speed does not make
     * single letters fire sooner.
     */
    const val SINGLE_LETTER_MIN_DELAY_MS = 300

    /**
     * The word in progress ends only at a delimiter, never on a timer.
     *
     * An all-tap word already behaves this way, since the autospace is gated on a swipe in the
     * buffer; this extends it to gestures. A long word can then be assembled in pieces with no
     * clock running, and the buffer keeps its real gesture geometry instead of being rebuilt
     * from letters.
     *
     * Off by default: the user must end every word, and a word left open keeps offering
     * candidates until a delimiter arrives.
     */
    const val WORD_ENDS_ON_SPACE = "pref_word_ends_on_space"

    /**
     * Autospace a word that was typed entirely by tapping, when its letters spell a
     * word the dictionary holds.
     *
     * A swipe autospaces because the lift ends the gesture; a tap ends nothing, so this is off by
     * default.
     *
     * Of the tap states whose letters spell a real word, 57% were mid-word. The delay filters
     * most of them, but about one fire in five is still premature at 300 ms, barely fewer at
     * 800 ms. A frequency floor and a top-candidate check did not pay for themselves: the
     * OpenSubtitles-derived wordlist holds "ke", "wh" and "whe". The space takes itself back when
     * the next thing typed is a letter (see retractsAutospace).
     */
    const val AUTOSPACE_TAPPED_WORDS = "pref_autospace_tapped_words"
    const val ZEN_MODE = "pref_zen_mode"
    const val VIBRATION = "pref_vibration"
    const val VIBRATION_INTENSITY = "pref_vibration_intensity"
    const val TRAIL_COLOR = "pref_trail_color"
    const val TRAIL_COLOR_CUSTOM_HUE = "pref_trail_color_custom_hue"
    const val LANGUAGE = "pref_language"
    /** Last language agreed with Android; detects Settings edits while the IME is inactive. */
    const val SYNCED_LANGUAGE = "pref_synced_language"

    /**
     * Let Android's selected subtype decide the language at every input start.
     *
     * On by default, so Android's picker and Kinetica agree. Off, [LANGUAGE] is the only source of
     * truth: Android can no longer pull a user whose phone is English back to English on every
     * cold start, while Kinetica still asks Android to follow when the language changes here.
     */
    const val SYNC_SYSTEM_LANGUAGE = "pref_sync_system_language"
    const val LONG_PRESS_MS = "pref_long_press_ms"
    const val CHORD_ARM_MS = "pref_chord_arm_ms"

    /** How long the spacebar is held still before a tap counts as a spacebar chord. */
    const val SPACE_CHORD_ARM_MS = "pref_space_chord_arm_ms"

    /** Set once [ChordDefaults] has turned the old reserved keys into chords. */
    const val CHORD_DEFAULTS_SEEDED = "pref_chord_defaults_seeded"
    const val RETYPE_AVOIDS_REJECTED = "pref_retype_avoids_rejected"
    const val AUTOCORRECT_LEVEL = "pref_autocorrect_level"
    const val REINFORCE_INCREMENT = "pref_reinforce_increment"
    const val EMOJI_KEY = "pref_emoji_key"
    /**
     * Put the digit or symbol ahead of the accents in a letter key's alternates.
     *
     * It decides the corner hint as well as the popup order, and an up-swipe on the top row types
     * the corner hint, so this is how digits are reached without a long press. On by default.
     *
     * Cost: an accented top-row key draws its digit instead of its accent in every language, and
     * the long-press popup reorders with it; the accents stay in the popup. Alternates and hints
     * never reach the decoder.
     */
    const val NUMBER_PRIORITY = "pref_number_priority"

    /**
     * Drop accented letters from the letter keys' long-press popups, keeping digits and symbols.
     * Off by default: the accents let a second language be written without switching layout. No
     * effect on a layout whose accents are its own language's (KeyboardLayout.nativeAccents), so
     * an Italian, Spanish, Polish or Czech writer keeps their letters.
     */
    const val PLAIN_LETTER_ALTERNATES = "pref_plain_letter_alternates"

    /**
     * The old on/off switch for the long-press grid, read only to migrate: an install that turned
     * it on opens [POPUP_SHAPE] at 3 x 3.
     */
    const val POPUP_GRID = "pref_popup_grid"

    /**
     * Long-press menu shape (#8): row | 3 | 4 | 5, the grid's width in cells. Row is the strip
     * above the key, the shipped gesture; a plain lift commits the default in every shape.
     */
    const val POPUP_SHAPE = "pref_popup_shape"

    /**
     * One string per letter of any board, `pref_alt_a` or `pref_alt_й`: that letter's long-press
     * list, space-separated, blank for the built-in one (#8). A key each, not one table, so a value
     * never holds the tab or newline the backup format cannot carry.
     */
    fun letterAlternatesKey(letter: Char): String = "pref_alt_$letter"

    /** Comma-key role: keep | remove | char | text | paste | select_all. */
    const val COMMA_MODE = "pref_comma_mode"

    /** Custom character/text backing the comma-key char and text modes. */
    const val COMMA_CUSTOM = "pref_comma_custom"

    /** Period-key role, the same six values [COMMA_MODE] takes. */
    const val PERIOD_MODE = "pref_period_mode"

    /** Custom character/text backing the period-key char and text modes. */
    const val PERIOD_CUSTOM = "pref_period_custom"
    /** JSON array of edge-swipe bindings; absent = built-in defaults. */
    const val EDGE_SWIPES = "pref_edge_swipes"

    /**
     * Implicit directional alternate swipes: a top-row letter's up-swipe inserts its digit, a
     * bottom-row letter's down-swipe its symbol. Off by default because letter-key swipes share
     * the typing surface.
     */
    const val ALTERNATE_SWIPES = "pref_alternate_swipes"

    /**
     * Backspace slide stages single characters instead of whole words. Off by default: whole-word
     * deletion is the faster edit in the common case. Both share one gesture, so this changes
     * only the slide's granularity.
     */
    const val BACKSPACE_CHAR_SLIDE = "pref_backspace_char_slide"

    /**
     * Reserve the suggestion bar's right edge for a retype button: one tap throws the
     * current word away so it can be gestured again in place.
     *
     * On the bar because a new key would cost a column from every row. Off by default because it
     * takes width from the words; the same action is also reachable as a `?123` chord.
     */
    const val RETYPE_BUTTON = "pref_retype_button"

    /**
     * Travel, in dp, that moves the spacebar's cursor slide by one step. Lower is faster.
     *
     * Floored at the 8dp that arms cursor mode, since a shorter step would fire on the sample that
     * armed it.
     */
    const val SPACEBAR_STEP_DP = "pref_spacebar_step_dp"

    /**
     * Travel, in dp, for one step of the suggestion bar's weight slide. Each step is one badge
     * tier.
     */
    const val REINFORCE_STEP_DP = "pref_reinforce_step_dp"
    const val BADGES_WHILE_ADJUSTING = "pref_badges_while_adjusting"

    /**
     * The spacebar's cursor slide moves whole words instead of single characters.
     *
     * Reuses the backspace slide's word walk, so "one word" means the same on both gestures. Off
     * by default, since characters are the finer edit. Composes with [SPACEBAR_STEP_DP].
     */
    const val SPACEBAR_WORD_SLIDE = "pref_spacebar_word_slide"

    /**
     * Left 30% of the spacebar ends the word and writes no space.
     *
     * Lets a compound the dictionary does not hold be swiped in two halves instead of pecked.
     * Off by default because it spends a third of the spacebar's tap area.
     */
    const val SPACELESS_SPACE = "pref_spaceless_space"

    /** A second spacebar tap inside the double-tap window ends the sentence. */
    const val DOUBLE_SPACE_PERIOD = "pref_double_space_period"

    /**
     * Never two spaces in a row, and no automatic space before a closing quote or bracket or
     * where a space already follows. Off by default: it changes what the spacebar does.
     */
    const val TIDY_SPACES = "pref_tidy_spaces"
    const val ENTER_ACTION = "pref_enter_action"
    const val TYPING_SPEED = "pref_typing_speed"
    const val NO_PRIMARY = "pref_no_primary"

    /** Holding a letter's long-press menu still opens that letter's list in settings (#8). */
    const val EDIT_FROM_MENU = "pref_edit_from_menu"

    /**
     * Learn which word tends to follow which, from this user's own typing.
     *
     * Opt-in, unlike the single-word learning beside it: a pair is a fragment of a sentence and
     * reveals more than a word count. On-device only (a Room table, no network, no permission),
     * and not part of the personal-dictionary export.
     */
    const val LEARN_PHRASES = "pref_learn_phrases"

    /**
     * Enter's slide-up/hold popup symbols, space-separated; the first is the primary committed by
     * a straight up-slide, the rest are reached by sliding left. Blank falls back to "? ! ,".
     */
    const val ENTER_ALTERNATES = "pref_enter_alternates"

    /**
     * Space-separated long-press alternates for the period and comma keys. Blank leaves the
     * layout's own list, so the layout JSON stays the source of truth and a language with
     * different punctuation is not overridden by a global default.
     */
    const val PERIOD_ALTERNATES = "pref_period_alternates"
    const val COMMA_ALTERNATES = "pref_comma_alternates"

    /**
     * Optional apostrophe key: a narrow "'" in the free home-row padding right of "L", for elided
     * and contracted words (nell'immagine, don't) without the symbols layer. Off by default.
     */
    const val APOSTROPHE_KEY = "pref_apostrophe_key"

    /**
     * Bumped whenever dictionary state changes outside the IME (base wordlist imported or
     * removed, personal dictionary reset or imported): the IME reloads when this value moves.
     */
    const val DICT_GENERATION = "pref_dict_generation"

    /**
     * Bumped when the expansion table changes, so the keyboard reloads it and only then.
     *
     * Chords are re-read on every input start instead, which is cheap for a few dozen rows; an
     * expansion table can hold hundreds, and re-reading it at every field focus would cost more
     * the more it is used.
     */
    const val EXPANSION_GENERATION = "pref_expansion_generation"

    /** StringSet of enabled language codes; cycle order is canonical (en, it). */
    const val ENABLED_LANGUAGES = "pref_enabled_languages"

    /**
     * Legacy: the reserved language-cycle letter ("none" when off). Read once by [ChordDefaults],
     * which turns it into a chord and removes it; an old backup can bring it back.
     */
    const val LANG_CYCLE_KEY = "pref_lang_cycle_key"

    /** "Mix enabled languages": every word also decodes against a second language. */
    const val AUTO_DETECT_LANGUAGE = "pref_auto_detect_language"

    /**
     * British spelling for English. Both spellings of every pair are in the bundled wordlist with
     * the American form the more frequent, so this exchanges the two counts at load time and adds
     * no word. No effect on other languages.
     */
    const val BRITISH_SPELLING = "pref_british_spelling"

    /** Width of the retype button in dp; only read when [RETYPE_BUTTON] is on. */
    const val RETYPE_BUTTON_DP = "pref_retype_button_dp"

    /**
     * Side inset around the key block, per side, in dp. The keys scale to fit so every distance in
     * kw is unchanged; see LayoutTransforms.blockScale.
     */
    const val SIDE_PAD_DP = "pref_side_pad_dp"
    const val HOME_ROW_SPREAD_PCT = "pref_home_row_spread"

    /**
     * Gap below the keyboard in dp. Adds height instead of shrinking keys.
     */
    const val BOTTOM_PAD_DP = "pref_bottom_pad_dp"

    /**
     * Peck-type mode: swipes and predictions off, taps commit literally, for text the engine would
     * mangle (slang, codes).
     */
    const val PECK_MODE = "pref_peck_mode"

    /** Legacy: the reserved peck-toggle letter, converted the same way as [LANG_CYCLE_KEY]. */
    const val PECK_CHORD_KEY = "pref_peck_chord_key"
    const val THEME_MODE = "pref_theme_mode"
    const val THEME_COLOR = "pref_theme_color"

    /** dark | light | system. Orthogonal to THEME_MODE, which picks the source. */
    const val THEME_BRIGHTNESS = "pref_theme_brightness"

    /**
     * Accent hue in degrees, 0-360, replacing the fixed thirteen-colour list. Absent means never
     * set: KeyboardConfig then derives it from [THEME_COLOR] so an existing install keeps its
     * colour.
     */
    const val THEME_HUE = "pref_theme_hue"

    const val DEFAULT_HEIGHT_PCT = 35

    /**
     * 45% of a Pixel 7 held sideways, about 390 dp, is a 44 dp row, near the portrait key's
     * 41 dp width. Key width comes from [DEFAULT_LANDSCAPE_SPLIT_GAP_PCT] in split.
     */
    const val DEFAULT_HEIGHT_PCT_LANDSCAPE = 45

    /** Split, because both thumbs hold a phone turned sideways at its two ends. */
    const val DEFAULT_LANDSCAPE_ARRANGEMENT = "split"

    /**
     * 30%: about 64 dp keys on a Pixel 7 held sideways, inside the
     * measured plateau of key sizes, which holds down to 46 dp keys at 50%.
     */
    const val DEFAULT_LANDSCAPE_SPLIT_GAP_PCT = 30
    const val MAX_LANDSCAPE_SPLIT_GAP_PCT = 70
    /** The shipped strip height, so an existing install does not move. */
    const val DEFAULT_SUGGESTION_BAR_DP = 44
    const val DEFAULT_DRAG_HANDLE = true

    /**
     * One of two floors: `KeyboardHeights.minPx` takes the larger of this and an absolute dp
     * height, and on a tall phone or an unfolded foldable the dp floor decides. `app:min` in
     * keyboard_prefs.xml has to match this or the slider cannot reach it.
     */
    const val MIN_HEIGHT_PCT = 10
    const val MAX_HEIGHT_PCT = 50
    const val DEFAULT_AUTOSPACE = true
    const val DEFAULT_AUTO_CAPITALIZE = true
    const val DEFAULT_AUTOSPACE_DELAY_MS = 300

    /** Both delays share a default; see AUTOSPACE_TAP_DELAY_MS for why. */
    const val DEFAULT_AUTOSPACE_TAP_DELAY_MS = DEFAULT_AUTOSPACE_DELAY_MS

    /** The retraction window's default multiple of the tap delay. */
    const val DEFAULT_AUTOSPACE_RETRACT_MULTIPLE = 2

    // Slider ranges, shared by KeyboardConfig's clamps and the step tables in arrays.xml
    // (TimingStepsTest holds the two together). The low floors were asked for on #2.
    const val AUTOSPACE_DELAY_MIN_MS = 10
    const val AUTOSPACE_DELAY_MAX_MS = 800
    const val AUTOSPACE_RETRACT_MIN_MS = 10
    const val AUTOSPACE_RETRACT_MAX_MS = 2000

    // The floor is 25 ms. Below the length of an ordinary tap, roughly
    // 60-150 ms, the hold timer fires first: a letter tap types its first alternate and a
    // swipe that has not yet moved 12 dp opens the popup instead.
    const val LONG_PRESS_MIN_MS = 25
    const val LONG_PRESS_MAX_MS = 700
    const val DEFAULT_WORD_ENDS_ON_SPACE = false
    const val DEFAULT_AUTOSPACE_TAPPED_WORDS = false
    const val DEFAULT_ZEN = false
    const val DEFAULT_VIBRATION = true
    const val DEFAULT_VIBRATION_INTENSITY = 2
    const val DEFAULT_TRAIL_COLOR = "rainbow"
    const val DEFAULT_LANGUAGE = "en"
    const val DEFAULT_KEY_ARRANGEMENT = "qwerty"
    const val DEFAULT_ARRANGEMENT_AUTO_APPLIED = false
    const val DEFAULT_LONG_PRESS_MS = 500
    const val DEFAULT_CHORD_ARM_MS = 150

    /**
     * 50 ms, with no stray chord in use. The guard against rollover is
     * the lift rule (a chord fires only while the spacebar is still held when the key lifts), not
     * this wait; the `chord missed` trace lines measure it.
     */
    const val DEFAULT_SPACE_CHORD_ARM_MS = 50
    const val DEFAULT_RETYPE_AVOIDS_REJECTED = false
    const val DEFAULT_AUTOCORRECT_LEVEL = "normal"
    const val DEFAULT_REINFORCE_INCREMENT = "medium"
    const val DEFAULT_EMOJI_KEY = false
    const val DEFAULT_NUMBER_PRIORITY = true

    /** Full width, the mode the one-handed toggle leaves and returns to. */
    const val DEFAULT_LAYOUT_MODE = "full"
    const val DEFAULT_PLAIN_LETTER_ALTERNATES = false
    const val DEFAULT_POPUP_SHAPE = "row"
    const val DEFAULT_ALTERNATE_SWIPES = true
    const val DEFAULT_BACKSPACE_CHAR_SLIDE = false
    const val DEFAULT_RETYPE_BUTTON = false
    const val DEFAULT_SPACEBAR_STEP_DP = 20
    const val DEFAULT_REINFORCE_STEP_DP = 24
    // A cleaner bar: the rank shows only on the word being slid.
    const val DEFAULT_BADGES_WHILE_ADJUSTING = true
    const val DEFAULT_SPACEBAR_WORD_SLIDE = false
    const val DEFAULT_SPACELESS_SPACE = false
    const val DEFAULT_DOUBLE_SPACE_PERIOD = false
    const val DEFAULT_TIDY_SPACES = false

    /** On: enter searches or sends where the app asks it to, the way Android defines it. */
    const val DEFAULT_ENTER_ACTION = true

    /** Off: a number on the spacebar is shown only to those who ask for it. */
    const val DEFAULT_TYPING_SPEED = false

    /**
     * Off: the equal-footing mode changes 6% of captured leads, more than an engine change may
     * move by default, so it is the user's to choose.
     */
    const val DEFAULT_NO_PRIMARY = false
    const val DEFAULT_EDIT_FROM_MENU = false
    const val DEFAULT_LEARN_PHRASES = false
    const val DEFAULT_NEXT_WORD = true
    const val DEFAULT_RECENT_WORDS = false
    const val DEFAULT_NUMBER_ROW = false
    const val DEFAULT_ENTER_ALTERNATES = "? ! ,"
    const val DEFAULT_APOSTROPHE_KEY = false
    const val DEFAULT_COMMA_MODE = "keep"
    const val DEFAULT_COMMA_CUSTOM = ""
    const val DEFAULT_PERIOD_MODE = "keep"
    const val DEFAULT_PERIOD_CUSTOM = ""
    const val DEFAULT_THEME_MODE = "default"
    const val DEFAULT_THEME_COLOR = "#5468FF"
    const val DEFAULT_THEME_BRIGHTNESS = "dark"
    const val DEFAULT_AUTO_DETECT_LANGUAGE = false
    const val DEFAULT_SYNC_SYSTEM_LANGUAGE = true
    const val DEFAULT_BRITISH_SPELLING = false

    /**
     * Retype button width in dp. The default is the original width; the slider is for users who
     * cannot hit it with a phone case on. Must match BarMetrics.RETYPE_DEFAULT_DP and the XML's
     * app:min, or the slider cannot reach its own floor.
     */
    const val DEFAULT_RETYPE_BUTTON_DP = 34
    const val DEFAULT_SIDE_PAD_DP = 0
    // Opt-in: 0 keeps QWERTY's half-key indent, the geometry every capture was typed on.
    const val DEFAULT_HOME_ROW_SPREAD_PCT = 0
    const val DEFAULT_BOTTOM_PAD_DP = 0
    const val DEFAULT_PECK_MODE = false

    /**
     * Canonical order of all bundled languages; cycling follows this order.
     *
     * Append new languages, never insert: auto-detect takes the first enabled non-active language
     * as its secondary predictor, so inserting a code would change which language competes in
     * every existing user's decode.
     */
    val ALL_LANGUAGES =
        listOf("en", "it", "es", "pl", "cs", "nl", "de", "fr", "no", "ru", "he", "ar", "uk")

    /**
     * Languages in another script, experimental: no capture from a native typist has checked
     * them. Never enabled for a user who has not chosen them.
     */
    val EXPERIMENTAL_LANGUAGES = setOf("ru", "he", "ar", "uk")

    /** What an unset enabled-languages preference means: every language but the experimental. */
    val DEFAULT_ENABLED_LANGUAGES = ALL_LANGUAGES.filter { it !in EXPERIMENTAL_LANGUAGES }
}
