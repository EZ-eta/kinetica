package com.kinetica.keyboard.ui

/**
 * How many candidate words share one page of the suggestion bar.
 *
 * The bar used to cut the strip into five equal zones whatever the words were, so
 * anything long was ellipsized: four candidates differing only in their endings showed
 * as four identical stems, which is the one thing a suggestion list must never do. A
 * page holds as many words as fit at their own measured width instead, and the rest go
 * to the next page, which the bar already pages between.
 *
 * Zones stay equal WITHIN a page, because the tap target should not shrink with the
 * word and because an uneven row is harder to aim at. So a page of n words needs
 * `available / n` to clear the widest word on it, and the packing is greedy from the
 * left: the leading candidates are the ones that matter, so they are the ones that get
 * the room.
 *
 * A page of one is not a policy, it is what the geometry says when the next word cannot
 * share. A word wider than the whole bar still gets a page and is ellipsized there,
 * which is the only case left where that happens.
 *
 * Pure, so the partition is testable without a view. [widths] already include whatever
 * padding and badge allowance each word needs; this only divides.
 */
object BarZones {

    /**
     * The size of each page, in order, summing to `widths.size`.
     *
     * [available] of zero or less means the view has not been laid out yet. There is no
     * measurement to pack against then, so it falls back to the fixed [maxZones] the bar
     * used before, rather than emitting one page per word.
     */
    fun pages(widths: List<Float>, available: Float, maxZones: Int): List<Int> {
        if (widths.isEmpty()) return emptyList()
        val cap = maxZones.coerceAtLeast(1)
        if (available <= 0f) {
            return List((widths.size + cap - 1) / cap) { p ->
                minOf(cap, widths.size - p * cap)
            }
        }
        val out = ArrayList<Int>()
        var i = 0
        while (i < widths.size) {
            var n = 1
            var widest = widths[i]
            while (i + n < widths.size && n < cap) {
                val grown = maxOf(widest, widths[i + n])
                if (grown * (n + 1) > available) break
                widest = grown
                n++
            }
            out.add(n)
            i += n
        }
        return out
    }

    /** Index into the full list of the first word on page [page]. */
    fun startOfPage(pageSizes: List<Int>, page: Int): Int {
        var start = 0
        for (p in 0 until page.coerceIn(0, pageSizes.size)) start += pageSizes[p]
        return start
    }
}
