package com.kinetica.keyboard.data

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * One learned word for one language. Keyed on (word, lang): the same spelling
 * learned in two languages carries two independent counts, so English commits
 * can never inflate or evict Italian personal weights (and vice versa).
 *
 * [word] is always lowercase, the key every count, block and pair is read by. [display] is the
 * capital the user writes it with, `Dennis` or `GitHub`, and [caseVotes] how settled that is:
 * one up for each mid-sentence commit with the capital, one down for each without it. Both
 * added in v7; an older row has no capital and no votes.
 */
@Entity(tableName = "user_words", primaryKeys = ["word", "lang"])
data class UserWord(
    val word: String,
    val lang: String,
    val frequency: Int,
    val updatedAt: Long,
    val display: String? = null,
    @ColumnInfo(defaultValue = "0") val caseVotes: Int = 0,
)
