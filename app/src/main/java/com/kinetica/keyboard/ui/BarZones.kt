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

    /**
     * Compact spacing: a page holds words at their own width with [gap] between them, greedy from
     * the left. The tap targets are no longer equal, so the leading words get a word-sized target
     * rather than a fifth of the bar; [compactLayout] gives each the gap's half on either side.
     */
    fun compactPages(widths: List<Float>, available: Float, gap: Float, maxZones: Int): List<Int> {
        if (widths.isEmpty()) return emptyList()
        val cap = maxZones.coerceAtLeast(1)
        if (available <= 0f) return pages(widths, available, cap)
        val out = ArrayList<Int>()
        var i = 0
        while (i < widths.size) {
            var n = 1
            var used = widths[i]
            while (i + n < widths.size && n < cap) {
                val grown = used + gap + widths[i + n]
                if (grown > available) break
                used = grown
                n++
            }
            out.add(n)
            i += n
        }
        return out
    }

    /** One page's words: n + 1 tap boundaries and n text centres, in the page's own x. */
    class Layout(val bounds: FloatArray, val centers: FloatArray)

    /** Equal zones, the default spacing. */
    fun equalLayout(n: Int, available: Float): Layout {
        val zone = available / n.coerceAtLeast(1)
        return Layout(
            FloatArray(n + 1) { it * zone },
            FloatArray(n) { (it + 0.5f) * zone },
        )
    }

    /**
     * Compact spacing: the words at their own [widths] with [gap] between them, the group
     * centred. A boundary falls midway through each gap and the outer two on the bar's edges, so
     * every touch lands on a word. A single word wider than the bar centres and is ellipsized.
     */
    fun compactLayout(widths: List<Float>, available: Float, gap: Float): Layout {
        val n = widths.size
        if (n <= 1) return equalLayout(1, available)
        val total = widths.sum() + gap * (n - 1)
        var x = ((available - total) / 2f).coerceAtLeast(0f)
        val bounds = FloatArray(n + 1)
        val centers = FloatArray(n)
        for (i in 0 until n) {
            centers[i] = x + widths[i] / 2f
            x += widths[i]
            if (i < n - 1) {
                bounds[i + 1] = x + gap / 2f
                x += gap
            }
        }
        bounds[n] = available
        return Layout(bounds, centers)
    }

    /** The zone [x] falls in, by [layout]'s boundaries. */
    fun zoneOf(layout: Layout, x: Float): Int {
        val n = layout.centers.size
        for (i in 1 until n) if (x < layout.bounds[i]) return i - 1
        return n - 1
    }

    /** Index into the full list of the first word on page [page]. */
    fun startOfPage(pageSizes: List<Int>, page: Int): Int {
        var start = 0
        for (p in 0 until page.coerceIn(0, pageSizes.size)) start += pageSizes[p]
        return start
    }
}
