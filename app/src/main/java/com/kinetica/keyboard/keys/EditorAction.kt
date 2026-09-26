package com.kinetica.keyboard.keys

// File-level, because an enum entry cannot read its own companion object: the
// entries are constructed before the companion is initialized.
private const val ACTION_PREFIX = "action:"

/**
 * A command a key, a chord, a swipe or the suggestion bar can perform instead of
 * inserting text.
 *
 * Most are editor commands that the app carries out. The last few are the keyboard's own
 * state - the language, the autospace, where the keys sit - and they live here rather than
 * in a type of their own because this enum is what every trigger surface already reads:
 * one entry appears in the chord picker, the edge-swipe picker, the ?123 menu and the
 * suggestion bar's action row at once.
 *
 * The reserved-output convention is the shipped one: a key whose `output` starts
 * with [PREFIX] is intercepted before the commit path and dispatched as a command,
 * and the prefix can never collide with typeable text. What is new is that the
 * mapping lives in one place, so the comma key and the chord shortcuts cannot
 * disagree about what `action:paste` means - they used to, and the chord path
 * inserted the string literally.
 *
 * Pure on purpose: the platform ids these become (`android.R.id.paste` and
 * friends) are resolved by the caller, so parsing is testable without a device.
 */
enum class EditorAction(val output: String) {
    PASTE("${ACTION_PREFIX}paste"),
    COPY("${ACTION_PREFIX}copy"),
    CUT("${ACTION_PREFIX}cut"),
    SELECT_ALL("${ACTION_PREFIX}select_all"),

    /**
     * Delete the word in progress - or the one just committed - and start it again in
     * place. Nintype's "re-type", asked for twice, and the only entry here that is not a
     * platform context-menu action: it acts ON the pending word rather than after it, so
     * the caller must not settle the word first. Living here anyway is the point - the
     * suggestion bar's button and a `?123` chord are then two triggers for one
     * implementation rather than two implementations.
     */
    RETYPE("${ACTION_PREFIX}retype"),

    /**
     * Replace the trigger already written at the cursor with its stored expansion.
     *
     * The second entry here that is not a platform context-menu action, and like [RETYPE]
     * it acts ON text rather than after it - but on text the editor already holds rather
     * than on a pending word, so unlike RETYPE it settles the word first.
     *
     * Living here is what makes it bindable from an edge swipe and from a `?123` chord at
     * once, with no routing of its own, because both already dispatch through
     * `performIfAction`.
     */
    EXPANDIFY("${ACTION_PREFIX}expandify"),

    /**
     * Ask the app to undo, and to redo.
     *
     * Platform context-menu commands like [PASTE], so the app does the work and the
     * keyboard keeps no history of its own. `performContextMenuAction` reports that the
     * call was delivered rather than that anything happened, so an editor that does not
     * implement them is silently a no-op - the same contract paste has shipped under
     * since v1.0.2. A stock text field has a real undo stack; a web view may not.
     */
    UNDO("${ACTION_PREFIX}undo"),
    REDO("${ACTION_PREFIX}redo"),

    /** Open Kinetica's settings. The ?123 hold reached this and nothing else did. */
    SETTINGS("${ACTION_PREFIX}settings"),

    /** Next enabled language, in the canonical cycle order. */
    NEXT_LANGUAGE("${ACTION_PREFIX}next_language"),

    /** Turn the automatic space on or off for good, not for one word. */
    TOGGLE_AUTOSPACE("${ACTION_PREFIX}toggle_autospace"),

    /** Shrink the keys to one side of the screen, or put them back. */
    ONE_HANDED("${ACTION_PREFIX}one_handed"),

    /**
     * Write today's date, or the time, at the cursor, in the phone's own format. Named in
     * the expandify request (#19) and useful from a chord or the bar as well.
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
         * True for a string that looks like a command but names none of them.
         * Worth telling apart from ordinary text: it is almost certainly a typo
         * in a chord expansion, and inserting `action:pate` into someone's
         * document is a worse answer than doing nothing.
         */
        fun isUnknownAction(output: String): Boolean =
            output.startsWith(PREFIX) && of(output) == null
    }
}
