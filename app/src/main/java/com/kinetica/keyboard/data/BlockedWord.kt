package com.kinetica.keyboard.data

import androidx.room.Entity

/**
 * One word the user never wants offered, for one language.
 *
 * Keyed on (word, lang) like [UserWord], and for the same reason: the bundled
 * lists overlap, so blocking a Korean name syllable that shows up in the English
 * corpus must not also remove a real Italian word with the same spelling.
 *
 * Blocking is not the same as de-reinforcing. Sliding a suggestion down adjusts
 * the personal count and the DAO clamps it at zero, which leaves the corpus
 * frequency underneath untouched - so a bundled word can be pushed off the top
 * of the bar but never out of the dictionary. This is what removes it: the word
 * is dropped while the trie is built, so it cannot be decoded, completed or
 * suggested at all.
 */
@Entity(tableName = "blocked_words", primaryKeys = ["word", "lang"])
data class BlockedWord(
    val word: String,
    val lang: String,
    val addedAt: Long,
)
