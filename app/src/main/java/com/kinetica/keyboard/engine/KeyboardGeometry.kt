package com.kinetica.keyboard.engine

import kotlin.math.sqrt

/**
 * Immutable letter-key geometry in key-width (kw) units, rebuilt by the UI on
 * every size or layout-mode change. Only letter keys participate: gesture
 * decoding never involves space, backspace, or mode keys (those pointers are
 * routed to dedicated controllers before reaching the engine).
 */
class KeyboardGeometry private constructor(
    val keyWidthPx: Float,
    /**
     * The x that divides the left thumb's half of the board from the right
     * one's, in view pixels. Used only by [GestureEngine.onPointerDown]'s
     * stream assignment.
     *
     * The centre of the letter block, not half the view width: with side
     * padding the keys no longer span the view, and a divider at half the view
     * would misassign a thumb.
     */
    val midlinePx: Float,
    /** The board's letters; a code here is a code of this alphabet. */
    val alphabet: Alphabet,
    private val present: BooleanArray,       // [letterCount]
    private val centersX: FloatArray,        // [letterCount] kw
    private val centersY: FloatArray,        // [letterCount] kw
    private val rects: FloatArray,           // [letterCount*4] kw: left, top, right, bottom
) {
    /** Letters this geometry can place: the alphabet's, keyed or not. */
    val letterCount: Int get() = alphabet.letterCount

    fun hasKey(code: Int): Boolean = code in 0 until alphabet.letterCount && present[code]

    fun centerX(code: Int): Float = centersX[code]
    fun centerY(code: Int): Float = centersY[code]

    /** Euclidean distance between two key centers, kw. */
    fun keyDist(a: Int, b: Int): Float {
        val dx = centersX[a] - centersX[b]
        val dy = centersY[a] - centersY[b]
        return sqrt(dx * dx + dy * dy)
    }

    fun distToCenter(xKw: Float, yKw: Float, code: Int): Float {
        val dx = xKw - centersX[code]
        val dy = yKw - centersY[code]
        return sqrt(dx * dx + dy * dy)
    }

    /** Letter code whose rect contains the point, or -1. */
    fun keyAt(xKw: Float, yKw: Float): Int {
        for (code in 0 until alphabet.letterCount) {
            if (!present[code]) continue
            val base = code * 4
            if (xKw >= rects[base] && xKw < rects[base + 2] &&
                yKw >= rects[base + 1] && yKw < rects[base + 3]
            ) return code
        }
        return -1
    }

    /** True while the point is inside the key's rect inflated by [inflateKw]. */
    fun insideInflated(xKw: Float, yKw: Float, code: Int, inflateKw: Float): Boolean {
        val base = code * 4
        return xKw >= rects[base] - inflateKw && xKw < rects[base + 2] + inflateKw &&
            yKw >= rects[base + 1] - inflateKw && yKw < rects[base + 3] + inflateKw
    }

    /** Letter code nearest to the point by center distance, or -1 if none present. */
    fun nearestKey(xKw: Float, yKw: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (code in 0 until alphabet.letterCount) {
            if (!present[code]) continue
            val d = distToCenter(xKw, yKw, code)
            if (d < bestD) {
                bestD = d
                best = code
            }
        }
        return best
    }

    companion object {
        /**
         * Builds geometry from pixel-space letter-key rects. Each entry is
         * (code, leftPx, topPx, rightPx, bottomPx). [keyWidthPx] is the width
         * of a standard letter key and defines the kw unit.
         */
        fun fromPx(
            keyWidthPx: Float,
            midlinePx: Float,
            letterRectsPx: List<FloatArray>,
            codes: IntArray,
            alphabet: Alphabet = Alphabet.LATIN,
        ): KeyboardGeometry {
            require(keyWidthPx > 0f) { "keyWidthPx must be positive" }
            require(letterRectsPx.size == codes.size)
            val n = alphabet.letterCount
            val present = BooleanArray(n)
            val cx = FloatArray(n)
            val cy = FloatArray(n)
            val rects = FloatArray(n * 4)
            for (i in codes.indices) {
                val code = codes[i]
                if (code !in 0 until n) continue
                val r = letterRectsPx[i]
                val base = code * 4
                rects[base] = r[0] / keyWidthPx
                rects[base + 1] = r[1] / keyWidthPx
                rects[base + 2] = r[2] / keyWidthPx
                rects[base + 3] = r[3] / keyWidthPx
                cx[code] = (rects[base] + rects[base + 2]) / 2f
                cy[code] = (rects[base + 1] + rects[base + 3]) / 2f
                present[code] = true
            }
            return KeyboardGeometry(keyWidthPx, midlinePx, alphabet, present, cx, cy, rects)
        }
    }
}
