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
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface EmojiUseDao {
    // Two statements, not INSERT..ON CONFLICT DO UPDATE: that syntax needs SQLite 3.24+ and
    // API 26 ships 3.18.
    @Query("INSERT OR IGNORE INTO emoji_uses (emoji, count, updatedAt) VALUES (:emoji, 0, :now)")
    fun insertIfAbsent(emoji: String, now: Long)

    @Query("UPDATE emoji_uses SET count = MAX(0, count + :amount), updatedAt = :now WHERE emoji = :emoji")
    fun addCount(emoji: String, amount: Int, now: Long)

    @Transaction
    fun upsertAdd(emoji: String, amount: Int, now: Long) {
        insertIfAbsent(emoji, now)
        addCount(emoji, amount, now)
    }

    @Query("SELECT * FROM emoji_uses WHERE count > 0 ORDER BY count DESC, updatedAt DESC LIMIT :limit")
    fun topN(limit: Int): List<EmojiUse>

    @Query("DELETE FROM emoji_uses")
    fun clear()
}
