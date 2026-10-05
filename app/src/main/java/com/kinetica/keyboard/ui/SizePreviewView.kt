package com.kinetica.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.kinetica.keyboard.layout.Key
import com.kinetica.keyboard.layout.LandscapeArrangement
import com.kinetica.keyboard.layout.LayoutMode
import com.kinetica.keyboard.layout.LayoutTransforms

/**
 * The keyboard at the size the settings give it, drawn across the view's full width with
 * a band of the app above it: the bar, the resize handle, the real alpha layout placed by the
 * same mode transform the keyboard uses (split and one-handed included) with its side margin,
 * and the bottom gap, in proportion. Sizes come from the service's own arithmetic.
 */
class SizePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Everything in the phone's own pixels; the view scales it to its width. */
    class Spec(
        val screenW: Float,
        val screenH: Float,
        val barPx: Float,
        val handlePx: Float,
        val boardPx: Float,
        val bottomPx: Float,
        val sidePadPx: Float,
        val keys: List<Key>,
        val theme: KeyboardTheme,
        val mode: LayoutMode = LayoutMode.FULL,
        /** The landscape arrangement, for the landscape picture; null draws the board as in portrait. */
        val landscapeArrangement: LandscapeArrangement? = null,
        val splitGapPct: Int = 50,
    )

    var spec: Spec? = null
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()

    /** As tall as the keyboard is at this width, plus the app's band, so nothing is shrunk twice. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val s = spec
        val h = if (s == null || s.screenW <= 0f) {
            (APP_BAND_DP * 3 * density).toInt()
        } else {
            ((s.barPx + s.handlePx + s.boardPx + s.bottomPx) * w / s.screenW + APP_BAND_DP * density).toInt()
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val s = spec ?: return
        if (width <= 0 || height <= 0 || s.screenW <= 0f) return
        val t = s.theme
        val scale = width / s.screenW
        val w = width.toFloat()
        val band = APP_BAND_DP * density
        val corner = 8f * density

        // The app, a band above the keyboard.
        rect.set(0f, 0f, w, band)
        fill.color = t.suggestionBg
        fill.alpha = 70
        canvas.drawRoundRect(rect, corner, corner, fill)
        fill.alpha = 255

        val barTop = band
        val handleTop = barTop + s.barPx * scale
        val boardTop = handleTop + s.handlePx * scale
        fill.color = t.background
        rect.set(0f, barTop, w, height.toFloat())
        canvas.drawRect(rect, fill)
        fill.color = t.suggestionBg
        rect.set(0f, barTop, w, handleTop)
        canvas.drawRect(rect, fill)

        val inset = 0.8f * density
        // The branch KeyboardView.rebuild takes: an arrangement only on the full board.
        val letter = s.keys.firstOrNull { it.isLetter }
        val arrangement = s.landscapeArrangement
            ?.takeIf { it != LandscapeArrangement.STRETCH && s.mode == LayoutMode.FULL }
        val minKeyPx = LayoutTransforms.LANDSCAPE_MIN_KEY_DP * density
        val blockPx = when {
            arrangement == null || letter == null -> 0f
            arrangement == LandscapeArrangement.SPLIT -> LayoutTransforms.splitBlockPx(
                s.screenW, s.sidePadPx, s.splitGapPct / 100f, letter.w, minKeyPx,
            )
            else -> LayoutTransforms.landscapeBlockPx(s.screenW, s.boardPx, s.sidePadPx, letter.w, letter.h, minKeyPx)
        }
        for (k in s.keys) {
            val r = if (arrangement != null && blockPx > 0f) {
                LayoutTransforms.applyLandscape(k, s.screenW, s.boardPx, s.sidePadPx, arrangement, blockPx)
            } else {
                LayoutTransforms.apply(s.mode, k, s.screenW, s.boardPx, s.sidePadPx)
            }
            rect.set(r.left * scale + inset, boardTop + r.top * scale + inset, r.right * scale - inset, boardTop + r.bottom * scale - inset)
            if (rect.width() <= 0f || rect.height() <= 0f) continue
            fill.color = if (k.isLetter) t.key else t.keySpecial
            canvas.drawRoundRect(rect, 2f * density, 2f * density, fill)
        }

        line.color = t.keyHint
        line.strokeWidth = density
        rect.set(0f, 0f, w, height.toFloat())
        canvas.drawRoundRect(rect, corner, corner, line)
    }

    private companion object {
        /** The strip of app shown above the keyboard, for scale. */
        const val APP_BAND_DP = 28f
    }
}
