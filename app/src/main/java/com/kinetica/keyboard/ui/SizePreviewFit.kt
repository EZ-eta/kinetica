package com.kinetica.keyboard.ui

/**
 * How wide the size picture is drawn. Scaled to the view's width alone, the portrait phone drawn
 * across a landscape settings screen came out taller than the screen.
 */
object SizePreviewFit {

    /** The picture never takes more than this share of the settings screen's height. */
    const val MAX_SCREEN_SHARE = 0.5f

    /**
     * The view's width, narrowed until the picture is no taller than [maxH]. [contentH] is the
     * keyboard's height in the phone's pixels on a screen [screenW] wide; [bandPx] the app strip
     * above it, drawn at a fixed size.
     */
    fun widthPx(viewW: Float, screenW: Float, contentH: Float, bandPx: Float, maxH: Float): Float {
        if (viewW <= 0f || screenW <= 0f || contentH <= 0f) return viewW.coerceAtLeast(0f)
        if (contentH * viewW / screenW + bandPx <= maxH) return viewW
        return ((maxH - bandPx).coerceAtLeast(0f) * screenW / contentH).coerceAtMost(viewW)
    }

    /** The picture's height at [drawW], band included. */
    fun heightPx(drawW: Float, screenW: Float, contentH: Float, bandPx: Float): Float =
        if (screenW <= 0f) bandPx else contentH * drawW / screenW + bandPx
}
