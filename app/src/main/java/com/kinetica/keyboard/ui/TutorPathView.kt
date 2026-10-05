package com.kinetica.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.kinetica.keyboard.layout.Key

/**
 * The gesture tutor's board: the letter keys with a word's strokes drawn over them, one colour
 * per thumb, numbered in the order they are written; a tap is a ring.
 */
class TutorPathView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** A stroke as the letter centres it passes, in 0..1 board units, and its thumb. */
    class Stroke(val left: Boolean, val points: List<Pair<Float, Float>>)

    var keys: List<Key> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var strokes: List<Stroke> = emptyList()
        set(value) {
            field = value
            animationStart = android.os.SystemClock.uptimeMillis()
            invalidate()
        }

    private var animationStart = 0L

    var theme: KeyboardTheme = KeyboardTheme.fromResources(context)
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val t = theme
        fill.color = t.background
        canvas.drawRect(0f, 0f, w, h, fill)
        val inset = 1.5f * density
        for (k in keys) {
            if (!k.isLetter) continue
            rect.set(k.x * w + inset, k.y * h + inset, (k.x + k.w) * w - inset, (k.y + k.h) * h - inset)
            fill.color = t.key
            canvas.drawRoundRect(rect, 3f * density, 3f * density, fill)
        }
        // The strokes go under the letters and faint, so the path never covers what it points at.
        stroke.strokeWidth = 4f * density
        for (s in strokes) {
            if (s.points.size < 2) continue
            stroke.color = if (s.left) t.accent else t.suggestionPrimary
            stroke.alpha = STROKE_ALPHA
            path.reset()
            path.moveTo(s.points[0].first * w, s.points[0].second * h)
            for (p in s.points.drop(1)) path.lineTo(p.first * w, p.second * h)
            canvas.drawPath(path, stroke)
        }
        label.textSize = h * 0.09f
        label.color = t.keyText
        for (k in keys) {
            if (!k.isLetter) continue
            rect.set(k.x * w, k.y * h, (k.x + k.w) * w, (k.y + k.h) * h)
            canvas.drawText(k.label, rect.centerX(), rect.centerY() - (label.descent() + label.ascent()) / 2f, label)
        }
        drawPen(canvas, w, h)
        label.textSize = 9f * density
        val off = BADGE_OFFSET_DP * density
        for ((i, s) in strokes.withIndex()) {
            if (s.points.isEmpty()) continue
            val color = if (s.left) t.accent else t.suggestionPrimary
            stroke.color = color
            stroke.alpha = 200
            if (s.points.size == 1) {
                stroke.alpha = STROKE_ALPHA
                canvas.drawCircle(s.points[0].first * w, s.points[0].second * h, 9f * density, stroke)
            }
            // The order the strokes are written in, beside each one's first key, not on it.
            val sx = s.points[0].first * w - off
            val sy = s.points[0].second * h - off
            fill.color = color
            canvas.drawCircle(sx, sy, 6f * density, fill)
            label.color = t.background
            canvas.drawText("${i + 1}", sx, sy - (label.descent() + label.ascent()) / 2f, label)
        }
    }

    /**
     * A dot that writes the word: along each stroke in order, then a pause, then again, so the
     * sequence reads without following the numbers.
     */
    private fun drawPen(canvas: Canvas, w: Float, h: Float) {
        val live = strokes.filter { it.points.isNotEmpty() }
        if (live.isEmpty()) return
        val per = live.map { s -> maxOf(1, s.points.size - 1) * LEG_MS }
        val total = per.sum() + PAUSE_MS
        var at = (android.os.SystemClock.uptimeMillis() - animationStart) % total
        for ((i, s) in live.withIndex()) {
            if (at >= per[i]) {
                at -= per[i]
                continue
            }
            val legs = s.points.size - 1
            val (x, y) = if (legs <= 0) {
                s.points[0]
            } else {
                val f = at.toFloat() / LEG_MS
                val leg = minOf(f.toInt(), legs - 1)
                val t = f - leg
                val a = s.points[leg]
                val b = s.points[leg + 1]
                (a.first + (b.first - a.first) * t) to (a.second + (b.second - a.second) * t)
            }
            fill.color = if (s.left) theme.accent else theme.suggestionPrimary
            canvas.drawCircle(x * w, y * h, 6f * density, fill)
            break
        }
        postInvalidateOnAnimation()
    }

    private companion object {
        /** Time the pen takes from one letter to the next. */
        const val LEG_MS = 380L

        /** The rest between two writings of the word. */
        const val PAUSE_MS = 900L

        /** Faint enough that a letter reads through its stroke. */
        const val STROKE_ALPHA = 90

        /** How far up and left of a key's centre its order badge sits. */
        const val BADGE_OFFSET_DP = 11f
    }
}
