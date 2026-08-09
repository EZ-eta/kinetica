package com.kinetica.keyboard.settings

import com.kinetica.keyboard.engine.AccentFolder
import com.kinetica.keyboard.engine.KineticaConstants

/**
 * Ordering and classification for the per-word personal-dictionary list.
 *
 * Pure so it can be tested: the activity around it is Android-only, but the two
 * decisions that make the screen useful rather than merely present are not.
 *
 * The screen exists because a learned word can be actively harmful and there was
 * no way to remove one short of resetting the whole language. Every historical
 * instance was the same shape - a misfire that got committed, learned, and then
 * won the same gesture again ("cuñado" at 6, Italian "sonore" inside the Spanish
 * rows, "quinndi", "qd").
 */
object PersonalWordRows {

    /**
     * True when this row actually reaches the decoder. Counts below
     * [KineticaConstants.PERSONAL_MERGE_MIN_COUNT] are recorded but never merged
     * into the trie, and a de-reinforced row sits at 0 while still existing.
     *
     * Surfaced in the UI because the distinction is invisible otherwise and it is
     * precisely what confused the item-13b diagnosis: a count-1 row cannot be the
     * cause of a bad decode, and a count-0 row is already inert.
     */
    fun isInDecode(count: Int): Boolean = count >= KineticaConstants.PERSONAL_MERGE_MIN_COUNT

    /**
     * Highest count first, then alphabetical.
     *
     * Deliberately not the DAO's alphabetical order. The word you are looking for
     * is the one distorting your ranking, and influence is what count measures -
     * `PERSONAL_BOOST * ln(1 + count)`. Alphabetical hides a self-reinforced
     * misfire in the middle of a list of harmless ones.
     */
    fun sortedForDisplay(rows: List<Pair<String, Int>>): List<Pair<String, Int>> =
        rows.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

    /**
     * Rows of [rows] whose word contains [query], in the order [rows] already
     * has - i.e. count order, not relevance and not alphabetical.
     *
     * Preserving the incoming order is the point rather than an implementation
     * detail: [sortedForDisplay] puts the most influential row first because
     * that is the one distorting a ranking, and a filter that re-sorted by match
     * position would undo the screen's one design decision.
     *
     * Matching folds accents and case through [AccentFolder], the same fold the
     * decoder uses, so "perche" finds "perché" and the screen agrees with the
     * trie about what a letter is. A blank or whitespace-only query - which the
     * search field produces constantly mid-edit - is the whole list, never an
     * empty one.
     *
     * Substring rather than prefix: the words worth hunting are the ones you
     * remember partially, and the list is small enough that a linear scan per
     * keystroke is free (the caller already holds every row in memory).
     */
    fun filtered(rows: List<Pair<String, Int>>, query: String): List<Pair<String, Int>> {
        val q = AccentFolder.fold(query.trim().lowercase())
        if (q.isEmpty()) return rows
        return rows.filter { AccentFolder.fold(it.first.lowercase()).contains(q) }
    }
}
