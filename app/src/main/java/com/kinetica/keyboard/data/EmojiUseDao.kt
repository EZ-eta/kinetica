package com.kinetica.keyboard.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface EmojiUseDao {
    // Two statements instead of INSERT..ON CONFLICT DO UPDATE, as in
    // UserWordDao: that upsert syntax needs SQLite 3.24+ and API 26 ships 3.18.
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
