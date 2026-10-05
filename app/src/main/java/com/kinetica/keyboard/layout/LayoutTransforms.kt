package com.kinetica.keyboard.layout

import android.graphics.RectF
import com.kinetica.keyboard.engine.KineticaConstants

/**
 * The five layout modes are pure transforms of one normalized layout: only the pixel projection
 * changes. Hit testing and engine geometry both read the transformed rects, so no mode needs a
 * special case downstream.
 */
object LayoutTransforms {

    private const val SIDE_SCALE = 0.85f
    private const val ONE_HANDED_SCALE = 0.85f
    private const val SPLIT_HALF = 0.42f
    private const val SPLIT_RIGHT_START = 0.58f

    /** Widest side inset the setting offers, per side. */
    const val MAX_SIDE_PAD_DP = 48

    /** Tallest bottom gap the setting offers. */
    const val MAX_BOTTOM_PAD_DP = 48

    /** Ceiling on the side inset as a fraction of the view, per side: a narrow phone keeps its keys. */
    private const val MAX_SIDE_PAD_FRACTION = 0.15f

    /** [dp] of side inset in pixels, bounded by both the dp cap and the view. */
    fun sidePadPx(dp: Int, density: Float, viewW: Float): Float =
        (dp.coerceIn(0, MAX_SIDE_PAD_DP) * density)
            .coerceAtMost(viewW * MAX_SIDE_PAD_FRACTION)
            .coerceAtLeast(0f)

    /**
     * Uniform scale the key block takes so a side inset preserves its aspect ratio.
     *
     * Shrinking width alone would move row pitch in kw, which was measured to make a
     * board less forgiving. Scaling both axes divides every rect and touch sample by the same
     * smaller `keyWidthPx`, so every kw distance matches the unpadded board and the freed
     * height becomes bottom gap.
     */
    fun blockScale(sidePadPx: Float, viewW: Float): Float {
        if (viewW <= 0f) return 1f
        return ((viewW - 2f * sidePadPx) / viewW).coerceIn(0.5f, 1f)
    }

    /**
     * The inset applied to one x coordinate, in pixels. Exposed as floats because RectF is
     * stubbed in JVM unit tests, like org.json, and the padding arithmetic needs a test.
     */
    fun padX(x: Float, sidePadPx: Float, viewW: Float): Float =
        if (sidePadPx <= 0f) x else sidePadPx + x * blockScale(sidePadPx, viewW)

    /**
     * The inset applied to one y coordinate. The block is anchored to the top, so the height
     * the scale frees appears below the keyboard.
     */
    fun padY(y: Float, sidePadPx: Float, viewW: Float): Float =
        if (sidePadPx <= 0f) y else y * blockScale(sidePadPx, viewW)

    /**
     * Narrowest a landscape letter key may be, in dp: twice the drift a tap may carry, the same
     * floor [com.kinetica.keyboard.settings.KeyboardHeights.MIN_KEYBOARD_DP] puts on a row.
     */
    const val LANDSCAPE_MIN_KEY_DP = 2f * KineticaConstants.TAP_MAX_DISP_DP

    /** Layout x at or left of which a key belongs to the left half of a split board. */
    private const val MIDLINE_X = 0.5f + 1e-4f

    /**
     * Width in pixels of the landscape letter block, for a view of [viewW] x [viewH] whose
     * letter key is [keyW] x [keyH] in layout units.
     *
     * The centred arrangement's block: as wide as holds pitch at
     * [KineticaConstants.LANDSCAPE_ROW_PITCH_KW], widened only to keep a key at [minKeyPx], and
     * never wider than the view less its insets. Its keys are small, 29 dp on a Pixel 7, and
     * small keys were measured as the cost; the split is sized by [splitBlockPx].
     */
    fun landscapeBlockPx(
        viewW: Float,
        viewH: Float,
        sidePadPx: Float,
        keyW: Float,
        keyH: Float,
        minKeyPx: Float,
    ): Float {
        val room = (viewW - 2f * sidePadPx).coerceAtLeast(0f)
        if (keyW <= 0f || keyH <= 0f) return room
        val locked = keyH * viewH / (KineticaConstants.LANDSCAPE_ROW_PITCH_KW * keyW)
        return maxOf(locked, minKeyPx / keyW).coerceAtMost(room)
    }

    /**
     * Width in pixels of the split block: the view less its insets and a gap of [gapFraction]
     * of the view between the halves, never so wide the halves overlap and never so narrow a
     * letter key of [keyW] layout units falls under [minKeyPx]. The gap sets key size, the only
     * landscape factor that was measured to move decoding.
     */
    fun splitBlockPx(
        viewW: Float,
        sidePadPx: Float,
        gapFraction: Float,
        keyW: Float,
        minKeyPx: Float,
    ): Float {
        val room = (viewW - 2f * sidePadPx).coerceAtLeast(0f)
        val floor = if (keyW > 0f) minKeyPx / keyW else 0f
        return (room - viewW * gapFraction.coerceAtLeast(0f)).coerceAtLeast(floor).coerceAtMost(room)
    }

    /**
     * One layout x projected into a landscape block of [blockPx]. SPLIT tears the block at its
     * midline and moves the right half to the right edge, so every distance inside a half is the
     * centred block's; [leftHalf] says which side [x] belongs to.
     */
    fun landscapeX(
        x: Float,
        leftHalf: Boolean,
        arrangement: LandscapeArrangement,
        viewW: Float,
        blockPx: Float,
        sidePadPx: Float,
    ): Float = when (arrangement) {
        LandscapeArrangement.CENTERED -> (viewW - blockPx) / 2f + x * blockPx
        LandscapeArrangement.SPLIT ->
            if (leftHalf) sidePadPx + x * blockPx else viewW - sidePadPx - blockPx + x * blockPx
        LandscapeArrangement.STRETCH -> x * viewW
    }

    /** Whether [key] belongs to the left half of a split board, judged by its centre. */
    fun inLeftHalf(key: Key): Boolean = key.x + key.w / 2f <= MIDLINE_X

    /**
     * Pixel rect for [key] in a landscape block. The spacebar bridges a split: its left edge
     * stays with the left half and its right edge with the right one.
     */
    fun applyLandscape(
        key: Key,
        viewW: Float,
        viewH: Float,
        sidePadPx: Float,
        arrangement: LandscapeArrangement,
        blockPx: Float,
    ): RectF {
        val left = inLeftHalf(key)
        val space = key.type == KeyType.SPACE
        return RectF(
            landscapeX(key.x, left || space, arrangement, viewW, blockPx, sidePadPx),
            key.y * viewH,
            landscapeX(key.x + key.w, left && !space, arrangement, viewW, blockPx, sidePadPx),
            (key.y + key.h) * viewH,
        )
    }

    /** Pixel rect for [key], inset by [sidePadPx] on each side. */
    fun apply(
        mode: LayoutMode,
        key: Key,
        viewW: Float,
        viewH: Float,
        sidePadPx: Float,
    ): RectF {
        val r = apply(mode, key, viewW, viewH)
        if (sidePadPx <= 0f) return r
        return RectF(
            padX(r.left, sidePadPx, viewW), padY(r.top, sidePadPx, viewW),
            padX(r.right, sidePadPx, viewW), padY(r.bottom, sidePadPx, viewW),
        )
    }

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
