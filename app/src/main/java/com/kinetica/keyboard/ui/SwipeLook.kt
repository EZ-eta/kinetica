package com.kinetica.keyboard.ui

/**
 * How a swipe shows on the board: the trail over the keys, the keys it crosses lit, or both.
 * Zen mode and private fields draw neither, whatever this says.
 */
enum class SwipeLook(val value: String, val drawsTrail: Boolean, val drawsKeys: Boolean) {
    TRAIL("trail", drawsTrail = true, drawsKeys = false),
    KEYS("keys", drawsTrail = false, drawsKeys = true),
    BOTH("both", drawsTrail = true, drawsKeys = true),
    ;

    companion object {
        /** An unknown value is the trail, as before the choice existed. */
        fun fromPref(value: String?): SwipeLook = entries.firstOrNull { it.value == value } ?: TRAIL
    }
}
