package com.kinetica.keyboard.ui

import kotlin.math.abs

/**
 * Which page a drag across the suggestion bar lands on.
 *
 * A page swipe starts anywhere on the bar and goes either way, so it shares its start
 * with the tap and the upward flick that commit a word and the long press that reweights one.
 * Horizontal dominance tells it apart: the flick and the slide are vertical, and a wandering
 * tap drifts along the finger's axis more than across it.
 *
 * Pure, so the rule is testable without a view, like [BarAdjust].
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
        // Leftward travel is forward, as the page dots read left to right.
        val step = if (dx < 0f) 1 else -1
        return Math.floorMod(page + step, pageCount)
    }
}
