package com.kinetica.keyboard.ui

import kotlin.math.abs

/**
 * Which page a drag across the suggestion bar lands on.
 *
 * The gesture used to need a start within 36dp of the words' right edge and to travel
 * right to left only, so it reached one page forward from one corner. Three independent
 * reporters asked for the whole bar and both directions
 * (R54), which means it now shares its start zone with the tap that commits a word, the
 * upward flick that commits one, and the long press that reweights one.
 *
 * Horizontal dominance is what separates it from those: the flick and the slide are
 * vertical by construction, and a tap that wanders is far more likely to wander along the
 * finger's own axis than across it. The travel threshold alone used to do this job only
 * because the start zone was 36dp wide.
 *
 * Pure, so the rule is testable without a view or a device, exactly as [BarAdjust] is.
 */
object BarPaging {

    /**
     * The page [page] becomes after a drag of [dx] by [dy] pixels, or -1 when the movement
     * is not a page swipe. Wraps in both directions, so the last page's forward swipe
     * reaches the first.
     */
    fun pageFor(page: Int, pageCount: Int, dx: Float, dy: Float, travelPx: Float): Int {
        if (pageCount <= 1) return -1
        if (abs(dx) < travelPx) return -1
        if (abs(dx) <= abs(dy)) return -1
        // Leftward travel is forward, which is the direction the gesture always had and
        // the one the page dots read left to right.
        val step = if (dx < 0f) 1 else -1
        return Math.floorMod(page + step, pageCount)
    }
}
