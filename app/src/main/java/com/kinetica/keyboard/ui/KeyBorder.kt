package com.kinetica.keyboard.ui

/**
 * The line drawn round each key, in px. Capped at a share of the narrowest key's shorter side, so
 * the widest setting on the 24 dp landscape keys still leaves the label its room.
 *
 * No Android imports, so the rule is testable without a device.
 */
object KeyBorder {

    const val MIN_DP = 1
    const val MAX_DP = 4

    /**
     * 4 dp is 14% of a 29 dp portrait key inside its gaps; on the 19 dp narrowest landscape key the
     * line stops near 3 dp.
     */
    const val MAX_SHARE = 0.15f

    /** The width for [widthDp] (0 = off); [narrowestSidePx] 0 means no layout yet, so no cap. */
    fun widthPx(widthDp: Int, density: Float, narrowestSidePx: Float): Float {
        if (widthDp <= 0) return 0f
        val px = widthDp.coerceIn(MIN_DP, MAX_DP) * density
        return if (narrowestSidePx > 0f) minOf(px, narrowestSidePx * MAX_SHARE) else px
    }

    /** How far the stroked rect sits inside the key, so the line stays within it. */
    fun inset(widthPx: Float): Float = widthPx / 2f
}
