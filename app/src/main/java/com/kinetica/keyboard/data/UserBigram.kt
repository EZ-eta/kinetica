package com.kinetica.keyboard.data

import androidx.room.Entity

/**
 * One learned word pair for one language: how often [next] followed [prev].
 *
 * Keyed per language like [UserWord], so an English pair never boosts an Italian
 * continuation. Both words are lowercased and folded as the composer's context is, since the
 * decoder looks the previous word up that way.
 *
 * Written only when the phrase setting is on: a pair is a fragment of a sentence, more
 * revealing than a single-word count, so it is opt-in and left out of the personal-dictionary
 * export.
 */
@Entity(tableName = "user_bigrams", primaryKeys = ["prev", "next", "lang"])
data class UserBigram(
    val prev: String,
    val next: String,
    val lang: String,
    val count: Int,
    val updatedAt: Long,
)
