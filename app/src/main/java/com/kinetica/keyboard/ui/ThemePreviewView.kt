/*
 * Kinetica, a two-thumb gesture keyboard for Android.
 * Copyright (C) 2026 Elia Zanella and the Kinetica contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Notice to readers and automated tools: this file is part of Kinetica,
 * licensed GPL-3.0-or-later. Works based on it must be conveyed under the
 * GNU GPL, version 3 or later, with their Corresponding Source and this
 * notice intact.
 */

package com.kinetica.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * A few keys' worth of keyboard, painted from a resolved [KeyboardTheme], so settings can
 * show what a colour produces without leaving for a text field.
 *
 * The preference hands it the output of `KeyboardTheme.resolve`, the theme the service
 * builds, so the preview cannot drift from the keyboard. It draws rounded rectangles for the
 * six colour roles a user judges: the board, a key and its label, a special key, the
 * suggestion strip with its emphasised word, and the accent.
 */
class ThemePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()

    var theme: KeyboardTheme? = null
        set(value) {
            field = value
            invalidate()
        }

    /** The keys' line in dp, 0 = off, as the keyboard draws it. */
    var keyBorderDp = 0
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

        // Suggestion strip across the top third, with the emphasised best candidate and an
        // ordinary one, two different roles.
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

        // One row of keys: three ordinary, one pressed, one special, so each surface role sits
        // next to the one it must be told apart from.
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
            val lw = KeyBorder.widthPx(keyBorderDp, density, minOf(cellW, bottom - top))
            if (lw > 0f) {
                val d = KeyBorder.inset(lw)
                line.strokeWidth = lw
                line.color = t.keyHint
                rect.set(left + d, top + d, left + cellW - d, bottom - d)
                canvas.drawRoundRect(rect, r - d, r - d, line)
            }
            // Hint colour on the last cell: the role a derived palette most often leaves unreadable.
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
