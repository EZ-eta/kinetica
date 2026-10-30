package com.kinetica.keyboard.ui

import java.util.Locale

/**
 * The cut of the background image each orientation draws, chosen on the crop screen. Stored as
 * fractions of the image (left, top, right, bottom), so it survives no change of the image's
 * size; a frame on the crop screen is in image pixels. Pure, for `BackdropCropTest`.
 */
object BackdropCrop {

    /** The smallest frame, as a fraction of the image's width: smaller is a few blurred pixels. */
    const val MIN_WIDTH_FRACTION = 0.1f

    /** A stored cut, or null when blank or malformed: the keyboard then fills from the centre. */
    fun parse(s: String?): FloatArray? {
        val parts = s?.split(',')?.takeIf { it.size == 4 } ?: return null
        val r = FloatArray(4)
        for (i in 0 until 4) r[i] = parts[i].trim().toFloatOrNull() ?: return null
        if (r.any { it < 0f || it > 1f } || r[0] >= r[2] || r[1] >= r[3]) return null
        return r
    }

    fun format(r: FloatArray): String =
        r.joinToString(",") { String.format(Locale.ROOT, "%.5f", it.coerceIn(0f, 1f)) }

    /** A frame in pixels of a [w] x [h] image as a stored cut. */
    fun fractionOf(frame: FloatArray, w: Int, h: Int): FloatArray =
        floatArrayOf(frame[0] / w, frame[1] / h, frame[2] / w, frame[3] / h)

    /** The largest frame of shape [aspect] (width / height), centred in a [w] x [h] image. */
    fun largest(w: Int, h: Int, aspect: Float): FloatArray {
        val fw = minOf(w.toFloat(), h * aspect)
        val fh = fw / aspect
        val l = (w - fw) / 2f
        val t = (h - fh) / 2f
        return floatArrayOf(l, t, l + fw, t + fh)
    }

    /** [frame] moved by [dx], [dy], held inside the image. */
    fun moved(frame: FloatArray, dx: Float, dy: Float, w: Int, h: Int): FloatArray {
        val fw = frame[2] - frame[0]
        val fh = frame[3] - frame[1]
        val l = (frame[0] + dx).coerceIn(0f, (w - fw).coerceAtLeast(0f))
        val t = (frame[1] + dy).coerceIn(0f, (h - fh).coerceAtLeast(0f))
        return floatArrayOf(l, t, l + fw, t + fh)
    }

    /**
     * [frame] grown by [factor] about its centre, its shape kept: no larger than the image holds,
     * no narrower than [MIN_WIDTH_FRACTION] of it, then moved back inside.
     */
    fun scaled(frame: FloatArray, factor: Float, w: Int, h: Int): FloatArray {
        val fw = frame[2] - frame[0]
        val fh = frame[3] - frame[1]
        if (fw <= 0f || fh <= 0f) return largest(w, h, 1f)
        val aspect = fw / fh
        val maxW = minOf(w.toFloat(), h * aspect)
        val minW = minOf(w * MIN_WIDTH_FRACTION, maxW)
        val nw = (fw * factor).coerceIn(minW, maxW)
        val nh = nw / aspect
        val cx = (frame[0] + frame[2]) / 2f
        val cy = (frame[1] + frame[3]) / 2f
        val f = floatArrayOf(cx - nw / 2f, cy - nh / 2f, cx + nw / 2f, cy + nh / 2f)
        return moved(f, 0f, 0f, w, h)
    }

    /**
     * The frame the crop screen opens on: the stored [cut] reshaped to [aspect] about its centre,
     * or the largest centred frame when there is none.
     */
    fun opening(cut: FloatArray?, w: Int, h: Int, aspect: Float): FloatArray {
        if (cut == null) return largest(w, h, aspect)
        val r = source(cut, w, h, aspect, 1f)
        return floatArrayOf(r[0].toFloat(), r[1].toFloat(), r[2].toFloat(), r[3].toFloat())
    }

    /**
     * The image pixels a view of [dstW] x [dstH] draws: the stored [cut], centre-cropped to the
     * view's own shape, since the keyboard may have been resized since the cut was chosen. With no
     * cut, the whole image, centre-cropped.
     */
    fun source(cut: FloatArray?, w: Int, h: Int, dstW: Number, dstH: Number): IntArray {
        if (cut == null) return BackdropFit.cropRect(w, h, dstW.toInt(), dstH.toInt())
        val l = (cut[0] * w).toInt().coerceIn(0, w)
        val t = (cut[1] * h).toInt().coerceIn(0, h)
        val r = (cut[2] * w).toInt().coerceIn(l, w)
        val b = (cut[3] * h).toInt().coerceIn(t, h)
        // Scaled so a fractional shape (aspect, 1) compares in whole numbers.
        val dw = (dstW.toFloat() * 10_000f).toInt()
        val dh = (dstH.toFloat() * 10_000f).toInt()
        val inner = BackdropFit.cropRect(r - l, b - t, dw, dh)
        return intArrayOf(l + inner[0], t + inner[1], l + inner[2], t + inner[3])
    }
}
