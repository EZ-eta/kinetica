package com.kinetica.keyboard.ui

/**
 * Per stream, whether a touch sample lays a new trail point or moves the last one.
 *
 * One point per [minIntervalMs] bounds draw work on high-refresh screens. Timed from when the
 * last point was laid, not from its latest sample: dense touch history kept moving one point
 * forward, and a one-colour trail, with no hue change to force a point, had no length.
 *
 * No Android imports, so the rule is testable without a device.
 */
class TrailSampling(private val minIntervalMs: Long) {

    private val laidAt = LongArray(2)

    /**
     * True when the sample at [t] on [stream] lays a point. A break ([forced]), a hue change or
     * an empty trail ([hasLast] false) always lays one.
     */
    fun lays(stream: Int, t: Long, forced: Boolean, sameHue: Boolean, hasLast: Boolean): Boolean {
        val since = t - laidAt[stream]
        if (hasLast && !forced && sameHue && since >= 0 && since < minIntervalMs) return false
        laidAt[stream] = t
        return true
    }
}
