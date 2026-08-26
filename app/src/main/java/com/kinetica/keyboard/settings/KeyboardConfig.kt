package com.kinetica.keyboard.settings

import android.content.SharedPreferences
import com.kinetica.keyboard.engine.KineticaConstants
import com.kinetica.keyboard.keys.EdgeSwipeBindings
import com.kinetica.keyboard.layout.LayoutMode
import com.kinetica.keyboard.ui.BarMetrics
import com.kinetica.keyboard.ui.KeyboardTheme

/**
 * Immutable snapshot of all preferences the keyboard consumes, rebuilt on any
 * preference change and pushed into the views. Views never read
 * SharedPreferences directly.
 */
data class KeyboardConfig(
    val heightPct: Int,
    /** Suggestion-strip height in dp; its text and ornaments scale off it. */
    val suggestionBarDp: Int,
    /** Draw the resize handle strip above the suggestion bar. */
    val dragHandleDp: Int,
    val layoutMode: LayoutMode,
    /** Letter arrangement: qwerty | qwertz | qzerty. */
    val keyArrangement: String,
    val autospace: Boolean,
    /** Capitalize the first word of a sentence. */
    val autoCapitalize: Boolean,
    val autospaceDelayMs: Long,
    val zenMode: Boolean,
    val vibration: Boolean,
    val vibrationIntensity: Int,
    val trailBaseHue: Float,
    val longPressMs: Long,
    val autocorrectConfidence: Float?,   // null = autocorrect off
    val language: String,
    val reinforceIncrement: Int,
    val emojiKey: Boolean,
    /** Digits/symbols before accents in long-press popups and key hints. */
    val numberPriority: Boolean,
    /** Accented letters removed from the letter keys' long-press popups. */
    val plainLetterAlternates: Boolean,
    /** Implicit directional alternate swipes on the letter rows. */
    val alternateSwipes: Boolean,
    /** Backspace slide stages single characters instead of whole words. */
    val backspaceCharSlide: Boolean,
    /** Enter popup symbols; first is the primary. Never empty. */
    val enterAlternates: List<String>,
    /** Period long-press alternates; EMPTY keeps the layout's own list. */
    val periodAlternates: List<String>,
    /** Comma long-press alternates; EMPTY keeps the layout's own list. */
    val commaAlternates: List<String>,
    /** Optional apostrophe key in the home-row right padding. */
    val apostropheKey: Boolean,
    /** Comma-key role (pre-coerced: char/text without a custom fall to keep). */
    val commaMode: String,
    val commaCustom: String,
    val themeMode: String,
    val themeColor: Int,
    /** dark | light | system; resolved against the configuration at use. */
    val themeBrightness: String,
    /** Raw trail selection; "theme" resolves against the active theme accent. */
    val trailColorMode: String,
    val edgeSwipes: EdgeSwipeBindings,
    val dictionaryGeneration: Int,
    /** Enabled languages in canonical cycle order; always contains [language]. */
    val enabledLanguages: List<String>,
    /** Letter code for the ?123-chord language cycle, or -1 when disabled. */
    val langCycleKeyCode: Int,
    /** Experimental: per-word language auto-detection for swipe words. */
    val autoDetectLanguage: Boolean,
    /** Peck-type mode: swipes/predictions off, taps commit literally. */
    val peckMode: Boolean,
    /** Letter code for the ?123-chord peck toggle, or -1 when disabled. */
    val peckChordKeyCode: Int,
) {
    companion object {
        fun from(prefs: SharedPreferences): KeyboardConfig = KeyboardConfig(
            heightPct = prefs.getInt(Prefs.KEYBOARD_HEIGHT_PCT, Prefs.DEFAULT_HEIGHT_PCT)
                .coerceIn(Prefs.MIN_HEIGHT_PCT, Prefs.MAX_HEIGHT_PCT),
            suggestionBarDp = prefs.getInt(
                Prefs.SUGGESTION_BAR_DP, Prefs.DEFAULT_SUGGESTION_BAR_DP,
            ).coerceIn(BarMetrics.MIN_DP.toInt(), BarMetrics.MAX_DP.toInt()),
            dragHandleDp = KeyboardHeights.handleDp(
                if (prefs.contains(Prefs.DRAG_HANDLE_DP)) {
                    prefs.getInt(Prefs.DRAG_HANDLE_DP, KeyboardHeights.MAX_HANDLE_DP)
                } else {
                    null
                },
                legacyHandleOn = prefs.getBoolean(Prefs.DRAG_HANDLE, Prefs.DEFAULT_DRAG_HANDLE),
            ),
            layoutMode = LayoutMode.fromPref(
                prefs.getString(Prefs.LAYOUT_MODE, "full"),
            ),
            keyArrangement = prefs.getString(
                Prefs.KEY_ARRANGEMENT, Prefs.DEFAULT_KEY_ARRANGEMENT,
            ) ?: Prefs.DEFAULT_KEY_ARRANGEMENT,
            autospace = prefs.getBoolean(Prefs.AUTOSPACE, Prefs.DEFAULT_AUTOSPACE),
            autoCapitalize = prefs.getBoolean(
                Prefs.AUTO_CAPITALIZE, Prefs.DEFAULT_AUTO_CAPITALIZE,
            ),
            autospaceDelayMs = prefs.getInt(
                Prefs.AUTOSPACE_DELAY_MS, Prefs.DEFAULT_AUTOSPACE_DELAY_MS,
            ).coerceIn(100, 800).toLong(),
            zenMode = prefs.getBoolean(Prefs.ZEN_MODE, Prefs.DEFAULT_ZEN),
            vibration = prefs.getBoolean(Prefs.VIBRATION, Prefs.DEFAULT_VIBRATION),
            vibrationIntensity = prefs.getInt(
                Prefs.VIBRATION_INTENSITY, Prefs.DEFAULT_VIBRATION_INTENSITY,
            ).coerceIn(1, 3),
            trailBaseHue = trailHue(prefs),
            longPressMs = prefs.getInt(Prefs.LONG_PRESS_MS, Prefs.DEFAULT_LONG_PRESS_MS)
                .coerceIn(300, 700).toLong(),
            autocorrectConfidence = when (
                prefs.getString(Prefs.AUTOCORRECT_LEVEL, Prefs.DEFAULT_AUTOCORRECT_LEVEL)
            ) {
                "off" -> null
                "aggressive" -> KineticaConstants.AUTOCORRECT_CONF_AGGRESSIVE
                else -> KineticaConstants.AUTOCORRECT_CONF_NORMAL
            },
            language = prefs.getString(Prefs.LANGUAGE, Prefs.DEFAULT_LANGUAGE)
                ?: Prefs.DEFAULT_LANGUAGE,
            reinforceIncrement = when (
                prefs.getString(Prefs.REINFORCE_INCREMENT, Prefs.DEFAULT_REINFORCE_INCREMENT)
            ) {
                "small" -> 1
                "large" -> 10
                else -> 5
            },
            emojiKey = prefs.getBoolean(Prefs.EMOJI_KEY, Prefs.DEFAULT_EMOJI_KEY),
            numberPriority = prefs.getBoolean(
                Prefs.NUMBER_PRIORITY, Prefs.DEFAULT_NUMBER_PRIORITY,
            ),
            plainLetterAlternates = prefs.getBoolean(
                Prefs.PLAIN_LETTER_ALTERNATES, Prefs.DEFAULT_PLAIN_LETTER_ALTERNATES,
            ),
            alternateSwipes = prefs.getBoolean(
                Prefs.ALTERNATE_SWIPES, Prefs.DEFAULT_ALTERNATE_SWIPES,
            ),
            backspaceCharSlide = prefs.getBoolean(
                Prefs.BACKSPACE_CHAR_SLIDE, Prefs.DEFAULT_BACKSPACE_CHAR_SLIDE,
            ),
            enterAlternates = parseEnterAlternates(
                prefs.getString(Prefs.ENTER_ALTERNATES, Prefs.DEFAULT_ENTER_ALTERNATES),
            ),
            periodAlternates = parseAlternates(
                prefs.getString(Prefs.PERIOD_ALTERNATES, null), MAX_PUNCTUATION_ALTERNATES,
            ),
            commaAlternates = parseAlternates(
                prefs.getString(Prefs.COMMA_ALTERNATES, null), MAX_PUNCTUATION_ALTERNATES,
            ),
            apostropheKey = prefs.getBoolean(
                Prefs.APOSTROPHE_KEY, Prefs.DEFAULT_APOSTROPHE_KEY,
            ),
            commaMode = commaMode(prefs),
            commaCustom = commaCustom(prefs),
            themeMode = prefs.getString(Prefs.THEME_MODE, Prefs.DEFAULT_THEME_MODE)
                ?: Prefs.DEFAULT_THEME_MODE,
            themeColor = themePrimary(prefs),
            themeBrightness = prefs.getString(
                Prefs.THEME_BRIGHTNESS, Prefs.DEFAULT_THEME_BRIGHTNESS,
            ) ?: Prefs.DEFAULT_THEME_BRIGHTNESS,
            trailColorMode = prefs.getString(Prefs.TRAIL_COLOR, Prefs.DEFAULT_TRAIL_COLOR)
                ?: Prefs.DEFAULT_TRAIL_COLOR,
            edgeSwipes = EdgeSwipeBindings.parse(prefs.getString(Prefs.EDGE_SWIPES, null)),
            dictionaryGeneration = prefs.getInt(Prefs.DICT_GENERATION, 0),
            enabledLanguages = enabledLanguages(prefs),
            langCycleKeyCode = langCycleKeyCode(prefs),
            autoDetectLanguage = prefs.getBoolean(
                Prefs.AUTO_DETECT_LANGUAGE, Prefs.DEFAULT_AUTO_DETECT_LANGUAGE,
            ),
            peckMode = prefs.getBoolean(Prefs.PECK_MODE, Prefs.DEFAULT_PECK_MODE),
            peckChordKeyCode = chordLetterCode(
                prefs, Prefs.PECK_CHORD_KEY, Prefs.DEFAULT_PECK_CHORD_KEY,
            ),
        )

        private val COMMA_MODES =
            setOf("keep", "remove", "char", "text", "paste", "select_all")

        /**
         * A char/text mode without usable custom content degrades to "keep"
         * (never a blank key), and unknown values from stale prefs do too.
         */
        private fun commaMode(prefs: SharedPreferences): String {
            val mode = prefs.getString(Prefs.COMMA_MODE, Prefs.DEFAULT_COMMA_MODE)
                ?.takeIf { it in COMMA_MODES } ?: Prefs.DEFAULT_COMMA_MODE
            return when {
                (mode == "char" || mode == "text") && commaCustom(prefs).isEmpty() -> "keep"
                else -> mode
            }
        }

        private fun commaCustom(prefs: SharedPreferences): String =
            (prefs.getString(Prefs.COMMA_CUSTOM, Prefs.DEFAULT_COMMA_CUSTOM) ?: "")
                .trim().take(MAX_COMMA_TEXT)

        // A short-text insertion, not a chord expansion: keep it key-sized.
        private const val MAX_COMMA_TEXT = 16

        /** Enter popup can host at most three cells (see the popup strip). */
        private const val MAX_ENTER_ALTERNATES = 3

        /**
         * Punctuation popups may be longer than enter's. Not a renderer limit -
         * showPopup divides the available width by the cell count and the English
         * "a" already ships nine - but past this the cells are too narrow to hit.
         */
        private const val MAX_PUNCTUATION_ALTERNATES = 8
        private val DEFAULT_ENTER_ALTERNATES_LIST = listOf("?", "!", ",")

        /**
         * Space-separated symbols for enter's slide-up popup; the first is the
         * primary (committed on a straight up-slide). Whitespace-split so a
         * literal comma is a valid symbol; capped at three cells; a blank or
         * all-whitespace value falls back to the built-in default.
         */
        fun parseEnterAlternates(raw: String?): List<String> =
            parseAlternates(raw, MAX_ENTER_ALTERNATES).ifEmpty { DEFAULT_ENTER_ALTERNATES_LIST }

        /**
         * Whitespace-split symbol list, capped at [max]. Empty for a blank value,
         * which every caller reads as "no opinion" - enter substitutes its
         * built-in default, the punctuation keys keep the layout's own list.
         */
        fun parseAlternates(raw: String?, max: Int): List<String> =
            raw.orEmpty().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(max)

        /**
         * The custom-theme primary, from the hue slider. Migration matters more
         * than the slider here: when THEME_HUE has never been written, the hue is
         * taken from whatever THEME_COLOR the user had picked from the retired
         * thirteen-colour list, so nobody's keyboard changes colour on upgrade.
         */
        private fun themePrimary(prefs: SharedPreferences): Int {
            val stored = prefs.getInt(Prefs.THEME_HUE, -1)
            val hue = if (stored in 0..360) {
                stored.toFloat()
            } else {
                KeyboardTheme.hueOf(
                    parseColorOr(prefs.getString(Prefs.THEME_COLOR, Prefs.DEFAULT_THEME_COLOR)),
                )
            }
            return KeyboardTheme.primaryForHue(hue)
        }

        private fun parseColorOr(value: String?): Int = try {
            android.graphics.Color.parseColor(value ?: Prefs.DEFAULT_THEME_COLOR)
        } catch (e: IllegalArgumentException) {
            android.graphics.Color.parseColor(Prefs.DEFAULT_THEME_COLOR)
        }

        /**
         * Enabled set in canonical order; the active language is always a
         * member so the cycle can never strand the keyboard on a language
         * the user disabled while it was active.
         */
        private fun enabledLanguages(prefs: SharedPreferences): List<String> {
            val active = prefs.getString(Prefs.LANGUAGE, Prefs.DEFAULT_LANGUAGE)
                ?: Prefs.DEFAULT_LANGUAGE
            val set = prefs.getStringSet(Prefs.ENABLED_LANGUAGES, null)
                ?: Prefs.ALL_LANGUAGES.toSet()
            val ordered = Prefs.ALL_LANGUAGES.filter { it in set || it == active }
            return ordered.ifEmpty { listOf(active) }
        }

        private fun langCycleKeyCode(prefs: SharedPreferences): Int =
            chordLetterCode(prefs, Prefs.LANG_CYCLE_KEY, Prefs.DEFAULT_LANG_CYCLE_KEY)

        /** Letter code for a single-letter chord pref; -1 for "none"/invalid. */
        private fun chordLetterCode(
            prefs: SharedPreferences,
            key: String,
            default: String,
        ): Int {
            val v = prefs.getString(key, default) ?: default
            val c = v.singleOrNull() ?: return -1
            return if (c in 'a'..'z') c - 'a' else -1
        }

        private fun trailHue(prefs: SharedPreferences): Float =
            when (prefs.getString(Prefs.TRAIL_COLOR, Prefs.DEFAULT_TRAIL_COLOR)) {
                "red" -> 0f
                "orange" -> 30f
                "green" -> 120f
                "cyan" -> 180f
                "violet" -> 275f
                "custom" -> prefs.getInt(Prefs.TRAIL_COLOR_CUSTOM_HUE, 200)
                    .coerceIn(0, 360).toFloat()
                else -> 0f   // rainbow: base 0, the per-key cycling does the rest
            }
    }
}
