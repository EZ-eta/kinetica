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

/**
 * Finding a setting by typing what you call it.
 *
 * Submenus hide rows, so search covers titles, summaries and synonyms; the synonyms matter
 * most, since a user looking for a setting rarely types its exact title.
 *
 * Pure, so the ranking is testable without a device, as with [BarPaging] and [PersonalWordRows].
 * The Android side walks the inflated screen and hands the rows over. There is no stored index:
 * the rows are read from the live preference tree each time the field opens, so nothing goes
 * stale after an update or a language change.
 */
object SettingsIndex {

    /**
     * One searchable row. [screenKey] is null for a row on the top level, and [screenTitle] is
     * shown under the title so a hit says where it lives as well as what it is.
     */
    data class Entry(
        val key: String,
        val title: String,
        val summary: String,
        val screenKey: String?,
        val screenTitle: String,
        val terms: List<String> = emptyList(),
    )

    /**
     * Rows of [entries] matching [query], best match first.
     *
     * Folded and lowercased through [AccentFolder], the decoder's fold, and matched as a
     * substring, for the reasons [PersonalWordRows.filtered] gives. Unlike that function, a blank
     * query matches nothing: every row would be the settings screen again, and the caller reads
     * the empty result as "close the overlay".
     *
     * Ranked title, then summary, then synonym. The sort is stable, so within a rank hits keep
     * tree order, the order they appear in settings.
     */
    fun match(entries: List<Entry>, query: String): List<Entry> {
        val q = AccentFolder.fold(query.trim().lowercase())
        if (q.isEmpty()) return emptyList()
        return entries
            .map { it to rankOf(it, q) }
            .filter { it.second != RANK_NONE }
            .sortedBy { it.second }
            .map { it.first }
    }

    /**
     * Where [entry] matches [folded], as a sort key. Lower is better; [RANK_NONE] is no
     * match at all.
     *
     * A title hit beats a summary hit because the title is what the user is trying to remember,
     * and a summary mentioning a word in passing should not push down the row named after it.
     * Synonyms come last: they make a row reachable, not prominent.
     */
    internal fun rankOf(entry: SettingsIndex.Entry, folded: String): Int = when {
        folded in fold(entry.title) -> RANK_TITLE
        folded in fold(entry.summary) -> RANK_SUMMARY
        entry.terms.any { folded in fold(it) } -> RANK_TERM
        else -> RANK_NONE
    }

    private fun fold(s: String): String = AccentFolder.fold(s.lowercase())

    internal const val RANK_TITLE = 0
    internal const val RANK_SUMMARY = 1
    internal const val RANK_TERM = 2
    internal const val RANK_NONE = 3
}
