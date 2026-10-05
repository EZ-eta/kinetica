package com.kinetica.keyboard.keys

/**
 * The shortcut actions offered in the suggestion bar and in the `?123` hold menu: which
 * exist, what each is drawn as, which order they come in, and which are worth offering
 * right now.
 *
 * Pure, because none of those four questions needs a view, and neither surface that renders
 * this has a JVM test harness of its own.
 *
 * Every cell is one symbol: the `?123` popup draws a cell with no measuring, ellipsis or
 * clipping, so a word-length label overlaps its neighbours; three characters is the widest that
 * fits. The suggestion bar would fit more, but both surfaces show the same row and a cell should
 * mean the same in each.
 */
object ActionRow {

    /**
     * Every action the row can offer, in the order it is drawn until the user orders it.
     *
     * The user's order is stored beside the selection ([ordered]); this is the order of
     * everything it does not name. The four defaults come first, so turning another on appends
     * instead of moving cells the user already knows.
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
        EditorAction.TOGGLE_NEXT_WORD,
        EditorAction.TOGGLE_RECENT_WORDS,
        EditorAction.TOGGLE_NUMBER_ROW,
        EditorAction.TOGGLE_TIDY_SPACES,
        EditorAction.TOGGLE_TYPING_SPEED,
        EditorAction.TOGGLE_PECK_MODE,
        EditorAction.TAB,
        EditorAction.ESCAPE,
        EditorAction.FORWARD_DELETE,
        EditorAction.HOME,
        EditorAction.END,
        EditorAction.ARROW_UP,
        EditorAction.ARROW_DOWN,
        EditorAction.ARROW_LEFT,
        EditorAction.ARROW_RIGHT,
        EditorAction.PAGE_UP,
        EditorAction.PAGE_DOWN,
        EditorAction.BACKSPACE,
        EditorAction.CTRL_NEXT,
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
     * Symbols, not icons, as for the retype button: they theme with the text, scale with the
     * strip and need no drawable. `SELECT_ALL` has no standard symbol, so it keeps the short text
     * label the comma key gives it.
     */
    fun glyph(action: EditorAction): String = when (action) {
        EditorAction.SETTINGS -> "⚙"          // gear, as the ?123 hold already shows
        EditorAction.NEXT_LANGUAGE -> "⇄"     // paired arrows: swap to the next one
        EditorAction.ONE_HANDED -> "◧"        // square half filled: keys to one side
        EditorAction.TOGGLE_AUTOSPACE -> "␣"  // the open box, the space symbol
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
        EditorAction.TOGGLE_NEXT_WORD -> "⇢"  // the word that comes next
        EditorAction.TOGGLE_RECENT_WORDS -> "☰" // rows of words
        EditorAction.TOGGLE_NUMBER_ROW -> "123" // not `#`, which a letter's list may hold as text
        EditorAction.TOGGLE_TIDY_SPACES -> "‿" // not `⎵`, which sat next to autospace's `␣`
        EditorAction.TOGGLE_TYPING_SPEED -> "wpm" // what the readout says, as `123` does
        EditorAction.TOGGLE_PECK_MODE -> "TAP"   // what the spacebar says in peck-type
        EditorAction.TAB -> "⇥"
        EditorAction.ESCAPE -> "⎋"            // ISO 9995-7, the escape symbol
        EditorAction.FORWARD_DELETE -> "⌦"
        EditorAction.HOME -> "⇱"
        EditorAction.END -> "⇲"
        EditorAction.ARROW_UP -> "↑"
        EditorAction.ARROW_DOWN -> "↓"
        EditorAction.ARROW_LEFT -> "←"
        EditorAction.ARROW_RIGHT -> "→"
        EditorAction.PAGE_UP -> "⇞"
        EditorAction.PAGE_DOWN -> "⇟"
        EditorAction.BACKSPACE -> "⌫"           // the key's own label
        EditorAction.CTRL_NEXT -> "⌃"           // the control symbol
    }

    /**
     * The action whose [glyph] is [text], or null: a letter's long-press list may hold one and
     * run it. No built-in list holds these symbols, so only a list the user wrote can.
     */
    fun actionForGlyph(text: String): EditorAction? =
        EditorAction.entries.firstOrNull { it !in NOT_IN_LISTS && glyph(it) == text }

    /**
     * Actions whose symbol is ordinary text a list may hold (`ALL`, `123`, the French `»`), so a
     * letter's list types them instead of running anything.
     */
    private val NOT_IN_LISTS = setOf(
        EditorAction.SELECT_ALL, EditorAction.TOGGLE_NUMBER_ROW, EditorAction.EXPANDIFY,
        EditorAction.TOGGLE_TYPING_SPEED, EditorAction.TOGGLE_PECK_MODE,
        // Arrows are text a list may well hold (`→`), so in a list they type themselves.
        EditorAction.ARROW_UP, EditorAction.ARROW_DOWN, EditorAction.ARROW_LEFT, EditorAction.ARROW_RIGHT,
    )

    /**
     * The glyph for an action written by name in a letter's list, `:paste` or `:select_all`, or
     * null for anything else: typing a name is easier than finding the symbol.
     */
    fun glyphForToken(text: String): String? {
        if (text.length < 2 || text[0] != ':') return null
        val name = text.substring(1).uppercase()
        return EditorAction.entries.firstOrNull { it.name == name && it !in NOT_IN_LISTS }?.let { glyph(it) }
    }

    /**
     * Whether [action] is worth offering at all right now.
     *
     * Only the language switch can be no: cycling does nothing with fewer than two enabled
     * languages. The spacebar hides its language code on the same test.
     */
    fun available(action: EditorAction, enabledLanguages: Int): Boolean =
        action != EditorAction.NEXT_LANGUAGE || enabledLanguages > 1

    /**
     * The row to draw: the chosen actions in [ordered] order, minus the unavailable, and no more
     * than [maxCells] of them.
     *
     * [maxCells] is the caller's geometry, not a preference: a cell narrower than a thumb is no
     * shortcut, so a bar that cannot hold the whole row holds the front of it.
     */
    fun resolve(
        chosen: Set<String>,
        enabledLanguages: Int,
        maxCells: Int,
        order: List<String> = emptyList(),
    ): List<EditorAction> {
        if (maxCells <= 0) return emptyList()
        return ordered(chosen, order)
            .filter { available(it, enabledLanguages) }
            .take(maxCells)
    }

    /**
     * The chosen actions in the user's [order], then any chosen action the order does not
     * name in [ALL]'s order. An unknown name is dropped, so an order written by a newer or
     * older build never breaks the row.
     *
     * The order is stored apart from the selection, so a selection made before ordering
     * existed needs no migration and the backup format is unchanged.
     */
    fun ordered(chosen: Set<String>, order: List<String>): List<EditorAction> {
        val byName = ALL.associateBy { it.name }
        val first = order.distinct().mapNotNull { name -> byName[name]?.takeIf { name in chosen } }
        return first + ALL.filter { it.name in chosen && it !in first }
    }

    /** An order as stored: names joined by commas. */
    fun encodeOrder(actions: List<EditorAction>): String = actions.joinToString(",") { it.name }

    /** A stored order, blank entries dropped. Names are checked by [ordered], not here. */
    fun decodeOrder(stored: String?): List<String> =
        stored.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * How many cells of at least [minCellPx] fit in [availablePx].
     *
     * The floor is the retype button's own: below about 24dp a symbol stops being a reliable
     * target at any bar height.
     */
    fun cellsThatFit(availablePx: Float, minCellPx: Float): Int =
        if (minCellPx <= 0f) 0 else (availablePx / minCellPx).toInt().coerceAtLeast(0)

    /**
     * What the spacebar says after a shortcut ran, or null for nothing.
     *
     * A state toggle says the state it leaves behind, which is otherwise hard to see: the
     * autospace dot is 2.5dp. An editor command says its own name, never an outcome, because the
     * app reports delivery, not effect: "Undo" is true where "Undone" might not be. Settings says
     * nothing: the keyboard closes.
     *
     * Decided from the state before the action runs, with the functions the actions themselves
     * call, so the notice and the effect cannot disagree.
     */
    fun notice(action: EditorAction, before: KeyboardState): Notice? = when (action) {
        EditorAction.SETTINGS -> null
        EditorAction.TOGGLE_AUTOSPACE -> Notice.Autospace(!before.autospace)
        EditorAction.TOGGLE_NEXT_WORD -> Notice.Toggle(action, !before.nextWord)
        EditorAction.TOGGLE_RECENT_WORDS -> Notice.Toggle(action, !before.recentWords)
        EditorAction.TOGGLE_NUMBER_ROW -> Notice.Toggle(action, !before.numberRow)
        EditorAction.TOGGLE_TIDY_SPACES -> Notice.Toggle(action, !before.tidySpaces)
        EditorAction.TOGGLE_TYPING_SPEED -> Notice.Toggle(action, !before.typingSpeed)
        EditorAction.TOGGLE_PECK_MODE -> Notice.Toggle(action, !before.peckMode)
        EditorAction.CTRL_NEXT -> Notice.Toggle(action, !before.ctrlPending)
        EditorAction.NEXT_LANGUAGE ->
            nextLanguage(before.languages, before.language)?.let { Notice.Language(it) }
        EditorAction.ONE_HANDED ->
            Notice.Layout(oneHandedToggle(before.layoutMode, before.rememberedOneHanded).mode)
        EditorAction.UNDO, EditorAction.REDO, EditorAction.PASTE, EditorAction.COPY,
        EditorAction.CUT, EditorAction.SELECT_ALL, EditorAction.RETYPE, EditorAction.EXPANDIFY,
        EditorAction.DATE, EditorAction.TIME, EditorAction.ENTER, EditorAction.COPY_LINE,
        EditorAction.TAB, EditorAction.ESCAPE, EditorAction.FORWARD_DELETE, EditorAction.HOME,
        EditorAction.END, EditorAction.ARROW_UP, EditorAction.ARROW_DOWN, EditorAction.ARROW_LEFT,
        EditorAction.ARROW_RIGHT, EditorAction.PAGE_UP, EditorAction.PAGE_DOWN,
        EditorAction.BACKSPACE,
        -> Notice.Sent(action)
    }

    /** The keyboard state a [notice] is decided from. */
    data class KeyboardState(
        val autospace: Boolean,
        val languages: List<String>,
        val language: String,
        val layoutMode: String,
        val rememberedOneHanded: String?,
        val nextWord: Boolean = false,
        val recentWords: Boolean = false,
        val numberRow: Boolean = false,
        val tidySpaces: Boolean = false,
        val typingSpeed: Boolean = false,
        val peckMode: Boolean = false,
        val ctrlPending: Boolean = false,
    )

    /** What a [notice] says; the service turns it into text. */
    sealed interface Notice {
        /** The command's own name. */
        data class Sent(val action: EditorAction) : Notice

        data class Autospace(val on: Boolean) : Notice

        /** A setting flipped from the keyboard, and the state it was left in. */
        data class Toggle(val action: EditorAction, val on: Boolean) : Notice

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

    /** Right-anchored, the mode named "one-handed" in the settings list. */
    const val DEFAULT_ONE_HANDED = "one_handed"
}
