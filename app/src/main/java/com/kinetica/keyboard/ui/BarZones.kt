package com.kinetica.keyboard.ui

/**
 * How many candidate words share one page of the suggestion bar.
 *
 * A page holds as many words as fit at their measured width and the rest go to the next page;
 * fixed fifths ellipsized long words until candidates that differ only in their endings looked
 * the same. Zones stay equal within a page so a tap target never shrinks with its word, and the
 * packing is greedy from the left, so the leading candidates get the room. A word wider than the
 * bar gets a page of its own and is ellipsized there.
 *
 * Pure, so the partition is testable without a view. [widths] already include padding and badges.
 */
object BarZones {

    /**
     * The size of each page, in order, summing to `widths.size`.
     *
     * [available] of zero or less means the view is not laid out yet, so pages hold a fixed
     * [maxZones] words instead of one page per word.
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
