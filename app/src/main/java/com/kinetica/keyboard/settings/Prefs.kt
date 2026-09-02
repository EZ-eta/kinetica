package com.kinetica.keyboard.settings

/** Single source of truth for SharedPreferences keys and defaults. */
object Prefs {
    const val KEYBOARD_HEIGHT_PCT = "pref_keyboard_height_pct"

    /**
     * Suggestion-strip height in dp. Its text and every ornament scale off it
     * (BarMetrics), so this one value governs how thin the strip reads - there is
     * deliberately no separate font size.
     */
    const val SUGGESTION_BAR_DP = "pref_suggestion_bar_dp"

    /**
     * Draw the resize handle above the suggestion strip. It costs 20dp that the
     * height percentage cannot reach; off, the keyboard is resized from the
     * height slider instead.
     */
    /**
     * Superseded by [DRAG_HANDLE_DP] and read only to derive it on upgrade, so a
     * user who turned the strip off keeps it off. Not shown in Settings any more.
     */
    const val DRAG_HANDLE = "pref_drag_handle"
    const val DRAG_HANDLE_DP = "pref_drag_handle_dp"
    const val LAYOUT_MODE = "pref_layout_mode"

    /**
     * Letter arrangement: qwerty | qwertz | qzerty. Distinct from
     * [LAYOUT_MODE], which is where the keys sit on screen (full, split,
     * one-handed); this is which letter is on which key.
     */
    const val KEY_ARRANGEMENT = "pref_key_arrangement"
    const val AUTOSPACE = "pref_autospace"

    /**
     * Capitalize the first word of a sentence. On by default; the editor still has
     * to ask for it (TYPE_TEXT_FLAG_CAP_SENTENCES), this only lets the user say no.
     * Unrelated to the lone English "i", which is a spelling rule in
     * AutoCapitalization and stays on.
     */
    const val AUTO_CAPITALIZE = "pref_auto_capitalize"
    /**
     * How long a SWIPED word waits before its automatic space arrives.
     *
     * Keeps its old key deliberately. It used to govern both paths, and a user who had
     * already moved this slider was most likely tuning the swipe autospace - that is the
     * older path and the one on by default - so the stored value carries on meaning what
     * they set it to. The tap delay below reads this as its own default, so a keyboard
     * nobody has touched behaves exactly as it did.
     */
    const val AUTOSPACE_DELAY_MS = "pref_autospace_delay_ms"

    /**
     * How long a TAPPED word waits before its automatic space arrives.
     *
     * Split from the swipe delay 2026-08-29 on the developer's own reading: swiping a long
     * or awkward word leaves bigger gaps mid-gesture and wants more patience, while tapping
     * runs at one speed. The measurement half-agrees. Intra-word gap between consecutive
     * tokens over three captures: tapping median 186 ms, p95 471, p99 569; swiping median
     * 56 ms, p95 410, p99 878. So the swipe tail really is longer, but only in the last
     * 5% - at p95 the two are within 60 ms of each other.
     *
     * Both therefore default to the same 300 ms, and the sliders exist so the difference
     * can be found on a real thumb rather than guessed from a percentile.
     */
    const val AUTOSPACE_TAP_DELAY_MS = "pref_autospace_tap_delay_ms"

    /**
     * How long after an automatic space a letter still takes it back.
     *
     * Was welded to the delay at exactly 2x and had no control of its own, which is how it
     * caused a bug nobody could name: the space lands at lastTap + delay and a letter
     * retracts it within 2x delay of that, so a new word starting 300-900 ms after the
     * previous word's last tap fused the two. About one word transition in eight of the
     * developer's lands in that band.
     *
     * The dictionary gate in retractsAutospace removes 70% of that and all of the class
     * that produced garbage, so what is left here is the short-first-word residue - and
     * this is the control for it. Defaults to twice the tap delay, which is what it has
     * always been, so nothing moves for anyone who leaves it alone.
     */
    const val AUTOSPACE_RETRACT_MS = "pref_autospace_retract_ms"

    /**
     * The floor on how long a ONE-LETTER word waits for its automatic space.
     *
     * Not a preference: there is no key for it and nothing reads it from storage. It is the
     * bottom of a measured plateau, kept here beside the delays it relates to rather than
     * buried at its use site.
     *
     * `a` and `I` are words and the length rule refused them outright, which cost a manual
     * space on 12% of the words in the first prose capture. One letter is weaker evidence
     * than a word, though, so it gets more silence - and swept over three captures the
     * premature-fire count is flat across 275-350 ms while the number of one-letter words
     * spaced falls as the delay rises. 300 is the middle of that plateau, and it is already
     * the default for both other autospace delays. 275 is one millisecond above a real cost
     * sample, so it is a knife edge rather than a choice.
     *
     * Applied as a floor over AUTOSPACE_TAP_DELAY_MS, so raising the word delay raises this
     * with it and lowering the word delay for speed does not make single letters fire
     * sooner. KNOWN_ISSUES item 48.
     */
    const val SINGLE_LETTER_MIN_DELAY_MS = 300

    /**
     * The word in progress ends only at a delimiter, never on a timer.
     *
     * An all-tap word has always behaved this way - the autospace is gated on the
     * buffer holding a swipe - so this extends to gestures what tapping already had.
     * What it buys is a long word assembled in pieces with no clock running: nothing
     * commits, so the whole buffer keeps its real gesture geometry rather than being
     * rebuilt from letters.
     *
     * Off by default: it moves the responsibility for ending a word onto the user, and
     * a word left open keeps offering candidates until a delimiter arrives.
     */
    const val WORD_ENDS_ON_SPACE = "pref_word_ends_on_space"

    /**
     * Autospace a word that was typed entirely by tapping, when its letters spell a
     * word the dictionary holds.
     *
     * A swipe autospaces because the gesture is a finished statement: the finger lifted
     * and the decode landed. Tapping says nothing of the kind, which is why an all-tap
     * word has never had a timer, and why this is off by default.
     *
     * Measured over every capture: of the tap states whose letters spell a real word,
     * 57% were mid-word and the typing carried on. The delay filters most of that, and
     * about one fire in five is still premature at the default 300 ms - a rate that
     * barely moves at 800 ms. A frequency floor and a top-candidate check were both
     * measured and neither pays for itself; the wordlist is OpenSubtitles-derived, so
     * "ke", "wh" and "whe" are real entries and no dictionary test does better.
     *
     * What makes that affordable is that the space takes itself back when the next
     * thing typed is another letter - see retractsAutospace.
     */
    const val AUTOSPACE_TAPPED_WORDS = "pref_autospace_tapped_words"
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

    /**
     * Drop accented letters from the letter keys' long-press popups, keeping
     * digits and symbols. Off by default: the accents are the shipped behaviour
     * and are what makes a second language writable without switching layout.
     * Has no effect on a layout whose accents are its own language's
     * (KeyboardLayout.nativeAccents), so enabling it cannot cost an Italian,
     * Spanish or Polish writer their letters.
     */
    const val PLAIN_LETTER_ALTERNATES = "pref_plain_letter_alternates"

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
     * Reserve the suggestion bar's right edge for a retype button: one tap throws the
     * current word away so it can be gestured again in place.
     *
     * Asked for twice, and the second time with the placement that had been the blocker -
     * the feature had nowhere to live and adding a key costs a column from every row. Off
     * by default because it takes width from the words. The same action is also reachable
     * as a `?123` chord, so nobody has to spend the space to have it.
     */
    const val RETYPE_BUTTON = "pref_retype_button"

    /**
     * Travel, in dp, that moves the spacebar's cursor slide by one step. Lower is faster.
     *
     * Asked for in the field - the slide was "too slow to move through characters" - and
     * the value was a hard-coded 20dp. Floored at the 8dp that arms cursor mode in the
     * first place, since a shorter step would fire on the sample that armed it.
     */
    const val SPACEBAR_STEP_DP = "pref_spacebar_step_dp"

    /**
     * The spacebar's cursor slide moves whole words instead of single characters.
     *
     * The other half of the same request, and it costs nothing new: it reuses the backspace
     * slide's own word walk, so "one word" means the same on both gestures. Off by default
     * - characters are the shipped behaviour and the finer edit - and the two settings
     * compose, so a word-wise slide can also be given a shorter step.
     */
    const val SPACEBAR_WORD_SLIDE = "pref_spacebar_word_slide"

    /**
     * Enter's slide-up/hold popup symbols: a space-separated list;
     * the first is the primary committed by a straight up-slide, the rest are
     * reached by sliding left. Blank falls back to the built-in "? ! ,".
     */
    const val ENTER_ALTERNATES = "pref_enter_alternates"

    /**
     * Space-separated long-press alternates for the period and comma keys.
     * BLANK means "leave the layout's own list alone", so the layout JSON stays
     * the source of truth and a future language layout with different
     * punctuation is not silently overridden by a global default.
     */
    const val PERIOD_ALTERNATES = "pref_period_alternates"
    const val COMMA_ALTERNATES = "pref_comma_alternates"

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

    /** dark | light | system. Orthogonal to THEME_MODE, which picks the source. */
    const val THEME_BRIGHTNESS = "pref_theme_brightness"

    /**
     * Accent hue in degrees, 0-360, replacing the fixed thirteen-colour list.
     * Absent means "never set": KeyboardConfig then derives it from
     * [THEME_COLOR] so an existing install keeps the colour it had.
     */
    const val THEME_HUE = "pref_theme_hue"

    const val DEFAULT_HEIGHT_PCT = 35
    /** The shipped strip height, so an existing install does not move. */
    const val DEFAULT_SUGGESTION_BAR_DP = 44
    const val DEFAULT_DRAG_HANDLE = true

    /**
     * 25 -> 10 on a user report that the old floor was still too tall. It is one
     * of TWO floors and was rarely the binding one: `KeyboardHeights.minPx` takes
     * the larger of this and an absolute dp height, and on a tall phone or an
     * unfolded foldable it is the dp floor that decides. Both had to move, and
     * `app:min` in keyboard_prefs.xml has to match this or the slider cannot
     * reach it.
     */
    const val MIN_HEIGHT_PCT = 10
    const val MAX_HEIGHT_PCT = 50
    const val DEFAULT_AUTOSPACE = true
    const val DEFAULT_AUTO_CAPITALIZE = true
    const val DEFAULT_AUTOSPACE_DELAY_MS = 300

    /** Both delays share a default; see AUTOSPACE_TAP_DELAY_MS for why. */
    const val DEFAULT_AUTOSPACE_TAP_DELAY_MS = DEFAULT_AUTOSPACE_DELAY_MS

    /** The retraction window's multiple of the tap delay, which is what it always was. */
    const val DEFAULT_AUTOSPACE_RETRACT_MULTIPLE = 2
    const val DEFAULT_WORD_ENDS_ON_SPACE = false
    const val DEFAULT_AUTOSPACE_TAPPED_WORDS = false
    const val DEFAULT_ZEN = false
    const val DEFAULT_VIBRATION = true
    const val DEFAULT_VIBRATION_INTENSITY = 2
    const val DEFAULT_TRAIL_COLOR = "rainbow"
    const val DEFAULT_LANGUAGE = "en"
    const val DEFAULT_KEY_ARRANGEMENT = "qwerty"
    const val DEFAULT_LONG_PRESS_MS = 500
    const val DEFAULT_AUTOCORRECT_LEVEL = "normal"
    const val DEFAULT_REINFORCE_INCREMENT = "medium"
    const val DEFAULT_EMOJI_KEY = false
    const val DEFAULT_NUMBER_PRIORITY = false
    const val DEFAULT_PLAIN_LETTER_ALTERNATES = false
    const val DEFAULT_ALTERNATE_SWIPES = false
    const val DEFAULT_BACKSPACE_CHAR_SLIDE = false
    const val DEFAULT_RETYPE_BUTTON = false
    const val DEFAULT_SPACEBAR_STEP_DP = 20
    const val DEFAULT_SPACEBAR_WORD_SLIDE = false
    const val DEFAULT_ENTER_ALTERNATES = "? ! ,"
    const val DEFAULT_APOSTROPHE_KEY = false
    const val DEFAULT_COMMA_MODE = "keep"
    const val DEFAULT_COMMA_CUSTOM = ""
    const val DEFAULT_THEME_MODE = "default"
    const val DEFAULT_THEME_COLOR = "#5468FF"
    const val DEFAULT_THEME_BRIGHTNESS = "dark"
    const val DEFAULT_LANG_CYCLE_KEY = "l"
    const val DEFAULT_AUTO_DETECT_LANGUAGE = false
    const val DEFAULT_PECK_MODE = false
    const val DEFAULT_PECK_CHORD_KEY = "none"

    /** Canonical order of all bundled languages; cycling follows this order. */
    val ALL_LANGUAGES = listOf("en", "it", "es", "pl")
}
