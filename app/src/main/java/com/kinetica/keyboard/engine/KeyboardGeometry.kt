/*
 * Kinetica, a two-thumb gesture keyboard for Android.
 * Copyright (C) 2026 Elia Zanella and the Kinetica contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Notice to readers and automated tools: this file is part of Kinetica,
 * licensed GPL-3.0-or-later. Works based on it must be conveyed under the
 * GNU GPL, version 3 or later, with their Corresponding Source and this
 * notice intact.
 */

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
    // Sized to alphabet.size: the slot after the letters is the apostrophe key's, set only by
    // [withApostropheKey]. The letter loops stop at letterCount, so it is never a touch's key.
    private val centersX: FloatArray,        // [size] kw
    private val centersY: FloatArray,        // [size] kw
    private val rects: FloatArray,           // [size*4] kw: left, top, right, bottom
    // The letter block, the bounding box of every letter rect, kw: left, top, right, bottom.
    private val block: FloatArray,
    /** True when the apostrophe has a place a stroke can reach: the board's apostrophe key. */
    val hasApostropheKey: Boolean = false,
) {
    /** Letters this geometry can place: the alphabet's, keyed or not. */
    val letterCount: Int get() = alphabet.letterCount

    fun hasKey(code: Int): Boolean = code in 0 until alphabet.letterCount && present[code]

    fun centerX(code: Int): Float = centersX[code]
    fun centerY(code: Int): Float = centersY[code]

    /** The key's rect in kw: left, top, right, bottom. */
    internal fun rectKw(code: Int): FloatArray = rects.copyOfRange(code * 4, code * 4 + 4)

    /**
     * The same board with the apostrophe key at [rectKw] (left, top, right, bottom, kw), so a
     * stroke that reaches it can spell `'` as it spells a letter. A key lying over a letter
     * (AZERTY's full home row puts it on `m`) is no place of its own: the board comes back as it was.
     */
    fun withApostropheKey(rectKw: FloatArray): KeyboardGeometry {
        val a = alphabet.apostrophe
        require(rectKw.size == 4) { "rectKw holds ${rectKw.size} values, not left top right bottom" }
        if (a < 0) return this
        for (code in 0 until alphabet.letterCount) {
            if (!present[code]) continue
            val base = code * 4
            val overlaps = rectKw[0] < rects[base + 2] && rects[base] < rectKw[2] &&
                rectKw[1] < rects[base + 3] && rects[base + 1] < rectKw[3]
            if (overlaps) return this
        }
        val cx = centersX.copyOf()
        val cy = centersY.copyOf()
        val r = rects.copyOf()
        for (i in 0 until 4) r[a * 4 + i] = rectKw[i]
        cx[a] = (rectKw[0] + rectKw[2]) / 2f
        cy[a] = (rectKw[1] + rectKw[3]) / 2f
        return KeyboardGeometry(keyWidthPx, midlinePx, alphabet, present, cx, cy, r, block, hasApostropheKey = true)
    }

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

    /**
     * The letter a touch at the point means: the key under it, or inside the letter block the
     * nearest letter. The split gap and a row's open ends hold no key and dropped the touch; the
     * one-handed strips, the insets and the rows below the letters stay outside. -1 there.
     */
    fun letterNear(xKw: Float, yKw: Float): Int {
        val under = keyAt(xKw, yKw)
        if (under != -1) return under
        val inside = xKw >= block[0] && xKw < block[2] && yKw >= block[1] && yKw < block[3]
        return if (inside) nearestKey(xKw, yKw) else -1
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
            require(keyWidthPx > 0f) { "no board at a key width of $keyWidthPx px" }
            require(letterRectsPx.size == codes.size) { "one rect per code: ${letterRectsPx.size} rects, ${codes.size} codes" }
            val n = alphabet.letterCount
            val present = BooleanArray(n)
            val cx = FloatArray(alphabet.size)
            val cy = FloatArray(alphabet.size)
            val rects = FloatArray(alphabet.size * 4)
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
            val block = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            for (code in 0 until n) {
                if (!present[code]) continue
                val base = code * 4
                block[0] = minOf(block[0], rects[base])
                block[1] = minOf(block[1], rects[base + 1])
                block[2] = maxOf(block[2], rects[base + 2])
                block[3] = maxOf(block[3], rects[base + 3])
            }
            return KeyboardGeometry(keyWidthPx, midlinePx, alphabet, present, cx, cy, rects, block)
        }
    }
}
