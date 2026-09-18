package com.kinetica.keyboard.settings

import com.kinetica.keyboard.engine.AccentFolder

/**
 * Finding a setting by typing what you call it.
 *
 * Sixty rows over five subscreens is past what anyone scans, and the reports that drove the
 * submenus (R49) turned into reports that the submenus hid things. The request (R82) asked
 * for full text plus "synonym tagging to match search intent without exact terminology",
 * which is the half that matters: nobody looking for the autospace delay searches "delay".
 *
 * Pure, so the ranking is testable without a device - the same split [BarPaging] and
 * [PersonalWordRows] use. The Android side walks the inflated screen and hands the rows
 * over; nothing here knows what a Preference is.
 *
 * It also answers the "index rebuilt on update or language change" half of the request by
 * not having an index: the rows are read from the live preference tree each time the field
 * is opened, so there is no stored copy that can go stale.
 */
object SettingsIndex {

    /**
     * One searchable row. [screenKey] is null for a row on the top level, and
     * [screenTitle] is what the result list shows underneath the title so a hit says where
     * it lives as well as what it is.
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
     * Folded and lowercased through [AccentFolder], the same fold the decoder uses, and
     * substring rather than prefix, both for the reasons [PersonalWordRows.filtered]
     * states. **One deliberate difference from that function: a blank query is NOTHING
     * here, not everything.** A search field showing all sixty rows is the settings screen
     * with an extra step, and the caller uses the empty result to mean "close the overlay".
     *
     * Ranked title, then summary, then synonym. Within a rank the incoming order is kept,
     * which is tree order, so two equally good hits appear in the order they appear in
     * settings. The sort is stable and that is load-bearing rather than incidental.
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
     * A title hit beats a summary hit because the title is what the user is trying to
     * remember, and a summary mentioning a word in passing should not push the row that is
     * actually named after it down the list. Synonyms come last for the same reason: they
     * exist to make a row reachable, not to make it prominent.
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
