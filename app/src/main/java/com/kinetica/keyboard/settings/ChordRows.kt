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

import com.kinetica.keyboard.data.ChordShortcut
import com.kinetica.keyboard.keys.ChordKey
import com.kinetica.keyboard.keys.ChordTrigger
import com.kinetica.keyboard.keys.EditorAction

/**
 * Ordering for the chord list.
 *
 * Pure so it can be tested; the screen around it is Android-only. A long chord list needs an order
 * to stay legible.
 */
object ChordRows {

    enum class Sort { KEY_ASC, KEY_DESC, FUNCTION }

    /**
     * The next sort in the cycle, so one button can carry all three.
     */
    fun next(sort: Sort): Sort = when (sort) {
        Sort.KEY_ASC -> Sort.KEY_DESC
        Sort.KEY_DESC -> Sort.FUNCTION
        Sort.FUNCTION -> Sort.KEY_ASC
    }

    /**
     * What a row does, as a sort key: the action's own name, or "" for a text chord.
     *
     * Text sorts first because it is the common case and the one a user scans for; the command
     * chords then group by name instead of scattering through the alphabet of their keys.
     */
    fun functionKey(expansion: String): String = EditorAction.of(expansion)?.name ?: ""

    /** [rows] in [sort] order. Key is always the tie-break, so the order is total. */
    fun sorted(rows: List<ChordShortcut>, sort: Sort): List<ChordShortcut> = when (sort) {
        Sort.KEY_ASC -> rows.sortedBy { it.chord }
        Sort.KEY_DESC -> rows.sortedByDescending { it.chord }
        Sort.FUNCTION -> rows.sortedWith(
            compareBy({ functionKey(it.expansion) }, { it.chord }),
        )
    }

    /**
     * [rows] in groups by the key they are held with, `?123` first, each group in [sort] order;
     * an empty group is left out. A row this build cannot read stays visible under `?123`, so it
     * can still be deleted.
     */
    fun grouped(rows: List<ChordShortcut>, sort: Sort): List<Pair<ChordTrigger, List<ChordShortcut>>> {
        val by = rows.groupBy { ChordKey.decode(it.chord)?.trigger ?: ChordTrigger.MODE }
        return ChordTrigger.entries.mapNotNull { t -> by[t]?.let { t to sorted(it, sort) } }
    }

    /** The key a row shows: the tapped key alone, whatever it is held with. */
    fun keyLabel(row: ChordShortcut): String = ChordKey.decode(row.chord)?.key?.toString() ?: row.chord
}
