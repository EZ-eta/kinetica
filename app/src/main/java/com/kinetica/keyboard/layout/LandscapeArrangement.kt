package com.kinetica.keyboard.layout

/**
 * Where the letter block sits in landscape. SPLIT is sized by its gap slider, CENTERED holds row
 * pitch at [com.kinetica.keyboard.engine.KineticaConstants.LANDSCAPE_ROW_PITCH_KW], and STRETCH
 * is the full-width board earlier versions drew.
 */
enum class LandscapeArrangement {
    SPLIT, CENTERED, STRETCH;

    companion object {
        fun fromPref(value: String?): LandscapeArrangement = when (value) {
            "centered" -> CENTERED
            "stretch" -> STRETCH
            else -> SPLIT
        }
    }
}
