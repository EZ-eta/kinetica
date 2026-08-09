package com.kinetica.keyboard.layout

enum class LayoutMode {
    FULL, RIGHT_ALIGNED, LEFT_ALIGNED, SPLIT, ONE_HANDED;

    companion object {
        fun fromPref(value: String?): LayoutMode = when (value) {
            "right" -> RIGHT_ALIGNED
            "left" -> LEFT_ALIGNED
            "split" -> SPLIT
            "one_handed" -> ONE_HANDED
            else -> FULL
        }
    }
}
