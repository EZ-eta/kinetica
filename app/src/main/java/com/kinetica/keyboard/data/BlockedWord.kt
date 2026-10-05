package com.kinetica.keyboard.data

import androidx.room.Entity

/**
 * One word the user never wants offered, for one language.
 *
 * Keyed on (word, lang) like [UserWord]: the bundled lists overlap, so blocking a stray
 * spelling in English must not remove a real Italian word spelled the same.
 *
 * Sliding a suggestion down only lowers the personal count, clamped at zero, and leaves the
 * corpus frequency in place. A blocked word is dropped while the trie is built, so it cannot
 * be decoded, completed or suggested at all.
 */
@Entity(tableName = "blocked_words", primaryKeys = ["word", "lang"])
data class BlockedWord(
    val word: String,
    val lang: String,
    val addedAt: Long,
)
