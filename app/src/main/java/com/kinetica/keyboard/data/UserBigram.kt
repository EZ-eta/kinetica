package com.kinetica.keyboard.data

import androidx.room.Entity

/**
 * One learned word pair for one language: how often [next] followed [prev].
 *
 * Beside [UserWord] rather than inside it, and keyed the same way it is - per language, so
 * an English pair can never boost an Italian continuation. Both words are stored lowercased
 * and folded exactly as the composer's context is, because that is what the decoder looks
 * the previous word up with.
 *
 * Written only when the phrase setting is on. What it holds is more revealing than the
 * single-word counts beside it - a pair is a fragment of a sentence - which is why it is
 * opt-in and why it is not part of the personal-dictionary export.
 */
@Entity(tableName = "user_bigrams", primaryKeys = ["prev", "next", "lang"])
data class UserBigram(
    val prev: String,
    val next: String,
    val lang: String,
    val count: Int,
    val updatedAt: Long,
)
