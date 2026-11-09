package com.kinetica.keyboard.ui

/**
 * How long a trail point lasts. It fades over [LIFE_MS] from when it was laid; kept while its word
 * is in progress, it stays whole and fades from the moment the word ends.
 */
object TrailLife {
    const val LIFE_MS = 250L

    /** From 1, newly laid or kept, down to 0, gone: the scale of the point's alpha and width. */
    fun strength(kept: Boolean, t: Long, now: Long): Float =
        if (kept) 1f else 1f - (now - t).coerceAtLeast(0) / LIFE_MS.toFloat()

    fun expired(kept: Boolean, t: Long, now: Long): Boolean = !kept && now - t > LIFE_MS
}
