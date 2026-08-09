package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.PathPoint
import kotlin.math.min
import kotlin.math.sqrt

/**
 * From-scratch dynamic time warping over arc-length-resampled 2D paths.
 *
 * Both the observed swipe and the ideal word path are resampled to N points at
 * uniform arc-length spacing before matching. That removes finger-speed
 * variation entirely, so the remaining warping is geometric (corner cutting,
 * overshoot) and a narrow Sakoe-Chiba band suffices. Fixed N keeps the matrix
 * shape constant: two preallocated rolling rows, zero allocation per call.
 *
 * NOT thread-safe: one instance per decode thread (and per test).
 */
class DtwMatcher {
    private val n = KineticaConstants.RESAMPLE_N
    private val band = KineticaConstants.DTW_BAND_R
    private val prev = FloatArray(n)
    private val curr = FloatArray(n)
    private val polyScratch = FloatArray(2 * (KineticaConstants.MAX_WORD_LEN + 1))

    /** Resamples [path] to N points at uniform arc length into [out] (2N floats). */
    fun resample(path: List<PathPoint>, out: FloatArray) {
        val count = path.size
        if (count == 0) return
        if (count == 1) {
            fillConstant(path[0].x, path[0].y, out)
            return
        }
        var arc = 0f
        for (i in 1 until count) {
            arc += dist(path[i - 1].x, path[i - 1].y, path[i].x, path[i].y)
        }
        if (arc <= 1e-6f) {
            fillConstant(path[0].x, path[0].y, out)
            return
        }
        val step = arc / (n - 1)
        out[0] = path[0].x
        out[1] = path[0].y
        var seg = 1
        var segStartAcc = 0f
        var segLen = dist(path[0].x, path[0].y, path[1].x, path[1].y)
        for (k in 1 until n - 1) {
            val target = k * step
            while (segStartAcc + segLen < target && seg < count - 1) {
                segStartAcc += segLen
                seg++
                segLen = dist(path[seg - 1].x, path[seg - 1].y, path[seg].x, path[seg].y)
            }
            val f = if (segLen <= 1e-6f) 0f else (target - segStartAcc) / segLen
            out[2 * k] = path[seg - 1].x + f * (path[seg].x - path[seg - 1].x)
            out[2 * k + 1] = path[seg - 1].y + f * (path[seg].y - path[seg - 1].y)
        }
        out[2 * (n - 1)] = path[count - 1].x
        out[2 * (n - 1) + 1] = path[count - 1].y
    }

    /**
     * Ideal path for letters[from until to]: polyline through key centers with
     * consecutive duplicate keys removed (a doubled letter adds zero length, so
     * "hello" and "helo" share one ideal path and the dictionary disambiguates)
     * and apostrophes skipped (no key on the letter layer). Resampled into
     * [out]. Returns false when no drawable letter remains.
     */
    fun idealPath(letters: IntArray, from: Int, to: Int, geometry: KeyboardGeometry, out: FloatArray): Boolean {
        var m = 0
        var prevCode = -1
        for (i in from until to) {
            val code = letters[i]
            if (code == Alphabet.APOSTROPHE || !geometry.hasKey(code)) continue
            if (code == prevCode) continue
            polyScratch[2 * m] = geometry.centerX(code)
            polyScratch[2 * m + 1] = geometry.centerY(code)
            prevCode = code
            m++
        }
        if (m == 0) return false
        resamplePoly(polyScratch, m, out)
        return true
    }

    /** Resamples a polyline of [count] (x, y) pairs to N points into [out]. */
    fun resamplePoly(poly: FloatArray, count: Int, out: FloatArray) {
        if (count == 1) {
            fillConstant(poly[0], poly[1], out)
            return
        }
        var arc = 0f
        for (i in 1 until count) {
            arc += dist(poly[2 * (i - 1)], poly[2 * (i - 1) + 1], poly[2 * i], poly[2 * i + 1])
        }
        if (arc <= 1e-6f) {
            fillConstant(poly[0], poly[1], out)
            return
        }
        val step = arc / (n - 1)
        out[0] = poly[0]
        out[1] = poly[1]
        var seg = 1
        var segStartAcc = 0f
        var segLen = dist(poly[0], poly[1], poly[2], poly[3])
        for (k in 1 until n - 1) {
            val target = k * step
            while (segStartAcc + segLen < target && seg < count - 1) {
                segStartAcc += segLen
                seg++
                segLen = dist(
                    poly[2 * (seg - 1)], poly[2 * (seg - 1) + 1],
                    poly[2 * seg], poly[2 * seg + 1],
                )
            }
            val f = if (segLen <= 1e-6f) 0f else (target - segStartAcc) / segLen
            out[2 * k] = poly[2 * (seg - 1)] + f * (poly[2 * seg] - poly[2 * (seg - 1)])
            out[2 * k + 1] = poly[2 * (seg - 1) + 1] + f * (poly[2 * seg + 1] - poly[2 * (seg - 1) + 1])
        }
        out[2 * (n - 1)] = poly[2 * (count - 1)]
        out[2 * (n - 1) + 1] = poly[2 * (count - 1) + 1]
    }

    /**
     * Banded DTW between two resampled paths. Both endpoints are anchored (the
     * gesture's start/end are the user's most deliberate positions, weighted
     * x2). Returns ACCUMULATED cost, or +Inf once the running row minimum
     * exceeds [abandonAboveAccum]. Divide by N for the mean per-step cost.
     */
    fun distanceAccum(observed: FloatArray, ideal: FloatArray, abandonAboveAccum: Float): Float {
        val w = KineticaConstants.DTW_ENDPOINT_WEIGHT
        java.util.Arrays.fill(prev, Float.POSITIVE_INFINITY)
        java.util.Arrays.fill(curr, Float.POSITIVE_INFINITY)
        prev[0] = w * cellDist(observed, 0, ideal, 0)
        val row0Hi = min(n - 1, band)
        for (j in 1..row0Hi) {
            prev[j] = prev[j - 1] + cellDist(observed, 0, ideal, j)
        }
        for (i in 1 until n) {
            val jLo = maxOf(0, i - band)
            val jHi = min(n - 1, i + band)
            if (jLo > 0) curr[jLo - 1] = Float.POSITIVE_INFINITY
            var rowMin = Float.POSITIVE_INFINITY
            for (j in jLo..jHi) {
                val d = cellDist(observed, i, ideal, j)
                var best = prev[j]
                if (j > 0) {
                    if (prev[j - 1] < best) best = prev[j - 1]
                    if (curr[j - 1] < best) best = curr[j - 1]
                }
                val weighted = if (i == n - 1 && j == n - 1) w * d else d
                val v = weighted + best
                curr[j] = v
                if (v < rowMin) rowMin = v
            }
            if (jHi < n - 1) curr[jHi + 1] = Float.POSITIVE_INFINITY
            if (rowMin > abandonAboveAccum) return Float.POSITIVE_INFINITY
            System.arraycopy(curr, 0, prev, 0, n)
        }
        return prev[n - 1]
    }

    private fun fillConstant(x: Float, y: Float, out: FloatArray) {
        for (k in 0 until n) {
            out[2 * k] = x
            out[2 * k + 1] = y
        }
    }

    private fun cellDist(a: FloatArray, i: Int, b: FloatArray, j: Int): Float {
        val dx = a[2 * i] - b[2 * j]
        val dy = a[2 * i + 1] - b[2 * j + 1]
        return sqrt(dx * dx + dy * dy)
    }

    private fun dist(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x1 - x0
        val dy = y1 - y0
        return sqrt(dx * dx + dy * dy)
    }
}
