package com.kinetica.keyboard.keys

// File-level, because an enum entry cannot read its own companion object: the
// entries are constructed before the companion is initialized.
private const val ACTION_PREFIX = "action:"

/**
 * An editor command a key or a chord can perform instead of inserting text.
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
    ;

    companion object {
        const val PREFIX = ACTION_PREFIX

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
