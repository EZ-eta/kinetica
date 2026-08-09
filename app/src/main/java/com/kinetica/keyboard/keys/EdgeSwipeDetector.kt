package com.kinetica.keyboard.keys

import com.kinetica.keyboard.layout.Key
import kotlin.math.abs

/**
 * Directional shortcut swipes resolved against the user-configurable
 * [EdgeSwipeBindings] (defaults: backspace up = "!", enter up = "?", V down =
 * ",", B down = ".", X down = emoji). Decided at pointer lift: travel must
 * exceed 30dp with a clearly dominant axis so real letter swipes are not
 * stolen.
 */
object EdgeSwipeDetector {

    private const val MIN_TRAVEL_DP = 30f
    private const val DOMINANCE = 1.5f

    /** Returns the bound output ("emoji" is a reserved value), or null. */
    fun detect(
        key: Key,
        dxPx: Float,
        dyPx: Float,
        density: Float,
        bindings: EdgeSwipeBindings,
    ): String? {
        val minTravel = MIN_TRAVEL_DP * density
        val direction = when {
            -dyPx >= minTravel && -dyPx > DOMINANCE * abs(dxPx) -> EdgeSwipeBinding.Direction.UP
            dyPx >= minTravel && dyPx > DOMINANCE * abs(dxPx) -> EdgeSwipeBinding.Direction.DOWN
            -dxPx >= minTravel && -dxPx > DOMINANCE * abs(dyPx) -> EdgeSwipeBinding.Direction.LEFT
            dxPx >= minTravel && dxPx > DOMINANCE * abs(dyPx) -> EdgeSwipeBinding.Direction.RIGHT
            else -> return null
        }
        return bindings.outputFor(key.id, direction)
    }
}
