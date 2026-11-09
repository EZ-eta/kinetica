package com.kinetica.keyboard.ui

/**
 * How a word too wide for its place in the suggestion bar is shortened: its end cut, or its
 * middle, keeping the start and the end. In some languages several candidates share a long start
 * and differ only at the end (`organizzare`, `organizzazione`), which an end cut hides.
 *
 * Pure, so the rule is testable without a view, like [BarZones]; widths come from [measure].
 */
object BarFit {

    const val ELLIPSIS = "…"

    /** Letters an end cut keeps however narrow the place. */
    const val END_MIN = 3

    /** Letters a middle cut keeps on each side however narrow the place. */
    const val SIDE_MIN = 2

    /** [word] as it fits in [maxW]: whole when it fits, otherwise cut at its end or its middle. */
    fun fit(word: String, maxW: Float, middle: Boolean, measure: (String) -> Float): String {
        if (measure(word) <= maxW) return word
        return if (middle) middleCut(word, maxW, measure) else endCut(word, maxW, measure)
    }

    private fun endCut(word: String, maxW: Float, measure: (String) -> Float): String {
        var s = word
        while (s.length > END_MIN && measure("$s$ELLIPSIS") > maxW) {
            s = s.dropLast(1)
        }
        return "$s$ELLIPSIS"
    }

    /**
     * The most letters that fit, the start taking the odd one. Counted in code points, so a
     * letter outside the BMP is never split.
     */
    private fun middleCut(word: String, maxW: Float, measure: (String) -> Float): String {
        val points = word.codePoints().toArray()
        val n = points.size
        var keep = n - 1
        while (true) {
            val head = (keep + 1) / 2
            val tail = keep / 2
            val s = String(points, 0, head) + ELLIPSIS + String(points, n - tail, tail)
            if (keep <= 2 * SIDE_MIN || measure(s) <= maxW) return s
            keep--
        }
    }
}
