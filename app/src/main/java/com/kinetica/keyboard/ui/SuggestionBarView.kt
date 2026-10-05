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
import com.kinetica.keyboard.keys.ActionRow

/**
 * Suggestion strip: one row of equal-width zones, each a tappable candidate word. How many share
 * a row is [BarZones]' answer, from the words' measured widths up to [MAX_ZONES], so long
 * candidates get room instead of an ellipsis. Further candidates live on further pages: a
 * horizontal drag anywhere across the words pages in either direction, with position dots at the
 * bottom while there is more than one page.
 *
 *  - Composition mode: ranked candidates for the word in progress, best first (bold). A tap
 *    commits a word; a fast upward flick commits without waiting for the tap to settle.
 *  - Correction mode, after a commit: the committed word on a chip plus the alternatives it
 *    beat. Tapping another zone replaces the last committed word in the editor.
 *
 * Holding a zone in either mode arms weight adjustment without committing: sliding up raises the
 * personal weight and sliding down lowers it, one badge tier per [reinforceStepDp] of travel
 * (each step doubles or halves the count), with the badge previewing the pending tier. The delta
 * applies on lift. The middle cell of a recent-word column takes the same slide.
 *
 * Releasing in place reinforces in composition mode. In correction mode it picks: there the tap
 * is the primary action and a deliberate tap is slow, so the arm would steal it.
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
         * Long-press adjustment finished: apply the signed personal-weight [delta], already
         * scaled by the configured increment. No commit.
         */
        fun onSuggestionReinforced(word: String, delta: Int)

        /** One tier step crossed during a weight-adjust slide (haptic hook). */
        fun onReinforceStep()

        /** The reserved right-edge button: throw the current word away and start again. */
        fun onRetype()

        /**
         * A shortcut in the action row was tapped, by its index into what the bar was given.
         * Not the glyph: two actions could draw the same symbol, and the bar does not know what
         * any of them mean.
         */
        fun onBarAction(index: Int)

        /**
         * The weight slide travelled past the bottom of its scale: never offer [word] again.
         * Reversible from Dictionary settings, where the block list lives.
         */
        fun onSuggestionBlocked(word: String)

        /** A recent word's alternative was tapped: [back] commits before the newest, 0 the newest. */
        fun onRecentPicked(back: Int, word: String)
    }

    /**
     * One recent word on the bar and the two candidates it beat, drawn above and below it.
     * [back] is how many commits before the newest it is.
     */
    data class RecentColumn(
        val word: String,
        val above: String?,
        val below: String?,
        val back: Int,
        /** The personal counts behind [above], [word] and [below], for their badges and the slide. */
        val counts: List<Int> = listOf(0, 0, 0),
    )

    /**
     * One candidate zone. [tier] is the personal-weight badge level 0..7: 0 draws nothing, 1 a
     * center dot, 2..7 add hexagon-corner dots. [count] is the raw personal count behind the
     * tier, which the badge preview needs during a weight-adjust slide.
     */
    data class Suggestion(val word: String, val tier: Int, val count: Int = 0)

    var listener: Listener? = null

    /**
     * Whether a fast upward flick on a zone commits that word. KineticaIME turns it on when it
     * builds the bar; a field so the gesture can be suppressed wholesale. Ignored in correction
     * mode.
     */
    var flickEnabled = false

    /** Configured manual boost magnitude: what a release in place adds. */
    var reinforceIncrement = 1

    /** Travel per weight-slide step, the user's setting. */
    var reinforceStepDp = REINFORCE_STEP_DP

    /** Rank badges only on the word being slid; off shows every rank. */
    var badgesWhileAdjusting = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /**
     * Reserve the bar's right edge for a retype button, a small restart arrow. Off by default,
     * because it costs the words some width.
     */
    var retypeButton = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * Configured width of that button in dp. Adjustable because the default is hard to hit with
     * a phone case on.
     */
    var retypeButtonDp = BarMetrics.RETYPE_DEFAULT_DP
        set(value) {
            val clamped = BarMetrics.retypeDp(value)
            if (field == clamped) return
            field = clamped
            invalidate()
        }

    /** Recent words, oldest first, left of the live zones; empty turns the columns off. */
    var recent: List<RecentColumn> = emptyList()
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /**
     * How much taller than one row the bar is drawn, so recent words fit three to a column
     * while the live words keep the size the height setting gave them.
     */
    var tallFactor = 1f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private var words: List<Suggestion> = emptyList()
    private var correctionMode = false
    private var selectedIndex = -1
    private var page = 0
    private var downZone = -1
    private var downX = 0f
    private var downY = 0f
    private var pageSwipeCandidate = false
    private var recentDownCell = -1
    private var zoneKey = Int.MIN_VALUE

    /**
     * Shortcut glyphs to offer when there is nothing to suggest, already ordered and
     * filtered by the service. Empty turns the row off.
     */
    var actions: List<String> = emptyList()
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /**
     * Whether a word is being typed right now; the shortcut row is suppressed while it is. An
     * empty bar is common (every field entry, every cursor move, the stale timeout after a
     * failed swipe, a password field), so without this guard the row would appear mid-word on
     * every empty decode, under a thumb aiming at a suggestion.
     */
    var wordPending: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (words.isEmpty() && actions.isNotEmpty()) invalidate()
            }
        }
    private var pageSizes: List<Int> = emptyList()
    private var pageSwipeConsumed = false
    private var lastTouchY = 0f
    private var adjustArmed = false
    private var adjustZone = -1
    // The word the slide adjusts and its count, taken at the arm: a live candidate or a recent cell.
    private var adjustTarget: Suggestion? = null
    private var adjustCell = -1
    private var adjustStartY = 0f
    private var adjustSteps = 0
    private var velocityTracker: VelocityTracker? = null
    private val density = resources.displayMetrics.density
    private val zoneRect = RectF()
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val reinforceRunnable = Runnable {
        val zone = downZone
        // A recent column's cell arms too: with recent words on, the word just committed
        // lives only there.
        val target = when {
            zone >= 0 -> wordAt(zone)
            zone == ZONE_RECENT && BarAdjust.recentCellAdjustable(recentDownCell, RECENT_ROWS) ->
                recentCellSuggestion(recentDownCell)
            else -> null
        }
        if (target != null) {
            // Arm adjustment; the touch is consumed either way, so lifting does not also
            // commit. The delta is applied on lift.
            adjustArmed = true
            adjustZone = zone
            adjustTarget = target
            adjustCell = if (zone == ZONE_RECENT) recentDownCell else -1
            adjustStartY = lastTouchY
            adjustSteps = 0
            recycleTracker()
            invalidate()
        }
    }

    /** The word and count in recent cell [cell], or null for an empty one. */
    private fun recentCellSuggestion(cell: Int): Suggestion? {
        if (cell < 0) return null
        val col = recent.getOrNull(cell / RECENT_ROWS) ?: return null
        val r = cell % RECENT_ROWS
        val word = when (r) {
            0 -> col.above
            1 -> col.word
            else -> col.below
        } ?: return null
        val count = col.counts.getOrElse(r) { 0 }
        return Suggestion(word, KineticaConstants.personalTier(count), count)
    }

    /** Signed weight delta for the current slide on [count]. */
    private fun adjustDelta(count: Int, steps: Int): Int = BarAdjust.delta(count, steps, reinforceIncrement)

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

    /** Whether the bar shows exactly [candidates] as its composition words right now. */
    fun showsWords(candidates: List<String>): Boolean =
        !correctionMode && words.size == candidates.size && words.indices.all { words[it].word == candidates[it] }

    fun clearSuggestions() {
        if (!correctionMode && words.isNotEmpty()) {
            words = emptyList()
            page = 0
            invalidate()
        }
    }

    /**
     * Correction mode: the committed word at [selected] plus the alternatives it beat, all as
     * equally tappable zones. Pages like composition mode, through the same zone code.
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

    /**
     * Page sizes for the current words at the current size, recomputed only when one of those
     * changes, so neither onDraw nor onTouchEvent measures text on every frame or move event.
     */
    private fun pageSizes(): List<Int> {
        // Everything that changes wordsWidth is in the key, the retype button included, or
        // the partition would stay sized for the old width.
        val key = words.hashCode() * 31 * 31 + width * 31 + height +
            (if (retypeButton) 7919 else 0) + retypeButtonDp + recent.size * 131 + (tallFactor * 100).toInt()
        if (key != zoneKey) {
            zoneKey = key
            pageSizes = BarZones.pages(zoneWidths(), wordsWidth(), MAX_ZONES)
        }
        return pageSizes
    }

    /**
     * What each word needs of its zone: its drawn width, the padding [fit] reserves, and for a
     * badged word the badge's reach, counted on both sides because the text is centred. Measured
     * with the bold paint, the widest a word is ever drawn.
     */
    private fun zoneWidths(): List<Float> {
        val h = rowHeight()
        val orn = BarMetrics.scale(h, density)
        primaryPaint.textSize = BarMetrics.textSize(h)
        val pad = TEXT_INSET_DP * density * orn
        val badge = 2f * (BADGE_GAP_DP + BADGE_REACH_DP) * density * orn
        return words.map {
            primaryPaint.measureText(it.word) + pad + if (it.tier > 0) badge else 0f
        }
    }

    /**
     * Whether the shortcut row is on screen. Correction mode counts as content: the strip is a
     * live offer and the row must not cover it.
     */
    private fun actionsVisible(): Boolean =
        actions.isNotEmpty() && words.isEmpty() && !correctionMode && !wordPending

    /** Cells drawn: as many as a thumb-sized cell leaves room for. */
    private fun visibleActions(): List<String> {
        if (!actionsVisible()) return emptyList()
        val fit = ActionRow.cellsThatFit(wordsWidth(), BarMetrics.RETYPE_MIN_DP * density)
        return actions.take(fit)
    }

    private fun pageCount(): Int = pageSizes().size

    private fun pageStart(): Int = BarZones.startOfPage(pageSizes(), page)

    /** The page's slice of [words]; zone indices are relative to this. */
    private fun visible(): List<Suggestion> {
        val sizes = pageSizes()
        if (sizes.isEmpty()) return emptyList()
        val p = page.coerceIn(0, sizes.size - 1)
        val start = BarZones.startOfPage(sizes, p)
        return words.subList(start, start + sizes[p])
    }

    private fun wordAt(zone: Int): Suggestion? =
        if (zone < 0) null else words.getOrNull(pageStart() + zone)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        drawRecent(canvas, h)
        val vis = visible()
        // Ornaments scale with the bar so a thinned bar shrinks them instead of overlapping the
        // word; 1.0 at BarMetrics.REFERENCE_DP, the default 44dp look.
        val rh = rowHeight()
        val orn = BarMetrics.scale(rh, density)
        textPaint.textSize = BarMetrics.textSize(rh)
        primaryPaint.textSize = BarMetrics.textSize(rh)
        val baseY = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        // Drawn before the early return: an empty bar is when a botched word most needs
        // restarting.
        if (retypeButton) {
            val bw = retypeWidth()
            canvas.drawText(RETYPE_GLYPH, w - bw / 2f, baseY, textPaint)
            canvas.drawRect(w - bw - 1f, h * 0.2f, w - bw + 1f, h * 0.8f, dividerPaint)
        }
        // Everything from here is laid out from the left edge of the live words.
        canvas.save()
        canvas.translate(wordsLeft(), 0f)
        drawWords(canvas, h, vis, orn, baseY)
        canvas.restore()
    }

    private fun drawWords(canvas: Canvas, h: Float, vis: List<Suggestion>, orn: Float, baseY: Float) {
        // Painted before the return below: an empty bar is the state this row exists for.
        val acts = visibleActions()
        if (acts.isNotEmpty()) {
            val cellW = wordsWidth() / acts.size
            for (i in acts.indices) {
                canvas.drawText(acts[i], cellW * (i + 0.5f), baseY, textPaint)
                if (i > 0) {
                    canvas.drawRect(
                        cellW * i - 1f, h * 0.2f, cellW * i + 1f, h * 0.8f, dividerPaint,
                    )
                }
            }
        }
        if (vis.isEmpty()) return
        val zoneW = wordsWidth() / vis.size

        for (i in vis.indices) {
            val s = vis[i]
            val fullIdx = pageStart() + i
            val left = i * zoneW
            val cx = left + zoneW / 2f
            // Bold goes to the overall top candidate, or the committed word, on whatever page it
            // falls.
            val emphasized = if (correctionMode) fullIdx == selectedIndex else fullIdx == 0
            if (correctionMode && fullIdx == selectedIndex) {
                val pad = 4f * density * orn
                zoneRect.set(left + pad, pad, left + zoneW - pad, h - pad)
                val corner = 6f * density * orn
                canvas.drawRoundRect(zoneRect, corner, corner, chipPaint)
            }
            val paint = if (emphasized) primaryPaint else textPaint
            val shown = fit(s.word, paint, zoneW)
            canvas.drawText(shown, cx, baseY, paint)
            // While a slide is armed on this zone, the badge previews the pending tier.
            val blockArmed = adjustArmed && i == adjustZone &&
                BarAdjust.blockArmed(s.count, adjustSteps)
            val tier = if (adjustArmed && i == adjustZone) {
                KineticaConstants.personalTier(
                    BarAdjust.effectiveCount(s.count, adjustSteps, reinforceIncrement),
                )
            } else {
                s.tier
            }
            val badgeX = cx + paint.measureText(shown) / 2f + BADGE_GAP_DP * density * orn
            val badgeY = baseY + paint.ascent() + 3f * density * orn
            if (blockArmed) {
                // Struck through and marked, so the pending block reads under the thumb. The bar
                // has no popups and the IME shows no toast, so the drawing is the confirmation;
                // sliding back up disarms it.
                val half = paint.measureText(shown) / 2f
                canvas.drawRect(
                    cx - half, baseY + paint.ascent() / 2.4f,
                    cx + half, baseY + paint.ascent() / 2.4f + 1.6f * density * orn,
                    badgePaint,
                )
                drawBlockMark(canvas, orn, badgeX, badgeY)
            } else if (tier > 0 && BarAdjust.badgeShown(badgesWhileAdjusting, adjustArmed && i == adjustZone)) {
                drawTierBadge(canvas, tier, orn, badgeX, badgeY)
            }
            if (i > 0) {
                canvas.drawRect(left - 1f, h * 0.2f, left + 1f, h * 0.8f, dividerPaint)
            }
        }

        // Page dots, only when there is a page to go to: the affordance for the horizontal drag,
        // centred because the drag may start anywhere across the words.
        val pages = pageCount()
        if (pages > 1) {
            val spacing = 8f * density * orn
            val cy = h - 3.5f * density * orn
            val startX = wordsWidth() / 2f - (pages - 1) * spacing / 2f
            for (p in 0 until pages) {
                badgePaint.alpha = if (p == page) 255 else 90
                canvas.drawCircle(startX + p * spacing, cy, 1.5f * density * orn, badgePaint)
            }
            badgePaint.alpha = 255
        }
    }

    /**
     * The recent words, each in its column with the candidate it beat above and the next one
     * below. The word itself sits on the chip, as the committed word does in correction mode.
     */
    private fun drawRecent(canvas: Canvas, h: Float) {
        if (recent.isEmpty()) return
        val colW = recentColumnWidth()
        val rowH = h / RECENT_ROWS
        val size = BarMetrics.textSize(rowH) * RECENT_TEXT_SCALE
        textPaint.textSize = size
        primaryPaint.textSize = size
        val orn = BarMetrics.scale(rowHeight(), density)
        val pad = 3f * density * orn
        val corner = 6f * density * orn
        for ((c, col) in recent.withIndex()) {
            val left = c * colW
            val cx = left + colW / 2f
            val cells = arrayOf(col.above, col.word, col.below)
            for (r in 0 until RECENT_ROWS) {
                val word = cells[r] ?: continue
                val top = r * rowH
                val paint = if (r == 1) primaryPaint else textPaint
                if (r == 1) {
                    zoneRect.set(left + pad, top + pad, left + colW - pad, top + rowH - pad)
                    canvas.drawRoundRect(zoneRect, corner, corner, chipPaint)
                }
                val y = top + rowH / 2f - (paint.descent() + paint.ascent()) / 2f
                val shown = fit(word, paint, colW)
                canvas.drawText(shown, cx, y, paint)
                // The badge previews the slide while this cell is held.
                val cell = c * RECENT_ROWS + r
                val count = col.counts.getOrElse(r) { 0 }
                val held = adjustArmed && adjustZone == ZONE_RECENT && adjustCell == cell
                val badgeX = cx + paint.measureText(shown) / 2f + BADGE_GAP_DP * density * orn
                val badgeY = y + paint.ascent() + 3f * density * orn
                if (held && BarAdjust.blockArmed(count, adjustSteps)) {
                    drawBlockMark(canvas, orn, badgeX, badgeY)
                } else {
                    val tier = KineticaConstants.personalTier(
                        if (held) BarAdjust.effectiveCount(count, adjustSteps, reinforceIncrement) else count,
                    )
                    if (tier > 0 && BarAdjust.badgeShown(badgesWhileAdjusting, held)) {
                        drawTierBadge(canvas, tier, orn, badgeX, badgeY)
                    }
                }
            }
            canvas.drawRect(left + colW - 1f, h * 0.1f, left + colW + 1f, h * 0.9f, dividerPaint)
        }
    }

    /**
     * The pending-block mark: a ring with a bar through it, drawn where the tier badge would be.
     * A shape, not a glyph, so it needs no font metrics and themes with the badge it replaces.
     */
    private fun drawBlockMark(canvas: Canvas, orn: Float, cx: Float, cy: Float) {
        val r = 3.6f * density * orn
        val stroke = badgePaint.strokeWidth
        val style = badgePaint.style
        badgePaint.style = Paint.Style.STROKE
        badgePaint.strokeWidth = 1.2f * density * orn
        canvas.drawCircle(cx, cy, r, badgePaint)
        canvas.drawLine(cx - r * 0.7f, cy + r * 0.7f, cx + r * 0.7f, cy - r * 0.7f, badgePaint)
        badgePaint.style = style
        badgePaint.strokeWidth = stroke
    }

    /**
     * Personal-weight badge riding a word's top-right: tier 1 is the center dot, tiers 2..7 fill
     * the six hexagon corners clockwise from the top.
     */
    private fun drawTierBadge(canvas: Canvas, tier: Int, orn: Float, cx: Float, cy: Float) {
        val r = 1.2f * density * orn
        canvas.drawCircle(cx, cy, r, badgePaint)
        val ring = 3.2f * density * orn
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
        val inset = TEXT_INSET_DP * density * BarMetrics.scale(rowHeight(), density)
        if (paint.measureText(word) <= maxW - inset) return word
        var s = word
        while (s.length > 3 && paint.measureText("$s…") > maxW - inset) {
            s = s.dropLast(1)
        }
        return "$s…"
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downZone = zoneAt(ev.x)
                downX = ev.x
                downY = ev.y
                lastTouchY = ev.y
                resetAdjust()
                // Anywhere on the words, in either direction, once there is a page to go to.
                // Not from the retype button or a recent column, which are not word zones.
                pageSwipeCandidate = pageCount() > 1 && downZone != ZONE_RETYPE && downZone != ZONE_RECENT
                pageSwipeConsumed = false
                recentDownCell = if (downZone == ZONE_RECENT) recentCellAt(ev.x, ev.y) else -1
                // In a recent column only the committed word in the middle can be slid.
                val recentHold = downZone == ZONE_RECENT &&
                    BarAdjust.recentCellAdjustable(recentDownCell, RECENT_ROWS) &&
                    recentCellSuggestion(recentDownCell) != null
                if (downZone >= 0 || recentHold) {
                    longPressHandler.postDelayed(reinforceRunnable, REINFORCE_HOLD_MS)
                }
                if (flickEnabled && !correctionMode && downZone != ZONE_RETYPE) {
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                lastTouchY = ev.y
                if (adjustArmed) {
                    // One tier step per fixed travel; truncation toward zero keeps a small wobble
                    // around the start at step 0.
                    val raw = ((adjustStartY - ev.y) / (reinforceStepDp * density)).toInt()
                    // Clamped so travel past the blocking step changes nothing: the badge stops
                    // and shows the block, so the armed state reads before the lift.
                    val steps = BarAdjust.clampSteps(adjustTarget?.count ?: 0, raw)
                    if (steps != adjustSteps) {
                        adjustSteps = steps
                        listener?.onReinforceStep()
                        invalidate()
                    }
                    return true
                }
                if (pageSwipeConsumed) return true
                // A column's swap, decided before the hold arms, cancels the arm: the two never
                // read the same travel.
                if (downZone == ZONE_RECENT &&
                    BarAdjust.recentSwap(ev.x - downX, ev.y - downY, RECENT_SWIPE_DP * density)
                ) {
                    longPressHandler.removeCallbacks(reinforceRunnable)
                }
                // After the armed long press, which owns the pointer once it fires, and before
                // the flick, which shares the whole bar with paging. BarPaging refuses any drag
                // that travels further vertically than horizontally, keeping the three apart.
                val next = if (pageSwipeCandidate) {
                    BarPaging.pageFor(
                        page, pageCount(), ev.x - downX, ev.y - downY,
                        PAGE_SWIPE_TRAVEL_DP * density,
                    )
                } else {
                    -1
                }
                if (next >= 0) {
                    pageSwipeConsumed = true
                    longPressHandler.removeCallbacks(reinforceRunnable)
                    downZone = -1
                    recycleTracker()
                    page = next
                    invalidate()
                    return true
                }
                velocityTracker?.addMovement(ev)
                if (flickEnabled && !correctionMode && downZone >= 0) {
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
                // A recent cell is never a correction pick, so its hold in place reinforces.
                val correcting = correctionMode && adjustZone >= 0
                if (adjustArmed && BarAdjust.appliesOnLift(correcting, adjustSteps)) {
                    adjustTarget?.let {
                        if (BarAdjust.blockArmed(it.count, adjustSteps)) {
                            listener?.onSuggestionBlocked(it.word)
                        } else {
                            listener?.onSuggestionReinforced(it.word, adjustDelta(it.count, adjustSteps))
                        }
                    }
                    resetAdjust()
                    downZone = -1
                    recycleTracker()
                    invalidate()
                    return true
                }
                // Armed but falling through to the pick: clear the arm's preview whether or not
                // the pick redraws.
                if (adjustArmed) {
                    resetAdjust()
                    invalidate()
                }
                if (pageSwipeConsumed) {
                    pageSwipeConsumed = false
                    downZone = -1
                    recycleTracker()
                    return true
                }
                velocityTracker?.addMovement(ev)
                val zone = zoneAt(ev.x)
                if (downZone == ZONE_RECENT) {
                    val cell = recentCellAt(ev.x, ev.y)
                    val dy = ev.y - downY
                    // Nintype-style swap: a vertical swipe on a column brings a neighbour into
                    // place, down for the word above, up for the word below.
                    val swipe = recentDownCell >= 0 &&
                        BarAdjust.recentSwap(ev.x - downX, dy, RECENT_SWIPE_DP * density)
                    if (swipe) {
                        val col = recent.getOrNull(recentDownCell / RECENT_ROWS)
                        val word = if (dy > 0) col?.above else col?.below
                        if (col != null && word != null) {
                            listener?.onRecentPicked(col.back, word)
                            performClick()
                        }
                    } else if (zone == ZONE_RECENT && cell == recentDownCell) {
                        val col = recent.getOrNull(cell / RECENT_ROWS)
                        val word = when (cell % RECENT_ROWS) {
                            0 -> col?.above
                            2 -> col?.below
                            else -> null
                        }
                        if (col != null && word != null) {
                            listener?.onRecentPicked(col.back, word)
                            performClick()
                        }
                    }
                    downZone = -1
                    recycleTracker()
                    return true
                }
                if (zone == ZONE_RETYPE && downZone == ZONE_RETYPE) {
                    listener?.onRetype()
                    performClick()
                    downZone = -1
                    recycleTracker()
                    return true
                }
                if (zone <= ZONE_ACTION_BASE && zone == downZone) {
                    listener?.onBarAction(ZONE_ACTION_BASE - zone)
                    performClick()
                    downZone = -1
                    recycleTracker()
                    return true
                }
                if (zone >= 0 && zone == downZone) {
                    wordAt(zone)?.let { s ->
                        val fullIdx = pageStart() + zone
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
        adjustTarget = null
        adjustCell = -1
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

    /**
     * Width the retype button takes off the right edge, or 0 when it is off. Capped at a quarter
     * of the bar so a narrow keyboard keeps room for words.
     */
    private fun retypeWidth(): Float =
        if (retypeButton) (retypeButtonDp * density).coerceAtMost(width / 4f) else 0f

    /** Width the word zones divide between them. */
    private fun wordsWidth(): Float = width - retypeWidth() - wordsLeft()

    /** One row's height: the bar's own unless [tallFactor] made room for recent words. */
    private fun rowHeight(): Float = height / tallFactor.coerceAtLeast(1f)

    private fun recentColumnWidth(): Float = width * RECENT_COLUMN_FRACTION

    /** Where the live words start: right of the recent-word columns. */
    private fun wordsLeft(): Float = recent.size * recentColumnWidth()

    /** Column times [RECENT_ROWS] plus row, for a touch inside the recent-word columns. */
    private fun recentCellAt(x: Float, y: Float): Int {
        val col = (x / recentColumnWidth()).toInt().coerceIn(0, (recent.size - 1).coerceAtLeast(0))
        val row = (y / (height.toFloat() / RECENT_ROWS)).toInt().coerceIn(0, RECENT_ROWS - 1)
        return col * RECENT_ROWS + row
    }

    private fun zoneAt(xRaw: Float): Int {
        val vis = visible()
        if (width <= 0) return -1
        // The button is not a word zone: it is outside the paging arithmetic, so MAX_ZONES,
        // visible and pageCount ignore it.
        if (retypeButton && xRaw >= width - retypeWidth()) return ZONE_RETYPE
        if (recent.isNotEmpty() && xRaw < wordsLeft()) return ZONE_RECENT
        val x = xRaw - wordsLeft()
        val acts = visibleActions()
        if (acts.isNotEmpty()) {
            // Negative, like ZONE_RETYPE: see ZONE_ACTION_BASE.
            val i = (x / (wordsWidth() / acts.size)).toInt().coerceIn(0, acts.size - 1)
            return ZONE_ACTION_BASE - i
        }
        if (vis.isEmpty()) return -1
        return (x / (wordsWidth() / vis.size)).toInt().coerceIn(0, vis.size - 1)
    }

    private companion object {
        /** Most zones one page may hold, whatever the words measure. */
        const val MAX_ZONES = 5

        /** Padding a word keeps inside its zone, shared by the packing and by [fit]. */
        private const val TEXT_INSET_DP = 8f

        // The tier badge rides the text's right edge at BADGE_GAP_DP, and its outermost dot
        // reaches BADGE_REACH_DP further (ring 3.2 + radius 1.2). Named so the width the packing
        // reserves and the position the drawing uses cannot drift apart.
        private const val BADGE_GAP_DP = 6f
        private const val BADGE_REACH_DP = 4.4f
        const val FLICK_VELOCITY_DP_S = 800f
        // Fixed, not the key long-press setting, which goes down to 25 ms and would let a slow
        // bar tap bump a word's weight. 450 ms spares a deliberate tap and still feels like the
        // key popups.
        const val REINFORCE_HOLD_MS = 450L
        // Default travel per weight-adjust step, about half a key height; the user's setting
        // moves it, and a tier per step keeps any word within reach.
        const val REINFORCE_STEP_DP = 24f
        // Horizontal travel before a drag pages instead of being a tap that wandered; matches
        // EdgeSwipeDetector's MIN_TRAVEL_DP so the two gestures feel alike. With the whole bar
        // as the start zone it also sets how far a thumb may drift across a word and
        // still commit it.
        const val PAGE_SWIPE_TRAVEL_DP = 30f
        // Not a word zone, so it cannot be an index into one.
        const val ZONE_RETYPE = -2

        // The recent-word columns: negative too, so no word gesture starts there.
        const val ZONE_RECENT = -3

        /** Rows in a recent-word column: the candidate above, the word, the next below. */
        const val RECENT_ROWS = 3

        /** Bar width each recent-word column takes: two leave three fifths to the live words. */
        const val RECENT_COLUMN_FRACTION = 0.2f

        /** A third of the bar is small for text; this much larger still fits the row. */
        const val RECENT_TEXT_SCALE = 1.25f

        /** Vertical travel that makes a touch on a recent column a swap, not a tap. */
        const val RECENT_SWIPE_DP = 16f

        /**
         * First shortcut cell; cell i is `ZONE_ACTION_BASE - i`. Negative, like [ZONE_RETYPE]:
         * `wordAt` returns null for any negative zone, so the 450 ms weight arm, the page swipe
         * and the upward flick stay off a row with no words behind it.
         */
        const val ZONE_ACTION_BASE = -10
        // U+21BB. A symbol, not an icon: it themes with the text, scales with the bar and needs
        // no drawable.
        const val RETYPE_GLYPH = "\u21bb"
    }
}
