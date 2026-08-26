package com.kinetica.keyboard.data

import androidx.room.Entity

/**
 * How often one emoji has been picked, so the picker can offer them back.
 *
 * Keyed on the whole emoji string, not on a codepoint: the bundled asset carries
 * ZWJ sequences and variation selectors (an aeroplane is U+2708 U+FE0F), and a
 * single Int cannot round-trip those.
 *
 * No language column, unlike [UserWord] and [BlockedWord]. Their (word, lang)
 * key exists to stop one language's list contaminating another's decode; emoji
 * belong to no language and are offered from one store whatever is being typed.
 */
@Entity(tableName = "emoji_uses", primaryKeys = ["emoji"])
data class EmojiUse(
    val emoji: String,
    val count: Int,
    val updatedAt: Long,
)
