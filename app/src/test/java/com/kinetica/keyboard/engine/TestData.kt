package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.Dwell
import com.kinetica.keyboard.engine.models.PathPoint
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.TapToken
import kotlin.math.sqrt

/** Shared fixtures: a synthetic QWERTY geometry and hand-built tokens. */
object TestData {
    const val KEY_W = 100f

    /**
     * Standard QWERTY grid, 1000px wide, 150px row height (kw: keys 1.0 wide,
     * rows 1.5 tall) - same proportions as assets/layouts/qwerty.json.
     */
    fun qwertyGeometry(): KeyboardGeometry {
        val rows = listOf(
            "qwertyuiop" to 0.0f,
            "asdfghjkl" to 0.5f,
            "zxcvbnm" to 1.5f,
        )
        val rects = ArrayList<FloatArray>()
        val codes = ArrayList<Int>()
        for ((rowIdx, row) in rows.withIndex()) {
            val (letters, offsetKeys) = row
            val top = rowIdx * 150f
            for ((i, ch) in letters.withIndex()) {
                val left = (offsetKeys + i) * KEY_W
                rects.add(floatArrayOf(left, top, left + KEY_W, top + 150f))
                codes.add(ch - 'a')
            }
        }
        return KeyboardGeometry.fromPx(KEY_W, 1000f, rects, codes.toIntArray())
    }

    fun smallDictionary(): Trie = Trie.build(
        listOf(
            "the" to 12000, "then" to 5000, "them" to 4000, "they" to 4500,
            "there" to 3000, "their" to 2800, "these" to 2500,
            "something" to 1500, "sometimes" to 1400, "someone" to 1300,
            "some" to 3500, "so" to 6000, "son" to 900,
            "hello" to 2000, "help" to 1800, "hell" to 400,
            "a" to 20000, "and" to 15000, "at" to 9000,
            "smoothing" to 50, "soothing" to 60,
            "don't" to 2200, "dont" to 5,
            "tie" to 300, "toe" to 200,
        ),
    )

    /** Tap at the exact key center. */
    fun tap(c: Char, g: KeyboardGeometry, t0: Long, stream: StreamId = StreamId.LEFT): TapToken {
        val code = c - 'a'
        return TapToken(stream, code, g.centerX(code), g.centerY(code), false, t0, t0 + 60)
    }

    /**
     * A clean swipe through the word's key centers: the ideal polyline,
     * densified to ~10 samples per segment with linear timestamps.
     */
    fun swipe(
        letters: String,
        g: KeyboardGeometry,
        t0: Long,
        durMs: Long,
        stream: StreamId = StreamId.RIGHT,
    ): SwipeToken {
        val centers = ArrayList<Pair<Float, Float>>()
        var prev = -1
        for (ch in letters) {
            val code = ch - 'a'
            if (code == prev) continue
            centers.add(g.centerX(code) to g.centerY(code))
            prev = code
        }
        require(centers.size >= 1)
        val path = ArrayList<PathPoint>()
        if (centers.size == 1) {
            for (k in 0 until 8) {
                path.add(PathPoint(centers[0].first, centers[0].second, t0 + k))
            }
        } else {
            val perSeg = 10
            val totalPts = (centers.size - 1) * perSeg + 1
            var idx = 0
            for (s in 0 until centers.size - 1) {
                val (x0, y0) = centers[s]
                val (x1, y1) = centers[s + 1]
                for (k in 0 until perSeg) {
                    val f = k / perSeg.toFloat()
                    val t = t0 + durMs * idx / (totalPts - 1)
                    path.add(PathPoint(x0 + f * (x1 - x0), y0 + f * (y1 - y0), t))
                    idx++
                }
            }
            path.add(PathPoint(centers.last().first, centers.last().second, t0 + durMs))
        }
        var arc = 0f
        for (i in 1 until path.size) {
            val dx = path[i].x - path[i - 1].x
            val dy = path[i].y - path[i - 1].y
            arc += sqrt(dx * dx + dy * dy)
        }
        val resampled = FloatArray(2 * KineticaConstants.RESAMPLE_N)
        DtwMatcher().resample(path, resampled)
        return SwipeToken(stream, path, resampled, emptyList(), arc, t0, t0 + durMs)
    }

    /**
     * A realistic imperfect swipe: at every interior vertex the finger
     * overshoots the turn by [overshootKw] along the incoming direction before
     * correcting toward the next key. Perfect center-to-center paths mask
     * pruning bugs that only fire when local minima shift by a sample or two.
     */
    fun sloppySwipe(
        letters: String,
        g: KeyboardGeometry,
        t0: Long,
        durMs: Long,
        overshootKw: Float = 0.4f,
        stream: StreamId = StreamId.RIGHT,
    ): SwipeToken {
        val centers = ArrayList<Pair<Float, Float>>()
        var prev = -1
        for (ch in letters) {
            val code = ch - 'a'
            if (code == prev) continue
            centers.add(g.centerX(code) to g.centerY(code))
            prev = code
        }
        require(centers.size >= 2)
        val vertices = ArrayList<Pair<Float, Float>>()
        vertices.add(centers[0])
        for (i in 1 until centers.size) {
            val (px, py) = centers[i - 1]
            val (cx, cy) = centers[i]
            val len = sqrt((cx - px) * (cx - px) + (cy - py) * (cy - py))
            vertices.add(cx to cy)
            if (i < centers.size - 1 && len > 1e-3f) {
                vertices.add(cx + (cx - px) / len * overshootKw to cy + (cy - py) / len * overshootKw)
            }
        }
        val path = ArrayList<PathPoint>()
        val perSeg = 10
        val totalPts = (vertices.size - 1) * perSeg + 1
        var idx = 0
        for (s in 0 until vertices.size - 1) {
            val (x0, y0) = vertices[s]
            val (x1, y1) = vertices[s + 1]
            for (k in 0 until perSeg) {
                val f = k / perSeg.toFloat()
                val t = t0 + durMs * idx / (totalPts - 1)
                path.add(PathPoint(x0 + f * (x1 - x0), y0 + f * (y1 - y0), t))
                idx++
            }
        }
        path.add(PathPoint(vertices.last().first, vertices.last().second, t0 + durMs))
        var arc = 0f
        for (i in 1 until path.size) {
            val dx = path[i].x - path[i - 1].x
            val dy = path[i].y - path[i - 1].y
            arc += sqrt(dx * dx + dy * dy)
        }
        val resampled = FloatArray(2 * KineticaConstants.RESAMPLE_N)
        DtwMatcher().resample(path, resampled)
        return SwipeToken(stream, path, resampled, emptyList(), arc, t0, t0 + durMs)
    }

    /**
     * One continuous swipe with a mid-path rest: the finger travels through
     * [before]'s key centers, rests on the last of them across a time gap
     * (many stationary samples), then resumes from that rest position through
     * [after]'s key centers. Models a thumb that pauses mid-word while the
     * other thumb acts - the resume-after-interruption pattern the merge's
     * split generators exist to cover. [overshootKw] adds turn overshoot so
     * the fixture is not a perfect-center path, per the sloppy-fixture
     * discipline.
     */
    fun dwellSwipe(
        before: String,
        after: String,
        g: KeyboardGeometry,
        t0: Long,
        travelMs: Long,
        restMs: Long,
        resumeMs: Long,
        overshootKw: Float = 0f,
        stream: StreamId = StreamId.LEFT,
        /**
         * Attach the [Dwell] marker GestureStream would have produced for the
         * rest span. Off by default so every pre-existing fixture stays a
         * dwell-free Tier-1 control: those goldens must keep proving the six
         * geometric split mechanisms without any dwell help.
         */
        markDwell: Boolean = false,
    ): SwipeToken {
        val beforeCenters = uniqueCenters(before, g)
        require(beforeCenters.isNotEmpty())
        val restCenter = beforeCenters.last()
        // The resumed leg starts at the rest position and visits the after-keys.
        val resumeCenters = ArrayList<Pair<Float, Float>>()
        resumeCenters.add(restCenter)
        for (c in uniqueCenters(after, g)) if (c != resumeCenters.last()) resumeCenters.add(c)
        require(resumeCenters.size >= 2) { "resume leg needs at least one new key" }

        val path = ArrayList<PathPoint>()
        appendPolyline(path, withOvershoot(beforeCenters, overshootKw), t0, t0 + travelMs)
        val restStart = t0 + travelMs
        val restEnd = restStart + restMs
        // Stationary samples across the pause: the held pointer keeps receiving
        // timestamped samples at the same coordinate while the other thumb moves.
        val restFirstIdx = path.size
        val restSamples = 12
        for (k in 0..restSamples) {
            val t = restStart + (restEnd - restStart) * k / restSamples
            path.add(PathPoint(restCenter.first, restCenter.second, t))
        }
        val restLastIdx = path.size - 1
        appendPolyline(path, withOvershoot(resumeCenters, overshootKw), restEnd, restEnd + resumeMs)

        var arc = 0f
        for (i in 1 until path.size) {
            val dx = path[i].x - path[i - 1].x
            val dy = path[i].y - path[i - 1].y
            arc += sqrt(dx * dx + dy * dy)
        }
        val resampled = FloatArray(2 * KineticaConstants.RESAMPLE_N)
        DtwMatcher().resample(path, resampled)
        val dwells = if (markDwell) {
            listOf(Dwell(restFirstIdx, restLastIdx, restStart, restEnd))
        } else {
            emptyList()
        }
        return SwipeToken(
            stream, path, resampled, emptyList(), arc, t0, restEnd + resumeMs,
            dwells = dwells,
        )
    }

    /** Key centers for a string, dropping consecutive duplicates. */
    private fun uniqueCenters(letters: String, g: KeyboardGeometry): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        var prev = -1
        for (ch in letters) {
            val code = ch - 'a'
            if (code == prev) continue
            out.add(g.centerX(code) to g.centerY(code))
            prev = code
        }
        return out
    }

    /** Overshoot each interior vertex along its incoming direction (as sloppySwipe). */
    private fun withOvershoot(
        centers: List<Pair<Float, Float>>,
        overshootKw: Float,
    ): List<Pair<Float, Float>> {
        if (overshootKw <= 0f || centers.size < 2) return centers
        val out = ArrayList<Pair<Float, Float>>()
        out.add(centers[0])
        for (i in 1 until centers.size) {
            val (px, py) = centers[i - 1]
            val (cx, cy) = centers[i]
            val len = sqrt((cx - px) * (cx - px) + (cy - py) * (cy - py))
            out.add(cx to cy)
            if (i < centers.size - 1 && len > 1e-3f) {
                out.add(cx + (cx - px) / len * overshootKw to cy + (cy - py) / len * overshootKw)
            }
        }
        return out
    }

    /** Interpolate ~10 samples per leg through [vertices], timestamps over [tStart,tEnd]. */
    private fun appendPolyline(
        path: ArrayList<PathPoint>,
        vertices: List<Pair<Float, Float>>,
        tStart: Long,
        tEnd: Long,
    ) {
        if (vertices.size < 2) {
            path.add(PathPoint(vertices[0].first, vertices[0].second, tStart))
            return
        }
        val perSeg = 10
        val totalPts = (vertices.size - 1) * perSeg + 1
        var idx = 0
        for (s in 0 until vertices.size - 1) {
            val (x0, y0) = vertices[s]
            val (x1, y1) = vertices[s + 1]
            for (k in 0 until perSeg) {
                val f = k / perSeg.toFloat()
                val t = tStart + (tEnd - tStart) * idx / (totalPts - 1)
                path.add(PathPoint(x0 + f * (x1 - x0), y0 + f * (y1 - y0), t))
                idx++
            }
        }
        path.add(PathPoint(vertices.last().first, vertices.last().second, tEnd))
    }
}
