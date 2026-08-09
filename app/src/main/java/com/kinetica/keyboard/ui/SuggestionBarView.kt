package com.kinetica.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import com.kinetica.keyboard.engine.KineticaConstants

/**
 * Suggestion strip: one row of up to [MAX_ZONES] equal-width zones, each an
 * independently tappable full candidate word. Candidates beyond one row live
 * on further pages: a leftward swipe that starts at the bar's right edge
 * cycles pages, with position dots at the bottom center while
 * more than one page exists. The same layout serves both phases of a word's
 * life:
 *
 *  - composition mode: ranked candidates for the word in progress, best first
 *    (bold); tapping a zone commits that word, a fast upward flick commits
 *    without waiting for the tap to settle;
 *  - correction mode (after a commit): the committed word (highlighted chip)
 *    plus the alternatives it beat; tapping any other zone replaces the last
 *    committed word in the editor directly.
 *
 * Long-pressing any zone in either mode arms weight adjustment without
 * committing: releasing in place reinforces the word (as a plain long-press
 * always did); sliding up while held increases the personal weight further,
 * sliding down decreases it, one increment per REINFORCE_STEP_DP of travel,
 * with the tier badge previewing the pending level live. The delta is applied
 * only on lift.
 */
class SuggestionBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onSuggestionPicked(word: String)

        /** User tapped a correction-mode zone: replace the last committed word. */
        fun onCorrectionPicked(replacement: String)

        /**
         * Long-press adjustment finished: apply the signed personal-weight
         * [delta] (already scaled by the configured increment), no commit.
         */
        fun onSuggestionReinforced(word: String, delta: Int)

        /** One tier step crossed during a weight-adjust slide (haptic hook). */
        fun onReinforceStep()
    }

    /**
     * One candidate zone. [tier] is the personal-weight badge level 0..7:
     * 0 draws nothing, 1 a center dot, 2..7 add hexagon-corner dots. [count]
     * is the raw personal count behind the tier, needed to preview the badge
     * live while a weight-adjust slide is in progress.
     */
    data class Suggestion(val word: String, val tier: Int, val count: Int = 0)

    var listener: Listener? = null

    /**
     * Whether a fast upward flick on a zone commits that word. KineticaIME
     * turns this on when it builds the bar; it stays a field so the gesture
     * can be suppressed wholesale, and it is ignored in correction mode.
     */
    var flickEnabled = false

    /** Configured manual boost magnitude; one slide step applies +/- this. */
    var reinforceIncrement = 1

    private var words: List<Suggestion> = emptyList()
    private var correctionMode = false
    private var selectedIndex = -1
    private var page = 0
    private var downZone = -1
    private var downX = 0f
    private var pageSwipeCandidate = false
    private var pageSwipeConsumed = false
    private var lastTouchY = 0f
    private var adjustArmed = false
    private var adjustZone = -1
    private var adjustStartY = 0f
    private var adjustSteps = 0
    private var velocityTracker: VelocityTracker? = null
    private val density = resources.displayMetrics.density
    private val zoneRect = RectF()
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val reinforceRunnable = Runnable {
        val zone = downZone
        if (zone != -1 && wordAt(zone) != null) {
            // Arm adjustment; the touch is consumed either way (lifting must
            // not also commit). The delta is applied on lift, not here.
            adjustArmed = true
            adjustZone = zone
            adjustStartY = lastTouchY
            adjustSteps = 0
            recycleTracker()
            invalidate()
        }
    }

    /**
     * Signed weight delta for the current slide. Releasing without sliding
     * keeps the historical plain-long-press behavior (+1 increment); each
     * upward step adds another increment, each downward step subtracts one -
     * so the first move in either direction already changes the preview.
     */
    private fun adjustDelta(steps: Int): Int =
        if (steps >= 0) (steps + 1) * reinforceIncrement else steps * reinforceIncrement

    private val bgPaint = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val primaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dividerPaint = Paint()
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Resolved color roles; the setter restains every paint and redraws. */
    var theme: KeyboardTheme = KeyboardTheme.fromResources(context)
        set(value) {
            field = value
            applyThemePaints()
            invalidate()
        }

    init {
        applyThemePaints()
    }

    private fun applyThemePaints() {
        bgPaint.color = theme.suggestionBg
        textPaint.color = theme.suggestionText
        primaryPaint.color = theme.suggestionPrimary
        chipPaint.color = theme.chip
        dividerPaint.color = theme.keyHint
        dividerPaint.alpha = 60
        badgePaint.color = theme.accent
    }

    /** Composition mode: ranked candidates for the word in progress. */
    fun setSuggestions(candidates: List<Suggestion>) {
        words = candidates
        correctionMode = false
        selectedIndex = -1
        page = 0
        invalidate()
    }

    fun clearSuggestions() {
        if (!correctionMode && words.isNotEmpty()) {
            words = emptyList()
            page = 0
            invalidate()
        }
    }

    /**
     * Correction mode: the committed word at [selected] plus the alternatives
     * it beat, all as equally tappable zones. Pages exactly like composition
     * mode - it is the same zone code.
     */
    fun showCorrection(candidates: List<Suggestion>, selected: Int) {
        words = candidates
        correctionMode = true
        selectedIndex = selected.coerceIn(0, (words.size - 1).coerceAtLeast(0))
        page = 0
        invalidate()
    }

    fun clearCorrection() {
        if (correctionMode) {
            words = emptyList()
            correctionMode = false
            selectedIndex = -1
            page = 0
            invalidate()
        }
    }

    private fun pageCount(): Int =
        if (words.isEmpty()) 0 else (words.size + MAX_ZONES - 1) / MAX_ZONES

    /** The page's slice of [words]; zone indices are relative to this. */
    private fun visible(): List<Suggestion> = words.drop(page * MAX_ZONES).take(MAX_ZONES)

    private fun wordAt(zone: Int): Suggestion? =
        if (zone < 0) null else words.getOrNull(page * MAX_ZONES + zone)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        val vis = visible()
        if (vis.isEmpty()) return

        textPaint.textSize = h * 0.40f
        primaryPaint.textSize = h * 0.40f
        val baseY = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        val zoneW = w / vis.size

        for (i in vis.indices) {
            val s = vis[i]
            val fullIdx = page * MAX_ZONES + i
            val left = i * zoneW
            val cx = left + zoneW / 2f
            // The bold "best" emphasis belongs to the overall top candidate /
            // the committed word, wherever the current page puts it.
            val emphasized = if (correctionMode) fullIdx == selectedIndex else fullIdx == 0
            if (correctionMode && fullIdx == selectedIndex) {
                val pad = 4f * density
                zoneRect.set(left + pad, pad, left + zoneW - pad, h - pad)
                canvas.drawRoundRect(zoneRect, 6f * density, 6f * density, chipPaint)
            }
            val paint = if (emphasized) primaryPaint else textPaint
            val shown = fit(s.word, paint, zoneW)
            canvas.drawText(shown, cx, baseY, paint)
            // While a weight-adjust slide is armed on this zone, the badge
            // previews the tier the pending delta would produce.
            val tier = if (adjustArmed && i == adjustZone) {
                KineticaConstants.personalTier((s.count + adjustDelta(adjustSteps)).coerceAtLeast(0))
            } else {
                s.tier
            }
            if (tier > 0) {
                drawTierBadge(
                    canvas, tier,
                    cx + paint.measureText(shown) / 2f + 6f * density,
                    baseY + paint.ascent() + 3f * density,
                )
            }
            if (i > 0) {
                canvas.drawRect(left - 1f, h * 0.2f, left + 1f, h * 0.8f, dividerPaint)
            }
        }

        // Page position dots (only when there is something to page to): the
        // affordance for the right-edge leftward swipe that cycles pages.
        val pages = pageCount()
        if (pages > 1) {
            val spacing = 8f * density
            val cy = h - 3.5f * density
            val startX = w / 2f - (pages - 1) * spacing / 2f
            for (p in 0 until pages) {
                badgePaint.alpha = if (p == page) 255 else 90
                canvas.drawCircle(startX + p * spacing, cy, 1.5f * density, badgePaint)
            }
            badgePaint.alpha = 255
        }
    }

    /**
     * Personal-weight badge riding a word's top-right: tier 1 is the center
     * dot, tiers 2..7 fill the six hexagon corners clockwise from the top.
     */
    private fun drawTierBadge(canvas: Canvas, tier: Int, cx: Float, cy: Float) {
        val r = 1.2f * density
        canvas.drawCircle(cx, cy, r, badgePaint)
        val ring = 3.2f * density
        val corners = (tier - 1).coerceAtMost(6)
        for (k in 0 until corners) {
            val rad = Math.toRadians(-90.0 + k * 60.0)
            canvas.drawCircle(
                cx + (ring * Math.cos(rad)).toFloat(),
                cy + (ring * Math.sin(rad)).toFloat(),
                r,
                badgePaint,
            )
        }
    }

    private fun fit(word: String, paint: Paint, maxW: Float): String {
        if (paint.measureText(word) <= maxW - 8f * density) return word
        var s = word
        while (s.length > 3 && paint.measureText("$s…") > maxW - 8f * density) {
            s = s.dropLast(1)
        }
        return "$s…"
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downZone = zoneAt(ev.x)
                downX = ev.x
                lastTouchY = ev.y
                resetAdjust()
                // A leftward swipe is a page flip only when it starts at the
                // bar's right edge and there is a page to go to.
                pageSwipeCandidate =
                    pageCount() > 1 && ev.x >= width - PAGE_EDGE_START_DP * density
                pageSwipeConsumed = false
                if (downZone != -1) {
                    longPressHandler.postDelayed(reinforceRunnable, REINFORCE_HOLD_MS)
                }
                if (flickEnabled && !correctionMode) {
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                lastTouchY = ev.y
                if (adjustArmed) {
                    // One tier step per fixed travel; truncation toward zero
                    // keeps a small wobble around the start position at step 0.
                    val steps = ((adjustStartY - ev.y) / (REINFORCE_STEP_DP * density)).toInt()
                    if (steps != adjustSteps) {
                        adjustSteps = steps
                        listener?.onReinforceStep()
                        invalidate()
                    }
                    return true
                }
                if (pageSwipeConsumed) return true
                if (pageSwipeCandidate && downX - ev.x >= PAGE_SWIPE_TRAVEL_DP * density) {
                    pageSwipeConsumed = true
                    longPressHandler.removeCallbacks(reinforceRunnable)
                    downZone = -1
                    recycleTracker()
                    page = (page + 1) % pageCount()
                    invalidate()
                    return true
                }
                velocityTracker?.addMovement(ev)
                if (flickEnabled && !correctionMode && downZone != -1) {
                    val vt = velocityTracker
                    if (vt != null) {
                        vt.computeCurrentVelocity(1000)
                        if (-vt.yVelocity > FLICK_VELOCITY_DP_S * density) {
                            longPressHandler.removeCallbacks(reinforceRunnable)
                            wordAt(downZone)?.let { listener?.onSuggestionPicked(it.word) }
                            downZone = -1
                            recycleTracker()
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                longPressHandler.removeCallbacks(reinforceRunnable)
                if (adjustArmed) {
                    wordAt(adjustZone)?.let {
                        listener?.onSuggestionReinforced(it.word, adjustDelta(adjustSteps))
                    }
                    resetAdjust()
                    downZone = -1
                    recycleTracker()
                    invalidate()
                    return true
                }
                if (pageSwipeConsumed) {
                    pageSwipeConsumed = false
                    downZone = -1
                    recycleTracker()
                    return true
                }
                velocityTracker?.addMovement(ev)
                val zone = zoneAt(ev.x)
                if (zone != -1 && zone == downZone) {
                    wordAt(zone)?.let { s ->
                        val fullIdx = page * MAX_ZONES + zone
                        if (correctionMode) {
                            if (fullIdx != selectedIndex) {
                                selectedIndex = fullIdx
                                invalidate()
                                listener?.onCorrectionPicked(s.word)
                            }
                        } else {
                            listener?.onSuggestionPicked(s.word)
                        }
                        performClick()
                    }
                }
                downZone = -1
                recycleTracker()
            }
            MotionEvent.ACTION_CANCEL -> {
                longPressHandler.removeCallbacks(reinforceRunnable)
                resetAdjust()
                pageSwipeConsumed = false
                downZone = -1
                recycleTracker()
                invalidate()
            }
        }
        return true
    }

    private fun resetAdjust() {
        adjustArmed = false
        adjustZone = -1
        adjustSteps = 0
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun recycleTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun zoneAt(x: Float): Int {
        val vis = visible()
        if (vis.isEmpty() || width <= 0) return -1
        return (x / (width.toFloat() / vis.size)).toInt().coerceIn(0, vis.size - 1)
    }

    private companion object {
        const val MAX_ZONES = 5
        const val FLICK_VELOCITY_DP_S = 800f
        // Between the keyboard's 300ms long-press floor and its 700ms
        // ceiling: slow enough not to swallow taps, fast enough to feel like
        // the same gesture family as the key popups.
        const val REINFORCE_HOLD_MS = 450L
        // Travel per weight-adjust step: about half a key height, so two or
        // three deliberate steps fit between the bar and the top key row
        // without the thumb leaving the keyboard area. May need on-device
        // tuning against real thumb travel.
        const val REINFORCE_STEP_DP = 24f
        // Page flip: start zone at the bar's right edge (wide enough to hit
        // blind, narrow enough to keep most of the last zone tappable), travel
        // mirroring EdgeSwipeDetector's MIN_TRAVEL_DP so the two edge gestures
        // feel like one family.
        const val PAGE_EDGE_START_DP = 36f
        const val PAGE_SWIPE_TRAVEL_DP = 30f
    }
}
