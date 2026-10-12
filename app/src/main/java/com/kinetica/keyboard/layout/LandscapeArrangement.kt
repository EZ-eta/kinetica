package com.kinetica.keyboard.layout

/**
 * Where the letter block sits in landscape. SPLIT is sized by its gap slider, CENTERED holds row
 * pitch at [com.kinetica.keyboard.engine.KineticaConstants.LANDSCAPE_ROW_PITCH_KW], and STRETCH
 * is the full-width board earlier versions drew.
 */
enum class LandscapeArrangement {
    SPLIT, CENTERED, STRETCH;

    companion object {
        /**
         * Android's own line for a tablet layout. An unfolded phone held upright is past it and
         * no phone is: a full-width board there stretches keys to twice a thumb's reach.
         */
        const val WIDE_SCREEN_DP = 600

        /**
         * Whether the arrangement applies: always in landscape, and in portrait on a screen
         * [WIDE_SCREEN_DP] wide or more when [wideToo] is on. Rebuilt on every configuration
         * change, so folding and unfolding switch it.
         */
        fun appliesTo(landscape: Boolean, widthDp: Int, wideToo: Boolean): Boolean =
            landscape || (wideToo && widthDp >= WIDE_SCREEN_DP)

        fun fromPref(value: String?): LandscapeArrangement = when (value) {
            "centered" -> CENTERED
            "stretch" -> STRETCH
            else -> SPLIT
        }
    }
}
