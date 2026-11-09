package com.kinetica.keyboard.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.kinetica.keyboard.engine.models.StreamId

/**
 * Per-stream swipe trails, in the hue [TrailHue] gives; points fade and shrink over
 * [TrailLife.LIFE_MS], or with [keeps] stay whole until [release].
 *
 * One smoothed piece per sample, so a fast swipe does not read as facets; the joins are
 * [TrailPath]'s and are tested there.
 */
class TrailRenderer(private val density: Float) {

    private class TrailPoint(
        var x: Float,
        var y: Float,
        var t: Long,
        var hue: Float,
        var breakBefore: Boolean,
        var kept: Boolean,
    )

    private val trails = arrayOf(ArrayDeque<TrailPoint>(), ArrayDeque<TrailPoint>())
    private val pointPool = ArrayDeque<TrailPoint>()
    private val hues = floatArrayOf(0f, 0f)
    private val forceNextPoint = BooleanArray(2)
    private val sampling = TrailSampling(MIN_SAMPLE_INTERVAL_MS)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val hsv = FloatArray(3)

    // Both reused every frame: one piece is drawn at a time, so smoothing allocates nothing.
    private val path = Path()
    private val quad = FloatArray(TrailPath.SIZE)

    /** Base hue in degrees. */
    var baseHue = 0f

    /** Saturation and value every point is drawn at: a typed colour keeps its own. */
    var saturation = 0.85f
    var value = 1f

    /** Rainbow: the hue steps per key and the right stream starts offset. */
    var cycles = true

    /** Points laid from now on stay whole until [release]: the trail kept until the word ends. */
    var keeps = false

    fun startStream(stream: StreamId) {
        val i = stream.ordinal
        hues[i] = TrailHue.start(baseHue, stream, cycles)
        forceNextPoint[i] = true
    }

    fun addPoint(stream: StreamId, x: Float, y: Float, t: Long) {
        val i = stream.ordinal
        val trail = trails[i]
        val hue = hues[i]
        val last = trail.lastOrNull()
        // MotionEvent history can deliver several samples per frame. The decoder keeps them
        // all; the trail needs one point per ~8 ms, so the endpoint moves instead, bounding
        // draw work and allocation on high-refresh screens. TrailSampling holds the rule.
        val lays = sampling.lays(i, t, forceNextPoint[i], last?.hue == hue, hasLast = last != null)
        if (!lays && last != null) {
            last.x = x
            last.y = y
            last.t = t
            return
        }
        val breakBefore = forceNextPoint[i]
        forceNextPoint[i] = false
        val point = if (pointPool.isEmpty()) {
            TrailPoint(x, y, t, hue, breakBefore, keeps)
        } else {
            pointPool.removeLast().also {
                it.x = x
                it.y = y
                it.t = t
                it.hue = hue
                it.breakBefore = breakBefore
                it.kept = keeps
            }
        }
        trail.addLast(point)
    }

    /** The hue [stream] is drawing in now. */
    fun hueOf(stream: StreamId): Float = hues[stream.ordinal]

    fun bumpHue(stream: StreamId) {
        hues[stream.ordinal] = TrailHue.next(hues[stream.ordinal], cycles)
    }

    /**
     * Drops expired points; returns true while anything is still fading. Kept points are laid after
     * any fading ones, so a trail whose first point is kept is still: no frame needs drawing.
     */
    fun prune(now: Long): Boolean {
        var alive = false
        for (trail in trails) {
            while (trail.isNotEmpty() && TrailLife.expired(trail.first().kept, trail.first().t, now)) {
                recycle(trail.removeFirst())
            }
            if (trail.isNotEmpty() && !trail.first().kept) alive = true
        }
        return alive
    }

    /** The kept points start fading [now]; true when there were any. */
    fun release(now: Long): Boolean {
        var any = false
        for (trail in trails) {
            for (p in trail) {
                if (!p.kept) continue
                p.kept = false
                p.t = now
                any = true
            }
        }
        return any
    }

    fun clear() {
        for (trail in trails) {
            while (trail.isNotEmpty()) recycle(trail.removeFirst())
        }
        forceNextPoint.fill(false)
    }

    private fun recycle(point: TrailPoint) {
        if (pointPool.size < MAX_POOLED_POINTS) pointPool.addLast(point)
    }

    fun draw(canvas: Canvas, now: Long) {
        for (trail in trails) {
            if (trail.size < 2) continue
            for (i in 1 until trail.size) {
                val p = trail[i]
                val a = trail[i - 1]
                if (p.breakBefore) continue
                val f = TrailLife.strength(p.kept, p.t, now)
                if (f <= 0f) continue
                hsv[0] = p.hue
                hsv[1] = saturation
                hsv[2] = value
                paint.color = Color.HSVToColor((200 * f).toInt(), hsv)
                paint.strokeWidth = (3f + 7f * f) * density
                // A run ends at a break as well as at the finger, so a lift stays a gap, not a
                // curve across the keyboard.
                val next = trail.getOrNull(i + 1)?.takeIf { !it.breakBefore }
                TrailPath.quadInto(
                    quad,
                    a.x, a.y, p.x, p.y,
                    next?.x ?: p.x, next?.y ?: p.y,
                    isFirst = i == 1 || a.breakBefore,
                    isLast = next == null,
                )
                path.rewind()
                path.moveTo(quad[0], quad[1])
                path.quadTo(quad[2], quad[3], quad[4], quad[5])
                canvas.drawPath(path, paint)
            }
        }
    }

    private companion object {
        const val MIN_SAMPLE_INTERVAL_MS = 8L
        const val MAX_POOLED_POINTS = 128
    }
}
