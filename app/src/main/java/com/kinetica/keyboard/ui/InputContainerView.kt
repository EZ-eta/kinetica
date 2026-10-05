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
 * Vertical stack: drag handle, suggestion bar, keyboard, bottom gap. The handle resizes the
 * keyboard live during the drag and reports the final height on release for persistence.
 */
class InputContainerView(
    context: Context,
    val suggestionBar: SuggestionBarView,
    val keyboardView: KeyboardView,
    barHeightPx: Int,
    keyboardHeightPx: Int,
    /**
     * Height of the resize handle strip, in px, zero for none; at zero the keyboard is resized
     * from Settings. The strip is added whatever the value, so a change applies to the live
     * view like the bar's height without rebuilding the input view; at zero it takes no touches.
     */
    handleHeightPx: Int,
    minKeyboardPx: Int,
    maxKeyboardPx: Int,
    bottomGapPx: Int,
    private val onHeightCommitted: (px: Int) -> Unit,
) : LinearLayout(context) {

    // The bounds come from screen percentages and a dp floor, so a short screen can invert
    // them, and coerceIn over an empty range throws. Normalized once so no caller can crash
    // the IME.
    private var minKeyboardPx = minOf(minKeyboardPx, maxKeyboardPx)
    private var maxKeyboardPx = maxOf(minKeyboardPx, maxKeyboardPx)

    /** New drag bounds, as the numbers row changes them; normalized the same way. */
    fun setHeightBounds(min: Int, max: Int) {
        minKeyboardPx = minOf(min, max)
        maxKeyboardPx = maxOf(min, max)
    }

    private val handle = HandleView(context)
    // Plain, showing the window background: empty space below the keys, not chrome.
    private val bottomGap = View(context)
    private var dragStartRawY = 0f
    private var dragStartHeight = 0

    init {
        orientation = VERTICAL
        addView(
            handle,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, handleHeightPx.coerceAtLeast(0)),
        )
        addView(suggestionBar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barHeightPx))
        addView(
            keyboardView,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                keyboardHeightPx.coerceIn(minKeyboardPx, maxKeyboardPx),
            ),
        )
        addView(bottomGap, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bottomGapPx))
        bottomGap.visibility = if (bottomGapPx == 0) GONE else VISIBLE
        wireHandle()
    }

    /** Handle strip colors follow the active theme. */
    fun applyTheme(theme: KeyboardTheme) {
        handle.setColors(theme.suggestionBg, theme.keyHint)
    }

    /**
     * Height of the gap below the keyboard, in pixels.
     *
     * Adds to the container's height and takes nothing from the keys, which are lifted off the
     * bottom edge, not made smaller: key rects, `keyWidthPx` and every kw distance stay as they
     * are. A child view, like the handle strip, so it resizes live without a rebuild.
     */
    fun setBottomGap(px: Int) {
        val h = px.coerceAtLeast(0)
        if (bottomGap.layoutParams?.height == h) return
        bottomGap.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, h)
        bottomGap.visibility = if (h == 0) GONE else VISIBLE
        requestLayout()
    }

    /** Live resize of the handle strip; zero hides it. */
    fun setHandleHeight(px: Int) {
        val lp = handle.layoutParams ?: return
        val h = px.coerceAtLeast(0)
        if (lp.height != h) {
            lp.height = h
            handle.requestLayout()
        }
    }

    /** Live resize of the suggestion strip, mirroring the keyboard's own path. */
    fun setBarHeight(px: Int) {
        val lp = suggestionBar.layoutParams ?: return
        if (lp.height != px) {
            lp.height = px
            suggestionBar.requestLayout()
        }
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
