package com.kinetica.keyboard.data

import androidx.room.Dao
import androidx.room.Query

@Dao
interface BlockedWordDao {
    // INSERT OR IGNORE rather than an upsert, for the same reason UserWordDao
    // avoids one: ON CONFLICT DO UPDATE needs SQLite 3.24+ and API 26 devices
    // ship 3.18. Blocking an already-blocked word is a no-op anyway.
    @Query("INSERT OR IGNORE INTO blocked_words (word, lang, addedAt) VALUES (:word, :lang, :now)")
    fun block(word: String, lang: String, now: Long)

    @Query("DELETE FROM blocked_words WHERE word = :word AND lang = :lang")
    fun unblock(word: String, lang: String)

    @Query("SELECT * FROM blocked_words WHERE lang = :lang ORDER BY word")
    fun allForLanguage(lang: String): List<BlockedWord>

    /** Just the spellings, which is all the dictionary load needs. */
    @Query("SELECT word FROM blocked_words WHERE lang = :lang")
    fun wordsForLanguage(lang: String): List<String>

    @Query("SELECT COUNT(*) FROM blocked_words WHERE lang = :lang")
    fun countForLanguage(lang: String): Int
}
