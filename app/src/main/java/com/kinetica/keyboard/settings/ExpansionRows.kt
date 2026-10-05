package com.kinetica.keyboard.settings

import com.kinetica.keyboard.data.Expansion
import com.kinetica.keyboard.engine.AccentFolder
import com.kinetica.keyboard.keys.EditorAction
import com.kinetica.keyboard.keys.KeyCombo

/**
 * Ordering, filtering and validation for the expansion list.
 *
 * Pure so the rules are testable; the screen around it is Android-only. Modelled on
 * [PersonalWordRows], not [ChordRows], because the list can reach hundreds or thousands of rows.
 */
object ExpansionRows {

    /**
     * Alphabetical by trigger, unlike the personal-dictionary list beside it.
     *
     * That list sorts by influence because the row being hunted is the one distorting the
     * ranking. Here nothing competes, so the user only needs to find the row they wrote, and the
     * alphabet does that. Case is ignored so `Today` and `today` sit together.
     */
    fun sortedForDisplay(rows: List<Expansion>): List<Expansion> =
        rows.sortedWith(compareBy({ it.trigger.lowercase() }, { it.trigger }, { it.position }))

    /**
     * Rows whose trigger or target contains [query], in the order [rows] already has.
     *
     * Folded through [AccentFolder] like the personal list's filter, so a search agrees with the
     * keyboard about what a letter is. The target is searched as well as the trigger: a user
     * hunting an expansion usually remembers what it produces, not the shorthand they chose.
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
     * Whitespace is the boundary the cursor walk uses, so a trigger containing any would be
     * unreachable: no typing puts it at the cursor as one token. Blank is refused for the same
     * reason, and the length bound matches the walk's own.
     */
    fun isValidTrigger(trigger: String, maxLen: Int): Boolean =
        trigger.isNotEmpty() && trigger.length <= maxLen && trigger.none { it.isWhitespace() }

    /**
     * A trigger as it is saved: every whitespace character removed.
     *
     * A space can never be part of a trigger, and the usual one is the keyboard's own automatic
     * space after the word (#19). Removed, not refused, so the only refusals are blank and too
     * long.
     */
    fun cleanTrigger(raw: String): String = raw.filterNot { it.isWhitespace() }

    /**
     * One line of a row, with the target's newlines shown as a symbol: a five-line block laid
     * out as itself would push every other row off the screen.
     */
    fun preview(target: String, maxChars: Int): String {
        val flat = target.replace("\n", " ⏎ ").replace("\r", "")
        return if (flat.length <= maxChars) flat else flat.take(maxChars - 1) + "…"
    }

    /**
     * What a row shows for [target]: a combination or an action by its name, text by its
     * [preview], never the stored `action:paste` string.
     */
    fun shown(target: String, maxChars: Int, labelOf: (EditorAction) -> String): String {
        KeyCombo.parse(target)?.let { return it.label() }
        val action = EditorAction.of(target) ?: return preview(target, maxChars)
        return labelOf(action)
    }

    /** [trigger]'s targets in position order. */
    fun targetsOf(rows: List<Expansion>, trigger: String): List<String> =
        rows.filter { it.trigger == trigger }.sortedBy { it.position }.map { it.target }

    /**
     * The target lists a save writes, by trigger (#19). A new row on a trigger that
     * already has targets adds one at the end, so a trigger can hold several; an edit replaces
     * its own position; an edit to another trigger moves that one target there.
     */
    fun afterSave(rows: List<Expansion>, existing: Expansion?, trigger: String, target: String): Map<String, List<String>> {
        if (existing == null) {
            val list = targetsOf(rows, trigger)
            return mapOf(trigger to if (target in list) list else list + target)
        }
        val own = rows.filter { it.trigger == existing.trigger }.sortedBy { it.position }
        if (existing.trigger == trigger) {
            return mapOf(trigger to own.map { if (it.position == existing.position) target else it.target })
        }
        val left = own.filter { it.position != existing.position }.map { it.target }
        val moved = targetsOf(rows, trigger).let { if (target in it) it else it + target }
        return mapOf(existing.trigger to left, trigger to moved)
    }

    /** What [row]'s trigger keeps when that one row is deleted; empty deletes the trigger. */
    fun afterDelete(rows: List<Expansion>, row: Expansion): List<String> =
        rows.filter { it.trigger == row.trigger && it.position != row.position }.sortedBy { it.position }.map { it.target }

    /**
     * [labels] made distinct for the bar, a repeat marked with its place, so a tap names one
     * target even when two previews read the same (two signatures cut at the same letter).
     */
    fun distinctLabels(labels: List<String>): List<String> {
        val seen = HashMap<String, Int>()
        return labels.map { l ->
            val n = (seen[l] ?: 0) + 1
            seen[l] = n
            if (n == 1) l else "$l ($n)"
        }
    }
}
