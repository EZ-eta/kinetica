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
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface ExpansionDao {
    @Insert
    fun insert(rows: List<Expansion>)

    @Query("SELECT * FROM expansions ORDER BY triggerText, position")
    fun all(): List<Expansion>

    @Query("DELETE FROM expansions WHERE triggerText = :trigger")
    fun deleteByTrigger(trigger: String)

    /**
     * Replaces every target for [trigger]. Mirrors [ChordShortcutDao.assign]: one trigger
     * owns its whole list, so an edit cannot leave an orphaned position behind.
     */
    @Transaction
    fun assign(trigger: String, targets: List<String>) {
        deleteByTrigger(trigger)
        if (targets.isEmpty()) return
        insert(targets.mapIndexed { i, t -> Expansion(trigger, i, t) })
    }
}
