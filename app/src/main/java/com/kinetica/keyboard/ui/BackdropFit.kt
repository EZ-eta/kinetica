package com.kinetica.keyboard.ui

/**
 * The background image's arithmetic: how much to shrink it on import, which way to turn it, and
 * which part of it fills the keyboard. Pure, so `BackdropFitTest` reaches it; `RectF` and the
 * bitmap classes are stubs on the JVM.
 */
object BackdropFit {

    /** Image opacity over the theme background, in percent: the photo as picked. */
    const val IMAGE_ALPHA_DEFAULT = 100

    /**
     * Key opacity over the image, in percent. 60 lets the picture through and keeps a key's
     * edge readable on a busy photo; 10 is the floor, below which a key is only its label.
     */
    const val KEY_ALPHA_DEFAULT = 60
    const val KEY_ALPHA_MIN = 10

    /**
     * The power-of-two decode step that brings the long edge to [maxLong] or a little above.
     * Never below it: a crop of a wide photo for a tall portrait keyboard keeps its detail.
     */
    fun sampleSize(srcW: Int, srcH: Int, maxLong: Int): Int {
        val long = maxOf(srcW, srcH)
        if (long <= 0 || maxLong <= 0) return 1
        var size = 1
        while (long / (size * 2) >= maxLong) size *= 2
        return size
    }

    /** [w] x [h] shrunk so its long edge is at most [maxLong], its shape kept; never enlarged. */
    fun scaledSize(w: Int, h: Int, maxLong: Int): Pair<Int, Int> {
        val long = maxOf(w, h)
        if (long <= maxLong || maxLong <= 0) return w to h
        return maxOf(1, (w.toLong() * maxLong / long).toInt()) to maxOf(1, (h.toLong() * maxLong / long).toInt())
    }

    /** The turn an EXIF orientation asks for, in degrees; mirrored values are read as their turn. */
    fun degreesFor(exifOrientation: Int): Int = when (exifOrientation) {
        3, 4 -> 180
        5, 6 -> 90
        7, 8 -> 270
        else -> 0
    }

    /** Width and height after a turn of [degrees]. */
    fun rotatedSize(w: Int, h: Int, degrees: Int): Pair<Int, Int> =
        if (degrees % 180 != 0) h to w else w to h

    /**
     * The centred part of a [srcW] x [srcH] image with the shape of a [dstW] x [dstH] view, as
     * left, top, right, bottom: the image fills the keyboard, cut on its long side, never
     * stretched.
     */
    fun cropRect(srcW: Int, srcH: Int, dstW: Int, dstH: Int): IntArray {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return intArrayOf(0, 0, srcW.coerceAtLeast(0), srcH.coerceAtLeast(0))
        // Compared as cross products, so no rounding decides which side is cut.
        return if (srcW.toLong() * dstH > srcH.toLong() * dstW) {
            val w = (srcH.toLong() * dstW / dstH).toInt().coerceIn(1, srcW)
            val left = (srcW - w) / 2
            intArrayOf(left, 0, left + w, srcH)
        } else {
            val h = (srcW.toLong() * dstH / dstW).toInt().coerceIn(1, srcH)
            val top = (srcH - h) / 2
            intArrayOf(0, top, srcW, top + h)
        }
    }

    /** A percentage as a paint alpha, 0-255. */
    fun alpha255(pct: Int): Int = (pct.coerceIn(0, 100) * 255 + 50) / 100
}
