package com.kinetica.keyboard.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * White radial bursts on key contact: radius 12 -> 28 dp, alpha 255 -> 0 over
 * 80 ms. Bursts are cheap circles; a handful at most are ever alive.
 */
class BurstRenderer(private val density: Float) {

    private class Burst(val x: Float, val y: Float, val t0: Long)

    private val bursts = ArrayList<Burst>(8)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    fun spawn(x: Float, y: Float, t: Long) {
        bursts.add(Burst(x, y, t))
    }

    /** Drops finished bursts; returns true while any is still animating. */
    fun prune(now: Long): Boolean {
        bursts.removeAll { now - it.t0 >= LIFE_MS }
        return bursts.isNotEmpty()
    }

    fun clear() = bursts.clear()

    fun draw(canvas: Canvas, now: Long) {
        for (b in bursts) {
            val f = ((now - b.t0).coerceAtLeast(0)) / LIFE_MS.toFloat()
            if (f >= 1f) continue
            paint.alpha = (255 * (1f - f)).toInt()
            val radius = (12f + 16f * f) * density
            canvas.drawCircle(b.x, b.y, radius, paint)
        }
    }

    private companion object {
        const val LIFE_MS = 80L
    }
}
