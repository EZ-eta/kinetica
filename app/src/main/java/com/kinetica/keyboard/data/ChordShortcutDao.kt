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

package com.kinetica.keyboard.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface ChordShortcutDao {
    @Insert
    fun insert(chord: ChordShortcut): Long

    @Delete
    fun delete(chord: ChordShortcut)

    @Query("SELECT * FROM chord_shortcuts")
    fun all(): List<ChordShortcut>

    @Query("DELETE FROM chord_shortcuts WHERE chord = :chord")
    fun deleteByChord(chord: String)

    /** One expansion per letter: assignment replaces any previous binding. */
    @Transaction
    fun assign(chord: String, expansion: String) {
        deleteByChord(chord)
        insert(ChordShortcut(chord = chord, expansion = expansion))
    }

    /** An edit as one step: the old row goes and the new key takes its binding, or neither. */
    @Transaction
    fun replace(old: ChordShortcut?, chord: String, expansion: String) {
        if (old != null) delete(old)
        assign(chord, expansion)
    }
}
