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

    private class TrailPoint(val x: Float, val y: Float, val t: Long, val hue: Float)

    private val trails = arrayOf(ArrayDeque<TrailPoint>(), ArrayDeque<TrailPoint>())
    private val hues = floatArrayOf(0f, 0f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val hsv = FloatArray(3)

    /** Base hue in degrees; the right stream starts offset by 60 deg. */
    var baseHue = 0f

    fun startStream(stream: StreamId) {
        hues[stream.ordinal] = (baseHue + if (stream == StreamId.RIGHT) 60f else 0f) % 360f
    }

    fun addPoint(stream: StreamId, x: Float, y: Float, t: Long) {
        trails[stream.ordinal].addLast(TrailPoint(x, y, t, hues[stream.ordinal]))
    }

    fun bumpHue(stream: StreamId) {
        hues[stream.ordinal] = (hues[stream.ordinal] + HUE_STEP) % 360f
    }

    /** Drops expired points; returns true while anything is still visible. */
    fun prune(now: Long): Boolean {
        var alive = false
        for (trail in trails) {
            while (trail.isNotEmpty() && now - trail.first().t > TRAIL_LIFE_MS) {
                trail.removeFirst()
            }
            if (trail.isNotEmpty()) alive = true
        }
        return alive
    }

    fun clear() {
        trails[0].clear()
        trails[1].clear()
    }

    fun draw(canvas: Canvas, now: Long) {
        for (trail in trails) {
            if (trail.size < 2) continue
            var prev: TrailPoint? = null
            for (p in trail) {
                val a = prev
                prev = p
                if (a == null) continue
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
        const val HUE_STEP = 30f
    }
}
