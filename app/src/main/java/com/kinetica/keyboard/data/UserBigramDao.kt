package com.kinetica.keyboard.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface UserBigramDao {
    // Two statements rather than an upsert, for the reason UserWordDao gives: ON CONFLICT
    // DO UPDATE needs SQLite 3.24+ and API 26 devices ship 3.18.
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
