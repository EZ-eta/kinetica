package com.kinetica.keyboard.layout

import android.graphics.RectF

/**
 * The five layout modes are pure transforms of the same normalized layout;
 * key definitions never change, only their pixel projection. Hit testing and
 * engine geometry both consume the transformed rects, so every mode works
 * without special cases downstream.
 */
object LayoutTransforms {

    private const val SIDE_SCALE = 0.85f
    private const val ONE_HANDED_SCALE = 0.85f
    private const val SPLIT_HALF = 0.42f
    private const val SPLIT_RIGHT_START = 0.58f

    fun apply(mode: LayoutMode, key: Key, viewW: Float, viewH: Float): RectF {
        val x0 = key.x
        val x1 = key.x + key.w
        val y0 = key.y
        val y1 = key.y + key.h
        return when (mode) {
            LayoutMode.FULL -> RectF(x0 * viewW, y0 * viewH, x1 * viewW, y1 * viewH)
            LayoutMode.RIGHT_ALIGNED -> RectF(
                (1f - SIDE_SCALE + x0 * SIDE_SCALE) * viewW, y0 * viewH,
                (1f - SIDE_SCALE + x1 * SIDE_SCALE) * viewW, y1 * viewH,
            )
            LayoutMode.LEFT_ALIGNED -> RectF(
                x0 * SIDE_SCALE * viewW, y0 * viewH,
                x1 * SIDE_SCALE * viewW, y1 * viewH,
            )
            LayoutMode.SPLIT -> RectF(
                splitX(x0, key) * viewW, y0 * viewH,
                splitX(x1, key) * viewW, y1 * viewH,
            )
            LayoutMode.ONE_HANDED -> RectF(
                (1f - ONE_HANDED_SCALE + x0 * ONE_HANDED_SCALE) * viewW,
                (1f - ONE_HANDED_SCALE + y0 * ONE_HANDED_SCALE) * viewH,
                (1f - ONE_HANDED_SCALE + x1 * ONE_HANDED_SCALE) * viewW,
                (1f - ONE_HANDED_SCALE + y1 * ONE_HANDED_SCALE) * viewH,
            )
        }
    }

    /**
     * Split: left-half keys compress into [0, 0.42], right-half keys into
     * [0.58, 1], leaving a 16% gap. The spacebar bridges the gap unchanged.
     */
    private fun splitX(x: Float, key: Key): Float {
        if (key.type == KeyType.SPACE) return x
        val centerX = key.x + key.w / 2f
        return if (centerX < 0.5f) {
            x * (SPLIT_HALF / 0.5f)
        } else {
            SPLIT_RIGHT_START + (x - 0.5f) * ((1f - SPLIT_RIGHT_START) / 0.5f)
        }
    }
}
