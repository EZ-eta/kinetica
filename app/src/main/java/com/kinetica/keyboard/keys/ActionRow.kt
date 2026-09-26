package com.kinetica.keyboard.keys

/**
 * The shortcut actions offered in the suggestion bar and in the `?123` hold menu: which
 * exist, what each is drawn as, which order they come in, and which are worth offering
 * right now.
 *
 * Pure, because none of those four questions needs a view and all four are easy to get
 * subtly wrong. Neither surface that renders this has a JVM test harness of its own.
 *
 * **Every cell is one symbol, and that is a constraint rather than a style.** The `?123`
 * popup draws a cell with no measuring, no ellipsis and no clipping, so a word-length
 * label overlaps its neighbours instead of shrinking; the widest thing proven to work
 * there is three characters. The suggestion bar would fit more, but the two surfaces show
 * the same row and a cell that means different things in each is worse than a tight one.
 */
object ActionRow {

    /**
     * Every action the row can offer, in the order it is drawn.
     *
     * Order is imposed here and is not stored, exactly as the enabled languages are: a
     * user chooses WHICH actions appear and the sequence is the keyboard's. Storing it
     * would need a reorder editor, and nothing in this app has one.
     *
     * The four the row ships with come first, so turning another on appends rather than
     * rearranging what a user already knows the position of.
     */
    val ALL: List<EditorAction> = listOf(
        EditorAction.SETTINGS,
        EditorAction.NEXT_LANGUAGE,
        EditorAction.ONE_HANDED,
        EditorAction.TOGGLE_AUTOSPACE,
        EditorAction.UNDO,
        EditorAction.REDO,
        EditorAction.PASTE,
        EditorAction.COPY,
        EditorAction.CUT,
        EditorAction.SELECT_ALL,
        EditorAction.RETYPE,
        EditorAction.EXPANDIFY,
        EditorAction.DATE,
        EditorAction.TIME,
        EditorAction.ENTER,
        EditorAction.COPY_LINE,
    )

    /** What the row offers out of the box. */
    val DEFAULT: Set<String> = setOf(
        EditorAction.SETTINGS.name,
        EditorAction.NEXT_LANGUAGE.name,
        EditorAction.ONE_HANDED.name,
        EditorAction.TOGGLE_AUTOSPACE.name,
    )

    /**
     * What a cell draws.
     *
     * Symbols rather than icons, for the reason the retype button records: they theme with
     * the text, scale with the strip, and need no drawable. `SELECT_ALL` has no symbol in
     * any standard, so it keeps the short text label the comma key already gives it.
     */
    fun glyph(action: EditorAction): String = when (action) {
        EditorAction.SETTINGS -> "⚙"          // gear, as the ?123 hold already shows
        EditorAction.NEXT_LANGUAGE -> "⇄"     // paired arrows: swap to the next one
        EditorAction.ONE_HANDED -> "◧"        // square half filled: keys to one side
        EditorAction.TOGGLE_AUTOSPACE -> "␣"  // the open box, which IS the space symbol
        EditorAction.UNDO -> "↶"
        EditorAction.REDO -> "↷"
        EditorAction.PASTE -> "⎘"             // ISO/IEC 9995-7, as the comma key uses
        EditorAction.COPY -> "⧉"              // two joined squares
        EditorAction.CUT -> "✂"
        EditorAction.SELECT_ALL -> "ALL"
        EditorAction.RETYPE -> "↻"            // the bar's own retype glyph
        EditorAction.EXPANDIFY -> "»"
        EditorAction.DATE -> "▦"              // a grid, as a calendar page
        EditorAction.TIME -> "◷"              // a clock face with one quarter marked
        EditorAction.ENTER -> "⏎"             // the return symbol
        EditorAction.COPY_LINE -> "≡"         // lines of text
    }

    /**
     * Whether [action] is worth offering at all right now.
     *
     * Only the language switch has an answer other than yes: cycling returns silently with
     * fewer than two enabled languages, so the cell would be a button that does nothing.
     * The spacebar hides its language code on the same test.
     */
    fun available(action: EditorAction, enabledLanguages: Int): Boolean =
        action != EditorAction.NEXT_LANGUAGE || enabledLanguages > 1

    /**
     * The row to draw: the chosen actions in canonical order, minus the unavailable, and
     * no more than [maxCells] of them.
     *
     * [maxCells] is the caller's geometry, not a preference. A cell narrower than a thumb
     * is not a shortcut, so a bar that cannot hold the whole row holds the front of it.
     */
    fun resolve(
        chosen: Set<String>,
        enabledLanguages: Int,
        maxCells: Int,
    ): List<EditorAction> {
        if (maxCells <= 0) return emptyList()
        return ALL
            .filter { it.name in chosen && available(it, enabledLanguages) }
            .take(maxCells)
    }

    /**
     * How many cells of at least [minCellPx] fit in [availablePx].
     *
     * The floor is the retype button's own: below about 24dp a symbol stops being a
     * reliable target at any bar height, which is the one number this project has ever
     * written down about hitting a glyph.
     */
    fun cellsThatFit(availablePx: Float, minCellPx: Float): Int =
        if (minCellPx <= 0f) 0 else (availablePx / minCellPx).toInt().coerceAtLeast(0)

    /**
     * What the spacebar says after a shortcut ran, or null for nothing.
     *
     * A state toggle says the state it leaves behind, which is the part nobody can see: the
     * autospace dot is 2.5dp. An editor command says its own name and never an outcome,
     * because the app reports that a command was delivered and not that it did anything, so
     * "Undo" is true where "Undone" might not be. Settings says nothing: the keyboard closes.
     *
     * Decided from the state BEFORE the action runs, with the same functions the actions
     * themselves call, so the notice and the effect cannot disagree.
     */
    fun notice(action: EditorAction, before: KeyboardState): Notice? = when (action) {
        EditorAction.SETTINGS -> null
        EditorAction.TOGGLE_AUTOSPACE -> Notice.Autospace(!before.autospace)
        EditorAction.NEXT_LANGUAGE ->
            nextLanguage(before.languages, before.language)?.let { Notice.Language(it) }
        EditorAction.ONE_HANDED ->
            Notice.Layout(oneHandedToggle(before.layoutMode, before.rememberedOneHanded).mode)
        EditorAction.UNDO, EditorAction.REDO, EditorAction.PASTE, EditorAction.COPY,
        EditorAction.CUT, EditorAction.SELECT_ALL, EditorAction.RETYPE, EditorAction.EXPANDIFY,
        EditorAction.DATE, EditorAction.TIME, EditorAction.ENTER, EditorAction.COPY_LINE,
        -> Notice.Sent(action)
    }

    /** The keyboard state a [notice] is decided from. */
    data class KeyboardState(
        val autospace: Boolean,
        val languages: List<String>,
        val language: String,
        val layoutMode: String,
        val rememberedOneHanded: String?,
    )

    /** What a [notice] says; the service turns it into text. */
    sealed interface Notice {
        /** The command's own name. */
        data class Sent(val action: EditorAction) : Notice

        data class Autospace(val on: Boolean) : Notice

        /** The language switched to, by code. */
        data class Language(val code: String) : Notice

        /** The layout mode switched to, by preference value. */
        data class Layout(val mode: String) : Notice
    }

    /**
     * The language the cycle moves to, or null with nothing to cycle to. A current language
     * missing from the list moves to the first one.
     */
    fun nextLanguage(languages: List<String>, current: String): String? =
        if (languages.size < 2) null else languages[(languages.indexOf(current) + 1).mod(languages.size)]

    /** [mode] after a one-handed toggle, and the mode to remember for next time. */
    data class OneHanded(val mode: String, val remember: String?)

    /**
     * The one-handed toggle.
     *
     * Leaving a one-handed mode remembers which it was, so the way back is the way the
     * user came. Entering one with nothing remembered uses [DEFAULT_ONE_HANDED]; a user
     * who prefers the left gets the left back from then on.
     */
    fun oneHandedToggle(current: String, remembered: String?): OneHanded =
        if (current == FULL) {
            OneHanded(remembered?.takeIf { it != FULL } ?: DEFAULT_ONE_HANDED, null)
        } else {
            OneHanded(FULL, current)
        }

    const val FULL = "full"

    /** Right-anchored, which is the mode named "one-handed" in the settings list. */
    const val DEFAULT_ONE_HANDED = "one_handed"
}
