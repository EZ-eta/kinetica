package com.kinetica.keyboard.data

import androidx.room.Entity

/**
 * One learned word for one language. Keyed on (word, lang): the same spelling
 * learned in two languages carries two independent counts, so English commits
 * can never inflate or evict Italian personal weights (and vice versa).
 */
@Entity(tableName = "user_words", primaryKeys = ["word", "lang"])
data class UserWord(
    val word: String,
    val lang: String,
    val frequency: Int,
    val updatedAt: Long,
)
