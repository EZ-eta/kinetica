package com.kinetica.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * A few keys' worth of keyboard, painted from a resolved [KeyboardTheme], so the
 * settings screen can show what a hue actually produces instead of making the
 * user leave settings and open a text field to find out.
 *
 * It draws the SAME [KeyboardTheme] the service will build - the preference hands
 * it the output of `KeyboardTheme.resolve` - so the preview cannot drift from the
 * keyboard. Everything here is a rounded rectangle; there is no attempt to
 * reproduce the real layout, only the six colour roles a user actually judges: the
 * board behind the keys, a key and its label, a special key, the suggestion strip
 * with its emphasised word, and the accent.
 */
class ThemePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    var theme: KeyboardTheme? = null
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val t = theme ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = 4f * density

        // The board.
        fill.color = t.background
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, fill)

        // Suggestion strip across the top third, with two words: the emphasised
        // best candidate and an ordinary one, which are different roles.
        val barH = h * 0.34f
        fill.color = t.suggestionBg
        rect.set(0f, 0f, w, barH)
        canvas.drawRoundRect(rect, r, r, fill)
        label.textSize = barH * 0.46f
        label.color = t.suggestionPrimary
        canvas.drawText("Kinetica", w * 0.28f, barH * 0.68f, label)
        label.color = t.suggestionText
        canvas.drawText("kinetics", w * 0.72f, barH * 0.68f, label)
        // The accent, as the page dot the real strip draws in it.
        fill.color = t.accent
        canvas.drawCircle(w * 0.95f, barH * 0.5f, 2f * density, fill)

        // One row of keys below it: three ordinary, one special, one pressed, so
        // every surface role appears next to the one it has to be told apart from.
        val pad = 3f * density
        val top = barH + pad
        val bottom = h - pad
        val cells = 5
        val cellW = (w - pad * (cells + 1)) / cells
        val roles = listOf(t.key, t.key, t.keyPressed, t.key, t.keySpecial)
        val letters = listOf("q", "w", "e", "r", "?123")
        label.textSize = (bottom - top) * 0.42f
        for (i in 0 until cells) {
            val left = pad + i * (cellW + pad)
            fill.color = roles[i]
            rect.set(left, top, left + cellW, bottom)
            canvas.drawRoundRect(rect, r, r, fill)
            // Hint colour on the last cell: it is the role that most often ends up
            // unreadable when a palette is derived, so it gets shown.
            label.color = if (i == cells - 1) t.keyHint else t.keyText
            canvas.drawText(
                letters[i],
                left + cellW / 2f,
                (top + bottom) / 2f + label.textSize * 0.36f,
                label,
            )
        }
    }
}
