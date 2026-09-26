package com.kinetica.keyboard.settings

import com.kinetica.keyboard.data.Expansion
import com.kinetica.keyboard.engine.AccentFolder
import com.kinetica.keyboard.keys.EditorAction

/**
 * Ordering, filtering and validation for the expansion list.
 *
 * Pure so the rules are testable: the screen around it is Android-only, but none of these
 * decisions is. It is modelled on [PersonalWordRows] rather than [ChordRows] because of
 * the size the list is expected to reach - its reporter describes "hundreds if not
 * thousands" of expansions, where chords cap at twenty-six.
 */
object ExpansionRows {

    /**
     * Alphabetical by trigger, unlike the personal-dictionary list beside it.
     *
     * That list sorts by influence because the row you are hunting is the one distorting
     * your ranking. Here every row does exactly what it says and nothing competes, so the
     * question is only "where is the one I wrote", and the answer is the alphabet. Case is
     * ignored so `Today` and `today` sit together rather than in separate blocks.
     */
    fun sortedForDisplay(rows: List<Expansion>): List<Expansion> =
        rows.sortedWith(compareBy({ it.trigger.lowercase() }, { it.trigger }, { it.position }))

    /**
     * Rows whose trigger or target contains [query], in the order [rows] already has.
     *
     * Folded through [AccentFolder] like the personal list's filter, so a search agrees
     * with the keyboard about what a letter is. The TARGET is searched as well as the
     * trigger, which the word list has no equivalent of: a user hunting an expansion
     * usually remembers what it produces, not the shorthand they chose for it.
     */
    fun filtered(rows: List<Expansion>, query: String): List<Expansion> {
        val q = AccentFolder.fold(query.trim().lowercase())
        if (q.isEmpty()) return rows
        return rows.filter {
            AccentFolder.fold(it.trigger.lowercase()).contains(q) ||
                AccentFolder.fold(it.target.lowercase()).contains(q)
        }
    }

    /**
     * Whether a trigger can be saved.
     *
     * Whitespace is the boundary the cursor walk uses, so a trigger containing any would
     * be unreachable: no amount of typing puts it at the cursor as one token. Blank is
     * refused for the same reason, and the length bound matches the walk's own.
     */
    fun isValidTrigger(trigger: String, maxLen: Int): Boolean =
        trigger.isNotEmpty() && trigger.length <= maxLen && trigger.none { it.isWhitespace() }

    /**
     * One line of a row, with the target's newlines shown rather than laid out.
     *
     * A target may be a five-line block; rendered as itself it would be as tall as it is
     * long and push every other row off the screen.
     */
    fun preview(target: String, maxChars: Int): String {
        val flat = target.replace("\n", " ⏎ ").replace("\r", "")
        return if (flat.length <= maxChars) flat else flat.take(maxChars - 1) + "…"
    }

    /**
     * What a row shows for [target]: an action by its name, text by its [preview]. Without
     * this an action target read `action:paste`, the reserved string the picker exists to
     * hide.
     */
    fun shown(target: String, maxChars: Int, labelOf: (EditorAction) -> String): String {
        val action = EditorAction.of(target) ?: return preview(target, maxChars)
        return labelOf(action)
    }
}
