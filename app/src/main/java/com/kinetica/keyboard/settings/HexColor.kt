package com.kinetica.keyboard.settings

/**
 * A colour typed as `#RRGGBB`. Spaces are dropped and the `#` is optional, since both are easy to
 * miss on a phone; anything else is refused, not guessed at.
 *
 * Hand-rolled: `android.graphics.Color.parseColor` has no JVM implementation, so a palette built
 * on it could not be tested.
 */
object HexColor {

    sealed class Result {
        object Blank : Result()

        /** Opaque ARGB. */
        data class Valid(val color: Int) : Result()

        data class Invalid(val typed: String) : Result()
    }

    fun parse(raw: CharSequence?): Result {
        val s = raw?.filterNot { it.isWhitespace() }?.toString().orEmpty()
        if (s.isEmpty()) return Result.Blank
        val digits = s.removePrefix("#")
        if (digits.length != 6 || digits.any { Character.digit(it, 16) < 0 }) return Result.Invalid(s)
        return Result.Valid(digits.toInt(16) or OPAQUE)
    }

    /** The colour of [raw], or [fallback] when it is not one. */
    fun colorOr(raw: String?, fallback: Int): Int = (parse(raw) as? Result.Valid)?.color ?: fallback

    /** `#RRGGBB`, upper case; alpha dropped. */
    fun format(color: Int): String = "#%06X".format(color and 0xFFFFFF)

    private const val OPAQUE = 0xFF shl 24
}
