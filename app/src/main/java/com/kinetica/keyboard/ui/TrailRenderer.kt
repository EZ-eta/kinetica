package com.kinetica.keyboard.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.kinetica.keyboard.engine.models.StreamId

/**
 * Per-stream swipe trails. Hue starts at the configured base (offset for the
 * right thumb so simultaneous trails are distinguishable) and advances 30 deg
 * on every key transition; points fade and shrink over TRAIL_LIFE_MS.
 */
class TrailRenderer(private val density: Float) {

    private class TrailPoint(
        var x: Float,
        var y: Float,
        var t: Long,
        var hue: Float,
        var breakBefore: Boolean,
    )

    private val trails = arrayOf(ArrayDeque<TrailPoint>(), ArrayDeque<TrailPoint>())
    private val pointPool = ArrayDeque<TrailPoint>()
    private val hues = floatArrayOf(0f, 0f)
    private val forceNextPoint = BooleanArray(2)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val hsv = FloatArray(3)

    /** Base hue in degrees; the right stream starts offset by 60 deg. */
    var baseHue = 0f

    fun startStream(stream: StreamId) {
        val i = stream.ordinal
        hues[i] = (baseHue + if (stream == StreamId.RIGHT) 60f else 0f) % 360f
        forceNextPoint[i] = true
    }

    fun addPoint(stream: StreamId, x: Float, y: Float, t: Long) {
        val i = stream.ordinal
        val trail = trails[i]
        val hue = hues[i]
        val last = trail.lastOrNull()
        // MotionEvent history can deliver several samples per display frame.
        // The decoder keeps every sample; the visual trail only needs one point
        // per ~8 ms. Updating the endpoint preserves its current position while
        // bounding both drawLine work and allocation pressure on high-refresh
        // devices. Hue transitions remain exact because they force a new point.
        if (!forceNextPoint[i] && last != null && last.hue == hue &&
            t >= last.t && t - last.t < MIN_SAMPLE_INTERVAL_MS
        ) {
            last.x = x
            last.y = y
            last.t = t
            return
        }
        val breakBefore = forceNextPoint[i]
        forceNextPoint[i] = false
        val point = if (pointPool.isEmpty()) {
            TrailPoint(x, y, t, hue, breakBefore)
        } else {
            pointPool.removeLast().also {
                it.x = x
                it.y = y
                it.t = t
                it.hue = hue
                it.breakBefore = breakBefore
            }
        }
        trail.addLast(point)
    }

    fun bumpHue(stream: StreamId) {
        hues[stream.ordinal] = (hues[stream.ordinal] + HUE_STEP) % 360f
    }

    /** Drops expired points; returns true while anything is still visible. */
    fun prune(now: Long): Boolean {
        var alive = false
        for (trail in trails) {
            while (trail.isNotEmpty() && now - trail.first().t > TRAIL_LIFE_MS) {
                recycle(trail.removeFirst())
            }
            if (trail.isNotEmpty()) alive = true
        }
        return alive
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
            var prev: TrailPoint? = null
            for (p in trail) {
                val a = prev
                prev = p
                if (a == null || p.breakBefore) continue
                val age = (now - p.t).coerceAtLeast(0)
                val f = 1f - age / TRAIL_LIFE_MS.toFloat()
                if (f <= 0f) continue
                hsv[0] = p.hue
                hsv[1] = 0.85f
                hsv[2] = 1f
                paint.color = Color.HSVToColor((200 * f).toInt(), hsv)
                paint.strokeWidth = (3f + 7f * f) * density
                canvas.drawLine(a.x, a.y, p.x, p.y, paint)
            }
        }
    }

    private companion object {
        const val TRAIL_LIFE_MS = 250L
        const val MIN_SAMPLE_INTERVAL_MS = 8L
        const val MAX_POOLED_POINTS = 128
        const val HUE_STEP = 30f
    }
}
