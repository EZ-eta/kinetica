package com.kinetica.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.kinetica.keyboard.R

/**
 * Vertical stack: drag handle, suggestion bar, keyboard. The handle resizes
 * the keyboard live during the drag (25-50% of screen height) and reports the
 * final height on release for persistence.
 */
class InputContainerView(
    context: Context,
    val suggestionBar: SuggestionBarView,
    val keyboardView: KeyboardView,
    barHeightPx: Int,
    keyboardHeightPx: Int,
    minKeyboardPx: Int,
    maxKeyboardPx: Int,
    private val onHeightCommitted: (px: Int) -> Unit,
) : LinearLayout(context) {

    // The bounds come from screen-percentage math and a dp floor; on short
    // screens the pair can arrive inverted, and coerceIn over an empty range
    // throws. Normalize once so no caller can crash the IME process.
    private val minKeyboardPx = minOf(minKeyboardPx, maxKeyboardPx)
    private val maxKeyboardPx = maxOf(minKeyboardPx, maxKeyboardPx)

    private val handle = HandleView(context)
    private var dragStartRawY = 0f
    private var dragStartHeight = 0

    init {
        orientation = VERTICAL
        val density = resources.displayMetrics.density
        addView(handle, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (20 * density).toInt()))
        addView(suggestionBar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barHeightPx))
        addView(
            keyboardView,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                keyboardHeightPx.coerceIn(minKeyboardPx, maxKeyboardPx),
            ),
        )
        wireHandle()
    }

    /** Handle strip colors follow the active theme. */
    fun applyTheme(theme: KeyboardTheme) {
        handle.setColors(theme.suggestionBg, theme.keyHint)
    }

    /** Swaps the keyboard for the emoji picker at the keyboard's height. */
    fun showEmojiPicker(picker: View) {
        if (picker.parent == null) {
            addView(
                picker,
                indexOfChild(keyboardView),
                LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    keyboardView.layoutParams.height,
                ),
            )
        } else {
            picker.layoutParams.height = keyboardView.layoutParams.height
        }
        picker.visibility = VISIBLE
        keyboardView.visibility = GONE
    }

    fun hideEmojiPicker(picker: View?) {
        picker?.visibility = GONE
        keyboardView.visibility = VISIBLE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun wireHandle() {
        handle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawY = ev.rawY
                    dragStartHeight = keyboardView.layoutParams.height
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val target = (dragStartHeight + (dragStartRawY - ev.rawY)).toInt()
                        .coerceIn(minKeyboardPx, maxKeyboardPx)
                    if (target != keyboardView.layoutParams.height) {
                        keyboardView.layoutParams.height = target
                        keyboardView.requestLayout()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onHeightCommitted(keyboardView.layoutParams.height)
                    true
                }
                else -> false
            }
        }
    }

    private class HandleView(context: Context) : View(context) {
        private val bgPaint = Paint().apply {
            color = ContextCompat.getColor(context, R.color.suggestion_bar_bg)
        }
        private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.kbd_key_hint)
        }

        fun setColors(bg: Int, pill: Int) {
            bgPaint.color = bg
            pillPaint.color = pill
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawRect(0f, 0f, w, h, bgPaint)
            val pillW = w * 0.12f
            val pillH = h * 0.25f
            canvas.drawRoundRect(
                (w - pillW) / 2f, (h - pillH) / 2f,
                (w + pillW) / 2f, (h + pillH) / 2f,
                pillH / 2f, pillH / 2f, pillPaint,
            )
        }
    }
}
