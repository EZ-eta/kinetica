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
interface UserWordDao {
    // Two statements instead of INSERT..ON CONFLICT DO UPDATE: that upsert
    // syntax needs SQLite 3.24+, but API 26 devices ship 3.18.
    @Query("INSERT OR IGNORE INTO user_words (word, lang, frequency, updatedAt) VALUES (:word, :lang, 0, :now)")
    fun insertIfAbsent(word: String, lang: String, now: Long)

    // MAX(0, ...) so a negative amount, a correction taking back a mistaken commit, never
    // drives a count below zero.
    @Query("UPDATE user_words SET frequency = MAX(0, frequency + :amount), updatedAt = :now WHERE word = :word AND lang = :lang")
    fun addWeight(word: String, lang: String, amount: Int, now: Long)

    /** [word]'s stored count in [lang], null without a row. */
    @Query("SELECT frequency FROM user_words WHERE word = :word AND lang = :lang")
    fun storedCount(word: String, lang: String): Int?

    /** Manual reinforcement and commit learning share this path. */
    @Transaction
    fun upsertAdd(word: String, lang: String, amount: Int, now: Long) {
        insertIfAbsent(word, lang, now)
        addWeight(word, lang, amount, now)
    }

    /**
     * The capital and its votes, written whole: decided from the live mirror after a vote, or as
     * a backup or an import carries them.
     */
    @Query("UPDATE user_words SET display = :display, caseVotes = :votes WHERE word = :word AND lang = :lang")
    fun setCase(word: String, lang: String, display: String?, votes: Int)

    /** The most used rows; at an equal count the newest first, so a word learned today is kept. */
    @Query("SELECT * FROM user_words WHERE lang = :lang ORDER BY frequency DESC, updatedAt DESC LIMIT :limit")
    fun topN(lang: String, limit: Int): List<UserWord>

    /** The rows with a learned capital, whatever their count: [topN] alone drops them past its limit. */
    @Query(
        "SELECT * FROM user_words WHERE lang = :lang AND display IS NOT NULL " +
            "ORDER BY frequency DESC, updatedAt DESC LIMIT :limit",
    )
    fun withCapitals(lang: String, limit: Int): List<UserWord>

    /** The rows counted [floor] times or more, which merge into the trie: [topN] alone drops them past its limit. */
    @Query(
        "SELECT * FROM user_words WHERE lang = :lang AND frequency >= :floor " +
            "ORDER BY frequency DESC, updatedAt DESC LIMIT :limit",
    )
    fun mergeable(lang: String, floor: Int, limit: Int): List<UserWord>

    @Query("SELECT * FROM user_words WHERE lang = :lang AND frequency > 0 ORDER BY word")
    fun allForLanguage(lang: String): List<UserWord>

    @Query("DELETE FROM user_words WHERE word = :word AND lang = :lang")
    fun delete(word: String, lang: String)

    /** Reset of the personal dictionary for one language. */
    @Query("DELETE FROM user_words WHERE lang = :lang")
    fun clearLanguage(lang: String)

    @Query("SELECT COUNT(*) FROM user_words WHERE lang = :lang AND frequency > 0")
    fun countForLanguage(lang: String): Int

    @Query("SELECT COUNT(*) FROM user_words")
    fun count(): Int
}
