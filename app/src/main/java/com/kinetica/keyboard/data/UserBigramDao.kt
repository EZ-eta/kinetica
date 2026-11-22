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
interface UserBigramDao {
    // Two statements, not an upsert: ON CONFLICT DO UPDATE needs SQLite 3.24+ and API 26
    // devices ship 3.18.
    @Query(
        "INSERT OR IGNORE INTO user_bigrams (prev, next, lang, count, updatedAt) " +
            "VALUES (:prev, :next, :lang, 0, :now)",
    )
    fun insertIfAbsent(prev: String, next: String, lang: String, now: Long)

    @Query(
        "UPDATE user_bigrams SET count = MAX(0, count + :amount), updatedAt = :now " +
            "WHERE prev = :prev AND next = :next AND lang = :lang",
    )
    fun addWeight(prev: String, next: String, lang: String, amount: Int, now: Long)

    @Transaction
    fun upsertAdd(prev: String, next: String, lang: String, amount: Int, now: Long) {
        insertIfAbsent(prev, next, lang, now)
        addWeight(prev, next, lang, amount, now)
    }

    @Query("SELECT * FROM user_bigrams WHERE lang = :lang AND count > 0 ORDER BY count DESC LIMIT :limit")
    fun topN(lang: String, limit: Int): List<UserBigram>

    @Query("SELECT COUNT(*) FROM user_bigrams WHERE lang = :lang AND count > 0")
    fun countForLanguage(lang: String): Int

    /** Reset of the learned phrases for one language. */
    @Query("DELETE FROM user_bigrams WHERE lang = :lang")
    fun clearLanguage(lang: String)
}
