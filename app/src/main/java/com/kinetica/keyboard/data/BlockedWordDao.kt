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

@Dao
interface BlockedWordDao {
    // INSERT OR IGNORE, not an upsert: ON CONFLICT DO UPDATE needs SQLite 3.24+ and API 26
    // devices ship 3.18. Blocking an already-blocked word is a no-op anyway.
    @Query("INSERT OR IGNORE INTO blocked_words (word, lang, addedAt) VALUES (:word, :lang, :now)")
    fun block(word: String, lang: String, now: Long)

    @Query("DELETE FROM blocked_words WHERE word = :word AND lang = :lang")
    fun unblock(word: String, lang: String)

    @Query("SELECT * FROM blocked_words WHERE lang = :lang ORDER BY word")
    fun allForLanguage(lang: String): List<BlockedWord>

    /** The spellings alone, all the dictionary load needs. */
    @Query("SELECT word FROM blocked_words WHERE lang = :lang")
    fun wordsForLanguage(lang: String): List<String>

    @Query("SELECT DISTINCT word FROM blocked_words")
    fun allWords(): List<String>

    @Query("SELECT COUNT(*) FROM blocked_words WHERE lang = :lang")
    fun countForLanguage(lang: String): Int
}
