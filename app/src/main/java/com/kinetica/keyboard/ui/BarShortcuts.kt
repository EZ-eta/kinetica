package com.kinetica.keyboard.ui

/**
 * When the suggestion bar shows its shortcut row, and when the button that brings the row back.
 *
 * On its own the row shows only on an empty bar, which in practice is a new field. The button
 * forces it over the words until the next key. Pure, so the rule is testable without a view,
 * like [BarPaging].
 */
object BarShortcuts {

    /**
     * Whether the row is drawn. Unforced, words, the correction strip and a word in progress each
     * keep it down: a thumb aiming at a suggestion must not land on a shortcut.
     */
    fun rowShown(
        hasActions: Boolean,
        hasWords: Boolean,
        correctionMode: Boolean,
        wordPending: Boolean,
        forced: Boolean,
    ): Boolean = hasActions && (forced || (!hasWords && !correctionMode && !wordPending))

    /**
     * Whether the button is drawn: the switch on, shortcuts to show, and words for the row to
     * cover. On an empty bar the row is already up and the button would only cost width.
     */
    fun buttonShown(enabled: Boolean, hasActions: Boolean, hasWords: Boolean): Boolean =
        enabled && hasActions && hasWords
}
