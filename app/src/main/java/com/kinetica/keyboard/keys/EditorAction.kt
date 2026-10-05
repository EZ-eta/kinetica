package com.kinetica.keyboard.keys

// File-level, because an enum entry cannot read its own companion object: the
// entries are constructed before the companion is initialized.
private const val ACTION_PREFIX = "action:"

/**
 * A command a key, a chord, a swipe or the suggestion bar can perform instead of
 * inserting text.
 *
 * Most are editor commands the app carries out. Others change the keyboard's own state (the
 * language, the autospace, where the keys sit) and live here too, because every trigger surface
 * reads this enum: one entry appears in the chord picker, the edge-swipe picker, the ?123 menu
 * and the suggestion bar's action row at once.
 *
 * A key whose `output` starts with [PREFIX] is intercepted before the commit path and dispatched
 * as a command; the prefix cannot collide with typeable text. The mapping lives in one place, so
 * the comma key and the chord shortcuts agree on what `action:paste` means.
 *
 * Pure: the platform ids these become (`android.R.id.paste` and friends) are resolved by the
 * caller, so parsing is testable without a device.
 */
enum class EditorAction(val output: String) {
    PASTE("${ACTION_PREFIX}paste"),
    COPY("${ACTION_PREFIX}copy"),
    CUT("${ACTION_PREFIX}cut"),
    SELECT_ALL("${ACTION_PREFIX}select_all"),

    /**
     * Delete the word in progress, or the one just committed, and start it again in place, as
     * Nintype's "re-type" does. It acts on the pending word, not after it, so the caller must not
     * settle the word first. Living here makes the suggestion bar's button and a `?123` chord two
     * triggers for one implementation.
     */
    RETYPE("${ACTION_PREFIX}retype"),

    /**
     * Replace the trigger already written at the cursor with its stored expansion.
     *
     * Like [RETYPE] it acts on text, not after it, but on text the editor already holds, so it
     * settles the pending word first. Edge swipes and `?123` chords both dispatch through
     * `performIfAction`, so it needs no routing of its own.
     */
    EXPANDIFY("${ACTION_PREFIX}expandify"),

    /**
     * Ask the app to undo, and to redo.
     *
     * Platform context-menu commands like [PASTE], so the app does the work and the keyboard
     * keeps no history of its own. `performContextMenuAction` reports delivery, not effect, so in
     * an editor without them they are a silent no-op, as paste is. A stock text field has an undo
     * stack; a web view may not.
     */
    UNDO("${ACTION_PREFIX}undo"),
    REDO("${ACTION_PREFIX}redo"),

    /** Open Kinetica's settings. */
    SETTINGS("${ACTION_PREFIX}settings"),

    /** Next enabled language, in the canonical cycle order. */
    NEXT_LANGUAGE("${ACTION_PREFIX}next_language"),

    /** Turn the automatic space on or off for good, not for one word. */
    TOGGLE_AUTOSPACE("${ACTION_PREFIX}toggle_autospace"),

    /** Shrink the keys to one side of the screen, or put them back. */
    ONE_HANDED("${ACTION_PREFIX}one_handed"),

    /**
     * Write today's date, or the time, at the cursor, in the phone's own format (#19).
     */
    DATE("${ACTION_PREFIX}date"),
    TIME("${ACTION_PREFIX}time"),

    /** What the enter key does: a newline, or the field's own send or search. */
    ENTER("${ACTION_PREFIX}enter"),

    /**
     * Put the line the cursor is on onto the clipboard. Read out of the editor and written
     * to the clipboard directly, so nothing is selected and no remembered offset is used.
     */
    COPY_LINE("${ACTION_PREFIX}copy_line"),

    /**
     * Settings flipped from the keyboard itself: the idle bar's predictions, the recent words, the
     * numbers row, tidy spaces and the typing speed.
     */
    TOGGLE_NEXT_WORD("${ACTION_PREFIX}toggle_next_word"),
    TOGGLE_RECENT_WORDS("${ACTION_PREFIX}toggle_recent_words"),
    TOGGLE_NUMBER_ROW("${ACTION_PREFIX}toggle_number_row"),
    TOGGLE_TIDY_SPACES("${ACTION_PREFIX}toggle_tidy_spaces"),
    TOGGLE_TYPING_SPEED("${ACTION_PREFIX}toggle_typing_speed"),

    /** Peck-type on or off, an action like any other so any shortcut can carry it. */
    TOGGLE_PECK_MODE("${ACTION_PREFIX}toggle_peck_mode"),

    /**
     * Keys the keyboard has no key for, sent as key events (SpecialKeys), as a hardware keyboard
     * would: Tab indents where the editor takes tabs and moves focus where it does not.
     */
    TAB("${ACTION_PREFIX}tab"),
    ESCAPE("${ACTION_PREFIX}escape"),
    FORWARD_DELETE("${ACTION_PREFIX}forward_delete"),
    HOME("${ACTION_PREFIX}home"),
    END("${ACTION_PREFIX}end"),
    ARROW_UP("${ACTION_PREFIX}arrow_up"),
    ARROW_DOWN("${ACTION_PREFIX}arrow_down"),
    ARROW_LEFT("${ACTION_PREFIX}arrow_left"),
    ARROW_RIGHT("${ACTION_PREFIX}arrow_right"),
    PAGE_UP("${ACTION_PREFIX}page_up"),
    PAGE_DOWN("${ACTION_PREFIX}page_down"),

    /** The keyboard's own backspace as a shortcut. */
    BACKSPACE("${ACTION_PREFIX}backspace"),

    /** Holds Ctrl for the next key tapped: then `a` sends Ctrl+A. */
    CTRL_NEXT("${ACTION_PREFIX}ctrl_next"),
    ;

    companion object {
        const val PREFIX = ACTION_PREFIX

        /**
         * Actions an expansion may not fire. EXPANDIFY would find its own trigger again and
         * never stop. RETYPE runs after expandify has cleared the word state, so it would
         * delete the word before the trigger. UNDO and REDO would take back the trigger's
         * own removal, which is the latest edit the app knows about.
         */
        val NOT_EXPANSION_TARGETS: Set<EditorAction> = setOf(EXPANDIFY, RETYPE, UNDO, REDO)

        /** The action [output] names, or null when it is ordinary text. */
        fun of(output: String): EditorAction? {
            if (!output.startsWith(PREFIX)) return null
            return entries.firstOrNull { it.output == output }
        }

        /**
         * True for a string that looks like a command but names none of them: almost certainly a
         * typo in a chord expansion, and inserting `action:pate` into a document is worse than
         * doing nothing.
         */
        fun isUnknownAction(output: String): Boolean =
            output.startsWith(PREFIX) && of(output) == null
    }
}
