package com.kinetica.keyboard.ui

/**
 * The skin tone set for emoji that take one: 0 none (the yellow default), 1-5 light to dark, the
 * Fitzpatrick modifiers U+1F3FB-U+1F3FF. The catalog carries each such emoji's light form, so a tone
 * is one modifier swapped for another, and only where Unicode lists every tone.
 */
object EmojiTone {

    const val NONE = 0
    const val DARKEST = 5

    private const val LIGHT = 0x1F3FB

    /** The stored names, in tone order, so a value reads as what it is in a backup. */
    val NAMES = listOf("none", "light", "medium_light", "medium", "medium_dark", "dark")

    /** The tone a stored [name] is; anything unknown is [NONE]. */
    fun of(name: String?): Int = NAMES.indexOf(name).coerceAtLeast(NONE)

    /** What [entry] shows and types in [tone]: its toned form, or itself when it takes none. */
    fun apply(entry: EmojiCatalog.Entry, tone: Int): String {
        val light = entry.toned ?: return entry.ch
        if (tone !in 1..DARKEST) return entry.ch
        return light.replace(String(Character.toChars(LIGHT)), String(Character.toChars(LIGHT + tone - 1)))
    }
}
