package com.kinetica.keyboard.settings

/**
 * The key a shortcut goes on, typed instead of picked from a list, so any key of any board works:
 * `й`, `1` or `,` alike. Spaces are dropped, since a typed space is never the key meant; more than
 * one character is refused, not guessed at.
 */
object ShortcutKeyInput {

    sealed class Result {
        object Blank : Result()

        /** A letter is its lowercase: a shifted board types the same chord. */
        data class One(val key: Char) : Result()

        data class TooMany(val typed: String) : Result()
    }

    fun parse(raw: CharSequence?): Result {
        val s = raw?.filterNot { it.isWhitespace() }?.toString().orEmpty()
        return when (s.length) {
            0 -> Result.Blank
            1 -> Result.One(s[0].lowercaseChar())
            else -> Result.TooMany(s)
        }
    }
}
