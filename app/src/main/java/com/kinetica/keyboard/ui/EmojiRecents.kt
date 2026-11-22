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

package com.kinetica.keyboard.ui

/**
 * Ordering for the emoji picker's "frequently used" tab.
 *
 * Pure, because the picker is a view with no JVM reach; same shape as
 * PersonalWordRows.sortedForDisplay.
 *
 * Ordered by count, so one emoji sent once cannot push out the one sent every day. Recency
 * breaks ties and the spelling breaks those, so the order is deterministic for a given store.
 */
object EmojiRecents {

    /**
     * Three rows of EmojiPickerView.COLUMNS: enough to be worth opening, few enough that the
     * panel never scrolls.
     */
    const val MAX = 24

    /** One emoji's usage, free of Room and of Android. */
    data class Use(val emoji: String, val count: Int, val updatedAt: Long)

    /**
     * The tab's contents, best first. Zero and negative counts are dropped: a count fallen to
     * zero means the same as one never recorded.
     */
    fun ordered(uses: Collection<Use>, limit: Int = MAX): List<String> =
        uses.asSequence()
            .filter { it.count > 0 && it.emoji.isNotEmpty() }
            .sortedWith(
                compareByDescending<Use> { it.count }
                    .thenByDescending { it.updatedAt }
                    .thenBy { it.emoji },
            )
            .map { it.emoji }
            .distinct()
            .take(limit)
            .toList()

    /**
     * The search strip before a letter is typed: [recents] first, then the catalog's order, so a
     * fresh install or a build with no picks yet shows the common faces, not an empty row.
     */
    fun strip(recents: List<String>, catalog: List<String>, limit: Int = MAX): List<String> =
        (recents.asSequence() + catalog.asSequence()).distinct().take(limit).toList()
}
