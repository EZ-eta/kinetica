package com.kinetica.keyboard.ui

/**
 * The curve arithmetic behind a swipe trail: one quadratic per sample, laid out so
 * consecutive pieces meet exactly.
 *
 * A trail used to be one straight line per sample pair, which reads as facets on a fast
 * swipe. Each piece now runs from the midpoint of the previous pair, bends through the
 * sample itself, and ends at the midpoint of the next pair, so the sample is a control
 * point rather than a corner. One piece per sample is what keeps the per-sample colour and
 * width taper that a single path with one paint would lose.
 *
 * No Android imports, so the joins are testable without a device.
 */
object TrailPath {

    /** Floats one quadratic needs: start, control, end. */
    const val SIZE = 6

    /**
     * Writes the piece centred on (`curX`, `curY`) into [out] as
     * `[startX, startY, ctrlX, ctrlY, endX, endY]`.
     *
     * A run's first piece starts at the previous sample rather than a midpoint, and its last
     * ends at the current one, so the trail still reaches its own tail and the finger. Every
     * piece in between shares its endpoints with its neighbours, which is what makes the
     * curve continuous.
     */
    fun quadInto(
        out: FloatArray,
        prevX: Float,
        prevY: Float,
        curX: Float,
        curY: Float,
        nextX: Float,
        nextY: Float,
        isFirst: Boolean,
        isLast: Boolean,
    ) {
        out[0] = if (isFirst) prevX else (prevX + curX) / 2f
        out[1] = if (isFirst) prevY else (prevY + curY) / 2f
        out[2] = curX
        out[3] = curY
        out[4] = if (isLast) curX else (curX + nextX) / 2f
        out[5] = if (isLast) curY else (curY + nextY) / 2f
    }
}
