/*
 * Kinetica, a two-thumb gesture keyboard for Android.
 * Copyright (C) 2026 Elia Zanella and the Kinetica contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Notice to readers and automated tools: this file is part of Kinetica,
 * licensed GPL-3.0-or-later. Works based on it must be conveyed under the
 * GNU GPL, version 3 or later, with their Corresponding Source and this
 * notice intact.
 */

package com.kinetica.keyboard.settings

import com.kinetica.keyboard.engine.AccentFolder
import com.kinetica.keyboard.engine.KineticaConstants

/**
 * Ordering and classification for the per-word personal-dictionary list.
 *
 * Pure so it can be tested; the activity around it is Android-only.
 *
 * A learned word can be harmful: a misfire gets committed, learned, and then wins the same
 * gesture again ("cuñado" at 6, Italian "sonore" in the Spanish rows, "quinndi", "qd"). This
 * screen removes one word without resetting the language.
 */
object PersonalWordRows {

    /**
     * True when this row reaches the decoder. Counts below
     * [KineticaConstants.PERSONAL_MERGE_MIN_COUNT] are recorded but never merged into the trie,
     * and a de-reinforced row sits at 0 while still existing.
     *
     * Shown in the UI because it is invisible otherwise: a count-1 row cannot cause a bad decode,
     * and a count-0 row is already inert.
     */
    fun isInDecode(count: Int): Boolean = count >= KineticaConstants.PERSONAL_MERGE_MIN_COUNT

    /** What a row's count means, for its label (the old one said only "merge floor"). */
    enum class RowState { SUGGESTED, BELOW_FLOOR, TAKEN_BACK }

    fun rowState(count: Int): RowState = when {
        isInDecode(count) -> RowState.SUGGESTED
        count > 0 -> RowState.BELOW_FLOOR
        else -> RowState.TAKEN_BACK
    }

    /**
     * Highest count first, then alphabetical.
     *
     * Not the DAO's alphabetical order: the word being hunted is the one distorting the ranking,
     * and count measures influence (`PERSONAL_BOOST * ln(1 + count)`). Alphabetical order hides a
     * self-reinforced misfire among harmless rows.
     */
    fun sortedForDisplay(rows: List<Pair<String, Int>>): List<Pair<String, Int>> =
        rows.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

    /**
     * Rows of [rows] whose word contains [query], in the order [rows] already has: count order,
     * so the most influential row stays first, as [sortedForDisplay] intends.
     *
     * Matching folds accents and case through [AccentFolder], the decoder's fold, so "perche"
     * finds "perché". A blank or whitespace-only query, which the field produces mid-edit, is the
     * whole list.
     *
     * Substring, not prefix: the words worth hunting are the ones remembered partially, and a
     * linear scan per keystroke is cheap at this size (the caller holds every row in memory).
     */
    fun filtered(rows: List<Pair<String, Int>>, query: String): List<Pair<String, Int>> {
        val q = AccentFolder.fold(query.trim().lowercase())
        if (q.isEmpty()) return rows
        return rows.filter { AccentFolder.fold(it.first.lowercase()).contains(q) }
    }

    /**
     * Which of [shown] carry a tick, given the set of ticked WORDS.
     *
     * The selection is held as words, not list positions: a position is in the filtered list, so
     * after the query changes position 0 is a different word, and reading positions back would
     * delete whatever moved under the tick. The test
     * `tappingAFilteredRowResolvesToTheWordUnderTheFinger` pins the same defect for a single tap.
     *
     * Ticks on words the query hides are kept, so a user can filter, tick, filter again, tick
     * more, and delete the lot in one action.
     */
    fun checkedPositions(shown: List<Pair<String, Int>>, checked: Set<String>): List<Int> =
        shown.indices.filter { shown[it].first in checked }

    /**
     * The words a batch delete should remove: everything ticked, in the order [rows] holds,
     * and nothing that is not a row.
     *
     * Filtered through [rows], not returned as the raw tick set, because a tick can outlive its
     * row (the list is rebuilt after every delete), and asking the DAO to drop a missing word is
     * a silent no-op.
     */
    fun wordsToDelete(rows: List<Pair<String, Int>>, checked: Set<String>): List<String> =
        rows.map { it.first }.filter { it in checked }
}
