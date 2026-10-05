package com.kinetica.keyboard.settings

import android.content.SharedPreferences
import com.kinetica.keyboard.engine.Alphabet
import com.kinetica.keyboard.engine.KineticaConstants
import com.kinetica.keyboard.ime.singleLetterDelayMs
import com.kinetica.keyboard.keys.ActionRow
import com.kinetica.keyboard.keys.EdgeSwipeBindings
import com.kinetica.keyboard.keys.SpacebarCursorController
import com.kinetica.keyboard.layout.LandscapeArrangement
import com.kinetica.keyboard.layout.LayoutMode
import com.kinetica.keyboard.layout.LayoutTransforms
import com.kinetica.keyboard.ui.BarMetrics
import com.kinetica.keyboard.ui.KeyboardTheme

/**
 * Immutable snapshot of all preferences the keyboard consumes, rebuilt on any preference change
 * and pushed into the views. Views never read SharedPreferences directly.
 */
data class KeyboardConfig(
    val heightPct: Int,
    val heightPctLandscape: Int,
    val landscapeArrangement: LandscapeArrangement,
    val landscapeSplitGapPct: Int,
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
    val autospaceTapDelayMs: Long,
    /** The tap delay, floored so one letter never spaces quicker than a whole word. */
    val autospaceSingleLetterDelayMs: Long,
    val autospaceRetractMs: Long,

    /** The word ends at a delimiter only, never on the autospace timer. */
    val wordEndsOnSpace: Boolean,

    /** Autospace an all-tap word whose letters spell a dictionary word. */
    val autospaceTappedWords: Boolean,
    val zenMode: Boolean,
    val vibration: Boolean,
    val vibrationIntensity: Int,
    val trailBaseHue: Float,
    val longPressMs: Long,
    val chordArmMs: Long,
    /** The spacebar's own lead-in before a tap is a spacebar chord. */
    val spaceChordArmMs: Long,
    val retypeAvoidsRejected: Boolean,
    val autocorrectConfidence: Float?,   // null = autocorrect off
    val language: String,
    val reinforceIncrement: Int,
    val emojiKey: Boolean,
    /** Digits/symbols before accents in long-press popups and key hints. */
    val numberPriority: Boolean,
    /** Accented letters removed from the letter keys' long-press popups. */
    val plainLetterAlternates: Boolean,
    /** Long-press grid width in cells, 0 for the strip. */
    val popupColumns: Int,
    /** Implicit directional alternate swipes on the letter rows. */
    val alternateSwipes: Boolean,
    /** Backspace slide stages single characters instead of whole words. */
    val backspaceCharSlide: Boolean,
    /** Suggestion bar reserves its right edge for a retype button. */
    val retypeButton: Boolean,
    /** Width of that button in dp, clamped to BarMetrics' settable range. */
    val retypeButtonDp: Int,
    /** Side inset around the key block, per side, in dp. */
    val sidePadDp: Int,
    /** How far the middle letter row spreads toward the edges, 0 to 100%. */
    val homeRowSpreadPct: Int,
    /** Gap below the keyboard in dp; added height, not smaller keys. */
    val bottomPadDp: Int,
    /** Travel that moves the spacebar's cursor slide one step; lower is faster. */
    val spacebarStepDp: Float,
    /** Travel per weight-slide step on the suggestion bar. */
    val reinforceStepDp: Float,
    /** Rank badges appear only on the word a slide is adjusting. */
    val badgesWhileAdjusting: Boolean,
    /** Spacebar cursor slide moves whole words instead of single characters. */
    val spacebarWordSlide: Boolean,
    /** Left 30% of the spacebar ends the word without writing a space. */
    val spacelessSpace: Boolean,
    val doubleSpacePeriod: Boolean,
    /** No double spaces, no autospace before a closer. */
    val tidySpaces: Boolean,
    /** Enter runs the field's action unless the app asks for none (EnterBehavior). */
    val enterAction: Boolean,
    /** Words per minute on the spacebar (TypingSpeed). */
    val typingSpeed: Boolean,
    /** Up to three languages decode as equals, weighted by what is being typed (LanguageMomentum). */
    val noPrimary: Boolean,
    /** A letter's menu held still opens its list in settings. */
    val editFromMenu: Boolean,
    /** Learn word pairs from this user's own typing; opt-in, on-device only. */
    val learnPhrases: Boolean,
    /** Enter popup symbols; first is the primary. Never empty. */
    val enterAlternates: List<String>,
    /** Period long-press alternates; empty keeps the layout's own list. */
    val periodAlternates: List<String>,
    /** Comma long-press alternates; empty keeps the layout's own list. */
    val commaAlternates: List<String>,
    /** The user's long-press list per letter; a letter absent keeps the layout's own. */
    val letterAlternates: Map<Char, List<String>>,
    /** Optional apostrophe key in the home-row right padding. */
    val apostropheKey: Boolean,
    /** Comma-key role (pre-coerced: char/text without a custom fall to keep). */
    val commaMode: String,
    val commaCustom: String,
    val periodMode: String,
    val periodCustom: String,
    val themeMode: String,
    val themeColor: Int,
    /** dark | light | system; resolved against the configuration at use. */
    val themeBrightness: String,
    /** Raw trail selection; "theme" resolves against the active theme accent. */
    val trailColorMode: String,
    val edgeSwipes: EdgeSwipeBindings,
    val dictionaryGeneration: Int,
    /** Bumped by the expansion editor; a change reloads the trigger table. */
    val expansionGeneration: Int,
    /** Shortcut actions offered in the suggestion bar, by [EditorAction] name. */
    val barActions: Set<String>,
    /** Shortcut actions offered in the ?123 hold menu, by [EditorAction] name. */
    val menuActions: Set<String>,
    /** The user's order for [barActions] and [menuActions] (ActionRow.ordered). */
    val barActionOrder: List<String>,
    val menuActionOrder: List<String>,
    /** Next-word predictions in the idle bar. */
    val nextWord: Boolean,
    /** Recent words and their alternatives on a taller bar. */
    val recentWords: Boolean,
    /** Digits in their own row above the letters. */
    val numberRow: Boolean,
    /** Enabled languages in canonical cycle order; always contains [language]. */
    val enabledLanguages: List<String>,
    /** "Mix enabled languages": swiped and tapped words also decode against a second one. */
    val autoDetectLanguage: Boolean,
    /** Android's selected subtype decides the language at input start. */
    val syncSystemLanguage: Boolean,
    /** Prefer British spellings in English; ignored for every other language. */
    val britishSpelling: Boolean,
    /** Peck-type mode: swipes/predictions off, taps commit literally. */
    val peckMode: Boolean,
) {
    companion object {
        fun from(prefs: SharedPreferences): KeyboardConfig {
            // Read once: the tap delay defaults to it and the retraction window to twice the tap
            // delay, so an untouched keyboard keeps the single-slider behaviour.
            val swipeDelay = prefs.getInt(
                Prefs.AUTOSPACE_DELAY_MS, Prefs.DEFAULT_AUTOSPACE_DELAY_MS,
            ).coerceIn(Prefs.AUTOSPACE_DELAY_MIN_MS, Prefs.AUTOSPACE_DELAY_MAX_MS).toLong()
            return KeyboardConfig(
            heightPct = prefs.getInt(Prefs.KEYBOARD_HEIGHT_PCT, Prefs.DEFAULT_HEIGHT_PCT)
                .coerceIn(Prefs.MIN_HEIGHT_PCT, Prefs.MAX_HEIGHT_PCT),
            heightPctLandscape = prefs.getInt(
                Prefs.KEYBOARD_HEIGHT_PCT_LANDSCAPE, Prefs.DEFAULT_HEIGHT_PCT_LANDSCAPE,
            ).coerceIn(Prefs.MIN_HEIGHT_PCT, Prefs.MAX_HEIGHT_PCT),
            landscapeArrangement = LandscapeArrangement.fromPref(
                prefs.getString(Prefs.LANDSCAPE_ARRANGEMENT, Prefs.DEFAULT_LANDSCAPE_ARRANGEMENT),
            ),
            landscapeSplitGapPct = prefs.getInt(
                Prefs.LANDSCAPE_SPLIT_GAP_PCT, Prefs.DEFAULT_LANDSCAPE_SPLIT_GAP_PCT,
            ).coerceIn(0, Prefs.MAX_LANDSCAPE_SPLIT_GAP_PCT),
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
                prefs.getString(Prefs.LAYOUT_MODE, Prefs.DEFAULT_LAYOUT_MODE),
            ),
            keyArrangement = prefs.getString(
                Prefs.KEY_ARRANGEMENT, Prefs.DEFAULT_KEY_ARRANGEMENT,
            ) ?: Prefs.DEFAULT_KEY_ARRANGEMENT,
            autospace = prefs.getBoolean(Prefs.AUTOSPACE, Prefs.DEFAULT_AUTOSPACE),
            autoCapitalize = prefs.getBoolean(
                Prefs.AUTO_CAPITALIZE, Prefs.DEFAULT_AUTO_CAPITALIZE,
            ),
            autospaceDelayMs = swipeDelay,
            // Defaults to the swipe delay, not a literal, so a user who moved the old single
            // slider and never touches the new one sees no change.
            autospaceTapDelayMs = prefs.getInt(
                Prefs.AUTOSPACE_TAP_DELAY_MS, swipeDelay.toInt(),
            ).coerceIn(Prefs.AUTOSPACE_DELAY_MIN_MS, Prefs.AUTOSPACE_DELAY_MAX_MS).toLong(),
            autospaceSingleLetterDelayMs = singleLetterDelayMs(
                prefs.getInt(Prefs.AUTOSPACE_TAP_DELAY_MS, swipeDelay.toInt())
                    .coerceIn(Prefs.AUTOSPACE_DELAY_MIN_MS, Prefs.AUTOSPACE_DELAY_MAX_MS).toLong(),
                Prefs.SINGLE_LETTER_MIN_DELAY_MS.toLong(),
            ),
            // Ceiling is above 2 * 800 so the derived default is always reachable.
            autospaceRetractMs = prefs.getInt(
                Prefs.AUTOSPACE_RETRACT_MS,
                Prefs.DEFAULT_AUTOSPACE_RETRACT_MULTIPLE * prefs.getInt(
                    Prefs.AUTOSPACE_TAP_DELAY_MS,
                    prefs.getInt(Prefs.AUTOSPACE_DELAY_MS, Prefs.DEFAULT_AUTOSPACE_DELAY_MS),
                ),
            ).coerceIn(Prefs.AUTOSPACE_RETRACT_MIN_MS, Prefs.AUTOSPACE_RETRACT_MAX_MS).toLong(),
            wordEndsOnSpace = prefs.getBoolean(
                Prefs.WORD_ENDS_ON_SPACE,
                Prefs.DEFAULT_WORD_ENDS_ON_SPACE,
            ),
            autospaceTappedWords = prefs.getBoolean(
                Prefs.AUTOSPACE_TAPPED_WORDS,
                Prefs.DEFAULT_AUTOSPACE_TAPPED_WORDS,
            ),
            zenMode = prefs.getBoolean(Prefs.ZEN_MODE, Prefs.DEFAULT_ZEN),
            vibration = prefs.getBoolean(Prefs.VIBRATION, Prefs.DEFAULT_VIBRATION),
            vibrationIntensity = prefs.getInt(
                Prefs.VIBRATION_INTENSITY, Prefs.DEFAULT_VIBRATION_INTENSITY,
            ).coerceIn(1, 3),
            trailBaseHue = trailHue(prefs),
            longPressMs = prefs.getInt(Prefs.LONG_PRESS_MS, Prefs.DEFAULT_LONG_PRESS_MS)
                .coerceIn(Prefs.LONG_PRESS_MIN_MS, Prefs.LONG_PRESS_MAX_MS).toLong(),
            chordArmMs = prefs.getInt(Prefs.CHORD_ARM_MS, Prefs.DEFAULT_CHORD_ARM_MS)
                .coerceIn(0, CHORD_ARM_MAX_MS).toLong(),
            spaceChordArmMs = prefs.getInt(Prefs.SPACE_CHORD_ARM_MS, Prefs.DEFAULT_SPACE_CHORD_ARM_MS)
                .coerceIn(0, SPACE_CHORD_ARM_MAX_MS).toLong(),
            retypeAvoidsRejected = prefs.getBoolean(
                Prefs.RETYPE_AVOIDS_REJECTED, Prefs.DEFAULT_RETYPE_AVOIDS_REJECTED,
            ),
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
            popupColumns = popupColumns(
                prefs.getString(Prefs.POPUP_SHAPE, null),
                prefs.getBoolean(Prefs.POPUP_GRID, false),
            ),
            alternateSwipes = prefs.getBoolean(
                Prefs.ALTERNATE_SWIPES, Prefs.DEFAULT_ALTERNATE_SWIPES,
            ),
            backspaceCharSlide = prefs.getBoolean(
                Prefs.BACKSPACE_CHAR_SLIDE, Prefs.DEFAULT_BACKSPACE_CHAR_SLIDE,
            ),
            retypeButton = prefs.getBoolean(Prefs.RETYPE_BUTTON, Prefs.DEFAULT_RETYPE_BUTTON),
            sidePadDp = prefs.getInt(Prefs.SIDE_PAD_DP, Prefs.DEFAULT_SIDE_PAD_DP)
                .coerceIn(0, LayoutTransforms.MAX_SIDE_PAD_DP),
            homeRowSpreadPct = prefs.getInt(Prefs.HOME_ROW_SPREAD_PCT, Prefs.DEFAULT_HOME_ROW_SPREAD_PCT)
                .coerceIn(0, 100),
            bottomPadDp = prefs.getInt(Prefs.BOTTOM_PAD_DP, Prefs.DEFAULT_BOTTOM_PAD_DP)
                .coerceIn(0, LayoutTransforms.MAX_BOTTOM_PAD_DP),
            retypeButtonDp = BarMetrics.retypeDp(
                prefs.getInt(Prefs.RETYPE_BUTTON_DP, Prefs.DEFAULT_RETYPE_BUTTON_DP),
            ),
            // Clamped here as well as in the controller: the slider's bounds are what a user sees,
            // and this is the value the view is handed.
            reinforceStepDp = prefs.getInt(Prefs.REINFORCE_STEP_DP, Prefs.DEFAULT_REINFORCE_STEP_DP)
                .coerceIn(REINFORCE_STEP_MIN_DP, REINFORCE_STEP_MAX_DP).toFloat(),
            badgesWhileAdjusting = prefs.getBoolean(Prefs.BADGES_WHILE_ADJUSTING, Prefs.DEFAULT_BADGES_WHILE_ADJUSTING),
            spacebarStepDp = prefs.getInt(
                Prefs.SPACEBAR_STEP_DP, Prefs.DEFAULT_SPACEBAR_STEP_DP,
            ).coerceIn(
                SpacebarCursorController.ENTER_SLIDE_DP.toInt(),
                SpacebarCursorController.MAX_STEP_DP.toInt(),
            ).toFloat(),
            spacebarWordSlide = prefs.getBoolean(
                Prefs.SPACEBAR_WORD_SLIDE, Prefs.DEFAULT_SPACEBAR_WORD_SLIDE,
            ),
            spacelessSpace = prefs.getBoolean(
                Prefs.SPACELESS_SPACE, Prefs.DEFAULT_SPACELESS_SPACE,
            ),
            tidySpaces = prefs.getBoolean(Prefs.TIDY_SPACES, Prefs.DEFAULT_TIDY_SPACES),
            enterAction = prefs.getBoolean(Prefs.ENTER_ACTION, Prefs.DEFAULT_ENTER_ACTION),
            typingSpeed = prefs.getBoolean(Prefs.TYPING_SPEED, Prefs.DEFAULT_TYPING_SPEED),
            noPrimary = prefs.getBoolean(Prefs.NO_PRIMARY, Prefs.DEFAULT_NO_PRIMARY),
            editFromMenu = prefs.getBoolean(Prefs.EDIT_FROM_MENU, Prefs.DEFAULT_EDIT_FROM_MENU),
            doubleSpacePeriod = prefs.getBoolean(
                Prefs.DOUBLE_SPACE_PERIOD, Prefs.DEFAULT_DOUBLE_SPACE_PERIOD,
            ),
            learnPhrases = prefs.getBoolean(
                Prefs.LEARN_PHRASES, Prefs.DEFAULT_LEARN_PHRASES,
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
            letterAlternates = letterAlternatesFrom { prefs.getString(it, null) },
            apostropheKey = prefs.getBoolean(
                Prefs.APOSTROPHE_KEY, Prefs.DEFAULT_APOSTROPHE_KEY,
            ),
            commaMode = punctuationMode(prefs, Prefs.COMMA_MODE, Prefs.COMMA_CUSTOM),
            commaCustom = punctuationCustom(prefs, Prefs.COMMA_CUSTOM),
            periodMode = punctuationMode(prefs, Prefs.PERIOD_MODE, Prefs.PERIOD_CUSTOM),
            periodCustom = punctuationCustom(prefs, Prefs.PERIOD_CUSTOM),
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
            expansionGeneration = prefs.getInt(Prefs.EXPANSION_GENERATION, 0),
            barActions = prefs.getStringSet(Prefs.BAR_ACTIONS, null) ?: ActionRow.DEFAULT,
            menuActions = prefs.getStringSet(Prefs.MENU_ACTIONS, null) ?: ActionRow.DEFAULT,
            barActionOrder = ActionRow.decodeOrder(prefs.getString(Prefs.BAR_ACTIONS_ORDER, null)),
            menuActionOrder = ActionRow.decodeOrder(prefs.getString(Prefs.MENU_ACTIONS_ORDER, null)),
            nextWord = prefs.getBoolean(Prefs.NEXT_WORD, Prefs.DEFAULT_NEXT_WORD),
            recentWords = prefs.getBoolean(Prefs.RECENT_WORDS, Prefs.DEFAULT_RECENT_WORDS),
            numberRow = prefs.getBoolean(Prefs.NUMBER_ROW, Prefs.DEFAULT_NUMBER_ROW),
            enabledLanguages = enabledLanguages(prefs),
            britishSpelling = prefs.getBoolean(
                Prefs.BRITISH_SPELLING, Prefs.DEFAULT_BRITISH_SPELLING,
            ),
            autoDetectLanguage = prefs.getBoolean(
                Prefs.AUTO_DETECT_LANGUAGE, Prefs.DEFAULT_AUTO_DETECT_LANGUAGE,
            ),
            syncSystemLanguage = prefs.getBoolean(
                Prefs.SYNC_SYSTEM_LANGUAGE, Prefs.DEFAULT_SYNC_SYSTEM_LANGUAGE,
            ),
            peckMode = prefs.getBoolean(Prefs.PECK_MODE, Prefs.DEFAULT_PECK_MODE),
        )
        }

        private val PUNCTUATION_MODES =
            setOf("keep", "remove", "char", "text", "paste", "select_all")

        /**
         * A char/text mode without usable custom content degrades to "keep" (never a blank key),
         * and so do unknown values from stale prefs. Shared by the comma and the period, which
         * have the same six modes and both default to "keep".
         */
        private fun punctuationMode(
            prefs: SharedPreferences,
            modeKey: String,
            customKey: String,
        ): String {
            val mode = prefs.getString(modeKey, Prefs.DEFAULT_COMMA_MODE)
                ?.takeIf { it in PUNCTUATION_MODES } ?: Prefs.DEFAULT_COMMA_MODE
            return when {
                (mode == "char" || mode == "text") &&
                    punctuationCustom(prefs, customKey).isEmpty() -> "keep"
                else -> mode
            }
        }

        private fun punctuationCustom(prefs: SharedPreferences, customKey: String): String =
            (prefs.getString(customKey, Prefs.DEFAULT_COMMA_CUSTOM) ?: "")
                .trim().take(MAX_PUNCTUATION_TEXT)

        // A short-text insertion, not a chord expansion: keep it key-sized.
        private const val MAX_PUNCTUATION_TEXT = 16

        /** Enter popup can host at most three cells (see the popup strip). */
        private const val MAX_ENTER_ALTERNATES = 3

        /**
         * Punctuation popups may be longer than enter's. Not a renderer limit (showPopup divides
         * the width by the cell count, and English "a" ships nine), but past this the cells are
         * too narrow to hit.
         */
        private const val MAX_PUNCTUATION_ALTERNATES = 8

        /**
         * The grid width a stored shape asks for, 0 for the strip. With no shape stored the older
         * on/off grid switch decides, so a grid turned on there stays a 3 x 3 grid.
         */
        fun popupColumns(shape: String?, legacyGrid: Boolean): Int = when (shape) {
            "3" -> 3
            "4" -> 4
            "5" -> 5
            null -> if (legacyGrid) 3 else 0
            else -> 0
        }

        /** A grid holds 14 around the letter at 5 x 3; #8's longest list is 10. */
        const val MAX_LETTER_ALTERNATES = 14

        /** The weight slide's step range: under 8 dp a tremor steps; over 48 a slide runs off the screen. */
        const val REINFORCE_STEP_MIN_DP = 8
        const val REINFORCE_STEP_MAX_DP = 48

        /** Above this a `?123` lead-in stops helping: the hold reads as a slow press. */
        const val CHORD_ARM_MAX_MS = 300

        /** The spacebar's reach is longer: a fast typist's thumb rests on it for a while. */
        const val SPACE_CHORD_ARM_MAX_MS = 600

        /** Every letter the user gave a list, read through [get] so a test needs no prefs. */
        fun letterAlternatesFrom(get: (String) -> String?): Map<Char, List<String>> {
            val out = HashMap<Char, List<String>>()
            // Every board's letters, not a-z alone: a Cyrillic `й` has a list too.
            for (c in LIST_LETTERS) {
                val list = parseAlternates(get(Prefs.letterAlternatesKey(c)), MAX_LETTER_ALTERNATES)
                if (list.isNotEmpty()) out[c] = list
            }
            return out
        }
        /** The letters a list can be written for: every letter of every alphabet, once. */
        val LIST_LETTERS: List<Char> = Alphabet.ALL.flatMap { it.letters.toList() }.distinct()

        private val DEFAULT_ENTER_ALTERNATES_LIST = listOf("?", "!", ",")

        /**
         * Space-separated symbols for enter's slide-up popup; the first is the primary (committed on
         * a straight up-slide). Whitespace-split so a literal comma is a valid symbol; capped at
         * three cells; a blank value falls back to the built-in default.
         */
        fun parseEnterAlternates(raw: String?): List<String> =
            parseAlternates(raw, MAX_ENTER_ALTERNATES).ifEmpty { DEFAULT_ENTER_ALTERNATES_LIST }

        /**
         * Whitespace-split symbol list, capped at [max]. Empty for a blank value, which every caller
         * reads as "no opinion": enter substitutes its built-in default, the punctuation keys keep
         * the layout's own list.
         */
        fun parseAlternates(raw: String?, max: Int): List<String> =
            raw.orEmpty().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(max)

        /**
         * The custom-theme primary, from the hue slider. When THEME_HUE has never been written, the
         * hue comes from the THEME_COLOR picked from the retired thirteen-colour list, so no
         * keyboard changes colour on upgrade.
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
         * Enabled set in canonical order; the active language is always a member so the cycle
         * cannot strand the keyboard on a language disabled while it was active.
         */
        private fun enabledLanguages(prefs: SharedPreferences): List<String> {
            val active = prefs.getString(Prefs.LANGUAGE, Prefs.DEFAULT_LANGUAGE)
                ?: Prefs.DEFAULT_LANGUAGE
            val set = prefs.getStringSet(Prefs.ENABLED_LANGUAGES, null)
                ?: Prefs.DEFAULT_ENABLED_LANGUAGES.toSet()
            val ordered = Prefs.ALL_LANGUAGES.filter { it in set || it == active }
            return ordered.ifEmpty { listOf(active) }
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
