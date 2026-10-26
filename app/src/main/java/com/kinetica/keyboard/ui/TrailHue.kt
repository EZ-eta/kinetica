package com.kinetica.keyboard.ui

import com.kinetica.keyboard.engine.models.StreamId

/**
 * The hue a swipe trail is drawn in. Rainbow starts the right thumb 60 deg on and steps 30 deg
 * per key; any other choice keeps its own hue for both thumbs, or the setting reads as rainbow.
 *
 * No Android imports, so the rule is testable without a device.
 */
object TrailHue {

    const val RIGHT_OFFSET = 60f
    const val STEP = 30f

    /** The hue a stream starts at, over [base] in degrees. */
    fun start(base: Float, stream: StreamId, cycles: Boolean): Float =
        (base + if (cycles && stream == StreamId.RIGHT) RIGHT_OFFSET else 0f) % 360f

    /** The hue after the trail enters a new key. */
    fun next(hue: Float, cycles: Boolean): Float = if (cycles) (hue + STEP) % 360f else hue
}
