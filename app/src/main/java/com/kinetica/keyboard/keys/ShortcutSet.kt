package com.kinetica.keyboard.keys

/**
 * One shortcut set as its settings screen edits it: which actions are on and in what order.
 *
 * Pure, because the screen is a view with no JVM harness and the list arithmetic is the part
 * that can go wrong. Chosen actions are listed first in their order, the rest after them in
 * [ActionRow.ALL]'s order; only chosen ones move.
 */
class ShortcutSet(initialChosen: List<EditorAction>) {

    private val chosen = initialChosen.distinct().toMutableList()

    /** Every action in display order, each with whether it is on. */
    fun rows(): List<Pair<EditorAction, Boolean>> =
        chosen.map { it to true } + ActionRow.ALL.filter { it !in chosen }.map { it to false }

    /** Turns [action] on, at the end of the chosen ones, or off. */
    fun toggle(action: EditorAction) {
        if (!chosen.remove(action)) chosen.add(action)
    }

    /** Moves a chosen [action] one place up (-1) or down (+1); anything else is a no-op. */
    fun move(action: EditorAction, delta: Int) {
        val i = chosen.indexOf(action)
        val j = i + delta
        if (i < 0 || j !in chosen.indices) return
        chosen[i] = chosen[j]
        chosen[j] = action
    }

    fun canMove(action: EditorAction, delta: Int): Boolean {
        val i = chosen.indexOf(action)
        return i >= 0 && (i + delta) in chosen.indices
    }

    /** The selection as the preference stores it. */
    fun selection(): Set<String> = chosen.map { it.name }.toSet()

    /** The order as the preference stores it. */
    fun order(): String = ActionRow.encodeOrder(chosen)

    companion object {
        /** The screen's starting state, from what the two preferences hold. */
        fun from(selection: Set<String>, storedOrder: String?): ShortcutSet =
            ShortcutSet(ActionRow.ordered(selection, ActionRow.decodeOrder(storedOrder)))
    }
}
