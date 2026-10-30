package com.kinetica.keyboard.ui

/**
 * The keys a swipe has crossed, lit and fading behind the thumb: the swipe drawn on the board
 * itself instead of as a line over it.
 *
 * Per key, when it was last crossed and in which hue; a key crossed again starts over. Arrays
 * are sized once per layout, so marking and drawing allocate nothing. No Android imports, so
 * the fade is testable without a device.
 */
class KeyWake {

    private var markedAt = LongArray(0)
    private var hues = FloatArray(0)
    private var lastMark = NEVER

    val size: Int
        get() = markedAt.size

    /** One slot per key of a new layout; whatever was lit goes. */
    fun resize(keys: Int) {
        if (keys != markedAt.size) {
            markedAt = LongArray(keys)
            hues = FloatArray(keys)
        }
        clear()
    }

    fun clear() {
        markedAt.fill(NEVER)
        lastMark = NEVER
    }

    /** [key] was crossed at [now] by a thumb drawing in [hue]. */
    fun mark(key: Int, hue: Float, now: Long) {
        if (key !in markedAt.indices) return
        markedAt[key] = now
        hues[key] = hue
        lastMark = now
    }

    /** 1 at the crossing, falling to 0 over [FADE_MS]. */
    fun lightAt(key: Int, now: Long): Float {
        if (key !in markedAt.indices || markedAt[key] == NEVER) return 0f
        val age = (now - markedAt[key]).coerceAtLeast(0L)
        if (age >= FADE_MS) return 0f
        return 1f - age / FADE_MS.toFloat()
    }

    fun hueOf(key: Int): Float = if (key in hues.indices) hues[key] else 0f

    /** True while any key is still lit. */
    fun anyLit(now: Long): Boolean = lastMark != NEVER && now - lastMark < FADE_MS

    companion object {
        /**
         * Longer than the trail's 250 ms: a key lights once per crossing, not per sample, so a
         * shorter fade leaves little more than the key under the thumb. Set by eye, a look.
         */
        const val FADE_MS = 400L

        /** The edge's width when lit and when nearly gone, in dp. */
        const val WIDTH_LIT_DP = 4f
        const val WIDTH_FADED_DP = 1.5f

        /** The edge narrows as it fades, so the newest keys read as the front of the swipe. */
        fun widthDp(light: Float): Float =
            WIDTH_FADED_DP + (WIDTH_LIT_DP - WIDTH_FADED_DP) * light.coerceIn(0f, 1f)

        private const val NEVER = Long.MIN_VALUE / 2
    }
}
