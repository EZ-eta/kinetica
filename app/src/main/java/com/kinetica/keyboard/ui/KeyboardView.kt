package com.kinetica.keyboard.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.PopupWindow
import com.kinetica.keyboard.engine.Alphabet
import com.kinetica.keyboard.engine.GestureEngine
import com.kinetica.keyboard.engine.KeyboardGeometry
import com.kinetica.keyboard.engine.KineticaConstants
import com.kinetica.keyboard.layout.Key
import com.kinetica.keyboard.layout.KeyType
import com.kinetica.keyboard.layout.KeyboardLayout
import com.kinetica.keyboard.layout.LayoutMode
import com.kinetica.keyboard.layout.LayoutTransforms
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.keys.BackspaceController
import com.kinetica.keyboard.keys.EdgeSwipeBinding
import com.kinetica.keyboard.keys.EdgeSwipeBindings
import com.kinetica.keyboard.keys.EdgeSwipeDetector
import com.kinetica.keyboard.keys.SpacebarCursorController
import kotlin.math.abs

/**
 * The keyboard surface: a plain custom View (the framework KeyboardView is
 * deprecated), hardware-accelerated Canvas rendering.
 *
 * Two rendering layers: a static bitmap (key backgrounds and labels,
 * re-rendered only on size/layout/shift changes) plus a dynamic overlay for
 * pressed keys, hue-cycling swipe trails, and key-contact bursts. A single
 * Choreographer callback drives frames only while something animates; at rest
 * there are zero invalidations. Zen mode gates all animation at enqueue time.
 *
 * Touch routing per pointer-down: letter keys stream to the GestureEngine,
 * the spacebar and backspace go to their slide controllers, and everything
 * else dispatches as a tap on lift.
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        /** Tap on a non-letter key (space, enter, shift, modes, punctuation). */
        fun onKeyTap(key: Key)

        /** Letter-key geometry changed (size or layout swap). */
        fun onGeometryChanged(geometry: KeyboardGeometry)

        /**
         * Spacebar slide: one cursor step left (-1) or right (+1), a whole word at a
         * time when [byWord] is set rather than a single character.
         */
        fun onCursorMove(direction: Int, byWord: Boolean)

        fun onDeleteChar()

        /**
         * Backspace slide staged [units] units for deletion (0 = retracted to
         * nothing), where a unit is a single character when [chars] is true and a
         * whole word otherwise. Nothing is deleted yet; the service computes the
         * span and feeds the preview back via [setDeletePreview].
         */
        fun onStageDelete(units: Int, chars: Boolean)

        /** Backspace slide lifted with a staged span: commit its deletion. */
        fun onCommitStagedDelete()

        /** Edge-swipe shortcut output; "emoji" is a reserved value. */
        fun onEdgeSwipe(output: String)

        /** Alternate chosen from a long-press popup (plain lift = default). */
        fun onKeyAlternate(key: Key, text: String)

        /** Horizontal slide on a mode/enter key requesting a layer switch. */
        fun onModeSlide(target: KeyType)

        /** Gear selected in the ?123 hold popup: open the settings screen. */
        fun onSettingsRequested()

        /** Synchronous query: does this letter have a chord expansion? */
        fun hasChord(letterCode: Int): Boolean

        /** ?123 held and a chord-assigned letter tapped: fire the expansion. */
        fun onChordTriggered(letterCode: Int)

        /** Any key-down; the service decides whether to vibrate. */
        fun onKeyPressFeedback()
    }

    var listener: Listener? = null
    var engine: GestureEngine? = null

    /** Disables all animation work (trails, bursts) at enqueue time. */
    var zenMode = false

    /** Extra trail gate: also cleared in private (password) fields. */
    var trailsEnabled = true

    var trailBaseHue: Float
        get() = trailRenderer.baseHue
        set(value) {
            trailRenderer.baseHue = value
        }

    /** Arm delay for backspace hold-repeat. */
    var backspaceHoldArmMs = 500L

    /** Backspace slide stages single characters instead of whole words. */
    var backspaceCharSlide: Boolean
        get() = backspaceController.charMode
        set(value) { backspaceController.charMode = value }

    /** Travel that advances the spacebar's cursor slide by one step; lower is faster. */
    var spacebarStepDp: Float
        get() = spaceController.stepDp
        set(value) { spaceController.stepDp = value }

    /** Spacebar slide moves whole words instead of single characters. */
    var spacebarWordSlide: Boolean
        get() = spaceController.wordMode
        set(value) { spaceController.wordMode = value }

    /** Active edge-swipe shortcut set; swapped live on preference changes. */
    var edgeSwipeBindings: EdgeSwipeBindings = EdgeSwipeBindings.DEFAULTS

    /** Threshold for long-press alternates on tap-dispatched keys. */
    var longPressMs = 500L

    var layoutMode: LayoutMode = LayoutMode.FULL
        set(value) {
            if (field != value) {
                field = value
                cancelActivePointers()
                if (width > 0 && height > 0) rebuild()
            }
        }

    /** Small dot on the spacebar while autospace is enabled. */
    var autospaceDot = false
        set(value) {
            if (field != value) {
                field = value
                renderStaticLayer()
                invalidate()
            }
        }

    /**
     * Status text at the spacebar's bottom-center (active language code, mode
     * markers); null hides it. Drawn at the 40%-alpha hint convention so it
     * reads as ambient state, not a key label. The service owns the content
     * (language is otherwise invisible until cycled).
     */
    var languageLabel: String? = null
        set(value) {
            if (field != value) {
                field = value
                renderStaticLayer()
                invalidate()
            }
        }

    private var layout: KeyboardLayout? = null
    private val keyRects = ArrayList<RectF>()
    private val keyInsets = ArrayList<RectF>()
    private var uppercase = false
    private var staticLayer: Bitmap? = null
    private var engineActive = false

    private val density = resources.displayMetrics.density
    private val keyGapPx = 2.5f * density
    private val cornerPx = 7f * density

    private val trailRenderer = TrailRenderer(density)
    private val burstRenderer = BurstRenderer(density)
    private var animating = false
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val now = SystemClock.uptimeMillis()
            val alive = trailRenderer.prune(now) or burstRenderer.prune(now)
            invalidate()
            if (alive) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                animating = false
            }
        }
    }

    private val letterCenterX = FloatArray(Alphabet.LETTERS)
    private val letterCenterY = FloatArray(Alphabet.LETTERS)

    private val spaceController = SpacebarCursorController(density) { dir, byWord ->
        listener?.onCursorMove(dir, byWord)
    }
    private val backspaceController = BackspaceController(
        density,
        Handler(Looper.getMainLooper()),
        onDeleteChar = { listener?.onDeleteChar() },
        onStageUnits = { units, chars -> listener?.onStageDelete(units, chars) },
        onCommitStaged = { listener?.onCommitStagedDelete() },
    )
    private var spacePointer = -1
    private var backspacePointer = -1

    private val bgPaint = Paint()
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val specialKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
    }

    /** Resolved color roles; the setter restains every paint and re-renders. */
    var theme: KeyboardTheme = KeyboardTheme.fromResources(context)
        set(value) {
            field = value
            applyThemePaints()
            if (width > 0 && height > 0) renderStaticLayer()
            invalidate()
        }

    private fun applyThemePaints() {
        bgPaint.color = theme.background
        keyPaint.color = theme.key
        specialKeyPaint.color = theme.keySpecial
        pressedPaint.color = theme.keyPressed
        labelPaint.color = theme.keyText
        hintPaint.color = theme.keyHint
        popupBgPaint.color = theme.popupBg
        popupSelectedPaint.color = theme.accent
        deletePreviewPaint.color = theme.keyText
    }

    private val routeByPointer = IntArray(MAX_POINTERS) { ROUTE_NONE }
    private val downKeyByPointer = IntArray(MAX_POINTERS) { -1 }
    private val downXByPointer = FloatArray(MAX_POINTERS)
    private val downYByPointer = FloatArray(MAX_POINTERS)
    // Displacement at the furthest sample from the down point, per pointer. Read
    // only by EdgeSwipeDetector, which needs it because a directional flick off
    // the top row is short and curves back before the lift - see its KDoc.
    private val peakDxByPointer = FloatArray(MAX_POINTERS)
    private val peakDyByPointer = FloatArray(MAX_POINTERS)
    private val downTimeByPointer = LongArray(MAX_POINTERS)
    private val pressedKeys = LinkedHashSet<Int>()

    // ------------------------------------------------------------ key popups
    //
    // One popup at a time, drawn as an overlay strip of cells above its anchor
    // key and owned by exactly one pointer. Two users: the ?123 hold menu
    // (gear -> settings) and long-press character alternates. Hold detection
    // runs on a timer so it can fire mid-press; it is cancelled by >12dp of
    // travel (the same displacement that commits a swipe), so it can never
    // steal a tap (<150 ms lift beats the timer) or a swipe from the engine.
    //
    // Rendering has two paths: rows with room above draw
    // the strip on this view's own canvas; an anchor whose strip would clamp
    // onto its own row (top row, or any row on a very short keyboard) renders
    // it in a non-touchable PopupWindow that extends ABOVE the keyboard edge
    // instead of under the pressing thumb. Touch input NEVER moves with the
    // visual: PopupState.rect keeps view coordinates and all selection math
    // stays view-local, so both paths behave identically under the finger.
    // The gear popup requires lift-inside-the-rect and is excluded from
    // elevation (its ?123 anchor is bottom-row anyway); any window failure
    // (bad token, detached surface) falls back to the clamped canvas strip.

    private class PopupState(
        val cells: List<String>,
        val rect: RectF,
        val cellW: Float,
        var selected: Int,
        /** True: commit only when the pointer lifts inside the popup rect. */
        val requireInside: Boolean,
        /** Pointer x when the popup appeared; selection follows x only after
         *  the finger slides away from here, so a plain long-press lift
         *  commits the pre-selected default. */
        val originX: Float = 0f,
        var engaged: Boolean = false,
        /** True: the strip renders in the elevated window, not on the canvas. */
        val elevated: Boolean = false,
    )

    private var popup: PopupState? = null
    private var popupPointer = -1
    private var pendingHoldPid = -1
    private var pendingHoldKeyIdx = -1
    private var popupWindow: PopupWindow? = null
    private var popupStrip: PopupStripView? = null

    /** Render surface for elevated popups: draws the exact same strip the
     *  canvas path would, translated to the window's own origin, so the two
     *  paths cannot drift apart visually. */
    private inner class PopupStripView(context: Context) : View(context) {
        override fun onDraw(canvas: Canvas) {
            val p = popup ?: return
            canvas.save()
            canvas.translate(-p.rect.left, -p.rect.top)
            drawPopup(canvas, p)
            canvas.restore()
        }
    }

    // ---------------------------------------------------- ?123 interactions
    //
    // Four gestures share the mode key, disambiguated by time and travel:
    //  tap        lift before longPressMs, <12dp travel        -> symbols
    //  slide      >=30dp travel, dominant horizontal           -> numpad
    //  hold       >=longPressMs stationary, no other key       -> gear popup
    //  chord      held >=CHORD_ARM_MS AND a letter key tapped  -> expansion
    // CHORD_ARM_MS (150ms) exists so a two-thumb typist brushing ?123 in the
    // same instant as a letter cannot fire a chord by accident; a deliberate
    // chord (press, then tap) clears 150ms without ever noticing it. A chord
    // consumes both touches: the letter never reaches the gesture engine and
    // the ?123 lift stops switching layers. Chords fire only for letters the
    // user has assigned (default: none), everything else types normally.
    private var modeHoldPointer = -1
    private var modeHoldDownTime = 0L
    private var modeHoldMoved = false
    private var modeChordFired = false
    private val holdHandler = Handler(Looper.getMainLooper())
    private val holdRunnable = Runnable { onHoldTimerFired() }
    private val holdSlopPx = KineticaConstants.TAP_MAX_DISP_DP * density

    private val popupBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val popupSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ------------------------------------------------ staged-delete preview
    //
    // The reversible backspace slide never touches the editor until lift; the
    // staged span is previewed struck-through in a chip above the backspace
    // key. Drawn in the IME's own window (not via setComposingRegion) so it
    // renders identically in every app - Kinetica's text model is
    // deliberately commit-only and some editors drop or restyle composing
    // spans.
    private var deletePreview: String? = null
    private val deletePreviewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        flags = flags or Paint.STRIKE_THRU_TEXT_FLAG
    }

    /** Staged-deletion span to preview; null hides the chip. */
    fun setDeletePreview(text: String?) {
        if (deletePreview != text) {
            deletePreview = text
            invalidate()
        }
    }

    init {
        applyThemePaints()
    }

    fun setKeyboardLayout(l: KeyboardLayout) {
        cancelActivePointers()
        layout = l
        if (width > 0 && height > 0) {
            rebuild()
        }
    }

    fun setShiftUppercase(value: Boolean) {
        if (uppercase != value) {
            uppercase = value
            renderStaticLayer()
            invalidate()
        }
    }

    /** Swipe path entered a new key: cycle the trail hue and burst the key. */
    fun onEngineKeyTransition(streamId: StreamId, code: Int) {
        if (zenMode || !trailsEnabled) return
        trailRenderer.bumpHue(streamId)
        if (code in 0 until Alphabet.LETTERS) {
            burstRenderer.spawn(letterCenterX[code], letterCenterY[code], SystemClock.uptimeMillis())
            ensureAnimating()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) rebuild()
    }

    override fun onDetachedFromWindow() {
        cancelActivePointers()
        super.onDetachedFromWindow()
    }

    private fun rebuild() {
        val l = layout ?: return
        keyRects.clear()
        keyInsets.clear()
        val w = width.toFloat()
        val h = height.toFloat()
        for (k in l.keys) {
            val rect = LayoutTransforms.apply(layoutMode, k, w, h)
            keyRects.add(rect)
            keyInsets.add(insetRect(rect))
        }
        buildGeometry(l)
        renderStaticLayer()
        invalidate()
    }

    private fun buildGeometry(l: KeyboardLayout) {
        val rects = ArrayList<FloatArray>()
        val codes = ArrayList<Int>()
        var minLetterW = Float.MAX_VALUE
        for (i in l.keys.indices) {
            val k = l.keys[i]
            if (!k.isLetter) continue
            val r = keyRects[i]
            rects.add(floatArrayOf(r.left, r.top, r.right, r.bottom))
            val code = k.output[0] - 'a'
            codes.add(code)
            letterCenterX[code] = r.centerX()
            letterCenterY[code] = r.centerY()
            if (r.width() < minLetterW) minLetterW = r.width()
        }
        engineActive = rects.isNotEmpty()
        if (!engineActive || minLetterW <= 0f) return
        val g = KeyboardGeometry.fromPx(
            minLetterW, width.toFloat(), rects, codes.toIntArray(),
        )
        engine?.setGeometry(g, KineticaConstants.TAP_MAX_DISP_DP * density)
        listener?.onGeometryChanged(g)
    }

    private fun renderStaticLayer() {
        if (width <= 0 || height <= 0) return
        val l = layout ?: return
        val bmp = staticLayer?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { staticLayer = it }
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        for (i in l.keys.indices) {
            drawKey(c, l.keys[i], keyInsets[i])
        }
    }

    private fun drawKey(c: Canvas, key: Key, inset: RectF) {
        // Chromeless keys (apostrophe) paint no background - just the glyph on
        // the keyboard surface, so they blend in Nintype-style.
        if (!key.chromeless) {
            val paint = if (key.type == KeyType.CHAR) keyPaint else specialKeyPaint
            c.drawRoundRect(inset, cornerPx, cornerPx, paint)
        }

        val label = when {
            key.type == KeyType.SPACE -> ""
            key.isLetter && uppercase -> key.label.uppercase()
            else -> key.label
        }
        if (label.isNotEmpty()) {
            labelPaint.textSize = if (label.length > 1) inset.height() * 0.32f else inset.height() * 0.45f
            val baseline = inset.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
            c.drawText(label, inset.centerX(), baseline, labelPaint)
        }
        key.hintChar?.let {
            // 40% alpha: visible enough to advertise the long-press default
            // without competing with the main label.
            hintPaint.alpha = 102
            hintPaint.textSize = inset.height() * 0.24f
            c.drawText(it, inset.right - 3f * density, inset.top + hintPaint.textSize + 2f * density, hintPaint)
        }
        if (key.type == KeyType.SPACE && autospaceDot) {
            hintPaint.alpha = 255
            c.drawCircle(inset.centerX(), inset.top + inset.height() * 0.22f, 2.5f * density, hintPaint)
        }
        if (key.type == KeyType.SPACE) {
            languageLabel?.let {
                // Bottom-center so it coexists with the autospace dot (top) and
                // never collides with the cursor-slide affordance.
                hintPaint.alpha = 102
                hintPaint.textSize = inset.height() * 0.24f
                hintPaint.textAlign = Paint.Align.CENTER
                c.drawText(it, inset.centerX(), inset.bottom - 4f * density, hintPaint)
                hintPaint.textAlign = Paint.Align.RIGHT
            }
        }
    }

    private fun insetRect(rect: RectF): RectF = RectF(
        rect.left + keyGapPx, rect.top + keyGapPx,
        rect.right - keyGapPx, rect.bottom - keyGapPx,
    )

    override fun onDraw(canvas: Canvas) {
        staticLayer?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        val l = layout
        if (pressedKeys.isNotEmpty() && l != null) {
            for (idx in pressedKeys) {
                if (idx !in l.keys.indices) continue
                val key = l.keys[idx]
                if (key.chromeless) continue
                // Insets are immutable between rebuilds; reusing them avoids a
                // RectF allocation on every animation frame while a key is held.
                val inset = keyInsets[idx]
                canvas.drawRoundRect(inset, cornerPx, cornerPx, pressedPaint)
                val label = if (key.isLetter && uppercase) key.label.uppercase() else key.label
                if (label.isNotEmpty() && key.type == KeyType.CHAR) {
                    labelPaint.textSize =
                        if (label.length > 1) inset.height() * 0.32f else inset.height() * 0.45f
                    val baseline = inset.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
                    canvas.drawText(label, inset.centerX(), baseline, labelPaint)
                }
            }
        }
        if (!zenMode) {
            val now = SystemClock.uptimeMillis()
            trailRenderer.draw(canvas, now)
            burstRenderer.draw(canvas, now)
        }
        popup?.let { if (!it.elevated) drawPopup(canvas, it) }
        deletePreview?.let { drawDeletePreview(canvas, it) }
    }

    private fun drawDeletePreview(canvas: Canvas, text: String) {
        val l = layout ?: return
        var anchor: RectF? = null
        for (i in l.keys.indices) {
            if (l.keys[i].type == KeyType.BACKSPACE) {
                anchor = keyRects[i]
                break
            }
        }
        val a = anchor ?: return
        val margin = 4f * density
        val pad = 10f * density
        val h = maxOf(a.height() * 0.8f, 40f * density)
        deletePreviewPaint.textSize = h * 0.42f
        // Newlines flatten so the chip stays one line; the head ellipsizes
        // because the words nearest the cursor (span tail) matter most.
        var shown = text.replace('\n', '⏎')
        val maxW = width - 2f * margin - 2f * pad
        if (deletePreviewPaint.measureText(shown) > maxW) {
            while (shown.length > 1 && deletePreviewPaint.measureText("…$shown") > maxW) {
                shown = shown.substring(1)
            }
            shown = "…$shown"
        }
        val w = deletePreviewPaint.measureText(shown) + 2f * pad
        val right = minOf(a.right, width - margin)
        val left = maxOf(margin, right - w)
        val top = maxOf(margin, a.top - h - 8f * density)
        val rect = RectF(left, top, left + w, top + h)
        canvas.drawRoundRect(rect, cornerPx, cornerPx, popupBgPaint)
        val baseline = rect.centerY() - (deletePreviewPaint.descent() + deletePreviewPaint.ascent()) / 2f
        canvas.drawText(shown, rect.centerX(), baseline, deletePreviewPaint)
    }

    private fun drawPopup(canvas: Canvas, p: PopupState) {
        canvas.drawRoundRect(p.rect, cornerPx, cornerPx, popupBgPaint)
        labelPaint.textSize = p.rect.height() * 0.45f
        val baseline = p.rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
        for (i in p.cells.indices) {
            val cx = p.rect.left + p.cellW * (i + 0.5f)
            if (i == p.selected) {
                val inset = 2f * density
                canvas.drawRoundRect(
                    p.rect.left + p.cellW * i + inset, p.rect.top + inset,
                    p.rect.left + p.cellW * (i + 1) - inset, p.rect.bottom - inset,
                    cornerPx, cornerPx, popupSelectedPaint,
                )
            }
            canvas.drawText(p.cells[i], cx, baseline, labelPaint)
        }
    }

    private fun ensureAnimating() {
        if (zenMode || animating) return
        animating = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    // ------------------------------------------------------------- touch

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = ev.actionIndex
                val pid = ev.getPointerId(idx)
                if (pid in 0 until MAX_POINTERS) {
                    handleDown(pid, ev.getX(idx), ev.getY(idx), ev.eventTime)
                }
            }
            MotionEvent.ACTION_MOVE -> handleMove(ev)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = ev.actionIndex
                val pid = ev.getPointerId(idx)
                if (pid in 0 until MAX_POINTERS) {
                    handleUp(pid, ev.getX(idx), ev.getY(idx), ev.eventTime)
                }
            }
            MotionEvent.ACTION_CANCEL -> cancelActivePointers()
        }
        return true
    }

    private fun handleDown(pid: Int, x: Float, y: Float, t: Long) {
        val l = layout ?: return
        val keyIdx = keyIndexAt(x, y)
        downKeyByPointer[pid] = keyIdx
        downXByPointer[pid] = x
        downYByPointer[pid] = y
        peakDxByPointer[pid] = 0f
        peakDyByPointer[pid] = 0f
        downTimeByPointer[pid] = t
        val key = if (keyIdx != -1) l.keys[keyIdx] else null

        if (keyIdx != -1) listener?.onKeyPressFeedback()

        if (key?.type == KeyType.MODE_SYMBOLS && modeHoldPointer == -1) {
            modeHoldPointer = pid
            modeHoldDownTime = t
            modeHoldMoved = false
            modeChordFired = false
        }

        routeByPointer[pid] = when {
            key?.isLetter == true && chordArmed(t) &&
                listener?.hasChord(key.output[0] - 'a') == true -> ROUTE_CHORD
            engineActive && engine?.onPointerDown(pid, x, y, t) == true -> {
                val stream = engine?.streamIdOf(pid)
                if (stream != null && !zenMode && trailsEnabled) {
                    trailRenderer.startStream(stream)
                    val code = key?.takeIf { it.isLetter }?.output?.get(0)?.minus('a')
                    if (code != null) {
                        burstRenderer.spawn(letterCenterX[code], letterCenterY[code], t)
                        ensureAnimating()
                    }
                }
                ROUTE_ENGINE
            }
            key?.type == KeyType.SPACE && spacePointer == -1 -> {
                spacePointer = pid
                spaceController.onDown(x)
                ROUTE_SPACE
            }
            key?.type == KeyType.BACKSPACE && backspacePointer == -1 -> {
                backspacePointer = pid
                backspaceController.onDown(x, backspaceHoldArmMs)
                ROUTE_BACKSPACE
            }
            else -> ROUTE_SPECIAL
        }
        val holdCapable = key != null &&
            (key.type == KeyType.MODE_SYMBOLS || key.alternates.isNotEmpty())
        if (holdCapable && popup == null && pendingHoldPid == -1) {
            scheduleHold(pid, keyIdx)
        }
        if (keyIdx != -1) {
            pressedKeys.add(keyIdx)
            invalidate()
        }
    }

    /** Chords arm only while ?123 rests in place past CHORD_ARM_MS. */
    private fun chordArmed(t: Long): Boolean =
        modeHoldPointer != -1 && !modeHoldMoved &&
            t - modeHoldDownTime >= CHORD_ARM_MS

    private fun scheduleHold(pid: Int, keyIdx: Int) {
        pendingHoldPid = pid
        pendingHoldKeyIdx = keyIdx
        holdHandler.postDelayed(holdRunnable, longPressMs)
    }

    private fun cancelHold() {
        if (pendingHoldPid != -1) {
            holdHandler.removeCallbacks(holdRunnable)
            pendingHoldPid = -1
            pendingHoldKeyIdx = -1
        }
    }

    private fun onHoldTimerFired() {
        val pid = pendingHoldPid
        val keyIdx = pendingHoldKeyIdx
        cancelHold()
        if (pid == -1 || keyIdx == -1) return
        val l = layout ?: return
        if (keyIdx !in l.keys.indices) return
        val key = l.keys[keyIdx]
        val route = routeByPointer[pid]
        when {
            key.type == KeyType.MODE_SYMBOLS && route == ROUTE_SPECIAL -> {
                routeByPointer[pid] = ROUTE_MODE_HOLD
                popupPointer = pid
                showPopup(keyIdx, listOf(GEAR_GLYPH), selected = -1, requireInside = true)
            }
            key.type == KeyType.ENTER && key.alternates.isNotEmpty() && route == ROUTE_SPECIAL -> {
                // Enter's popup shows its alternates ALONE (no base
                // glyph - that would commit "⏎" via onKeyAlternate). The primary
                // (first alternate) sits ON TOP of the key: reverse so it is the
                // rightmost cell and pre-select it, so a plain hold-and-lift
                // commits it and sliding left reaches the others.
                routeByPointer[pid] = ROUTE_ALT_POPUP
                popupPointer = pid
                val cells = key.alternates.reversed()
                showPopup(
                    keyIdx, cells, selected = cells.lastIndex,
                    requireInside = false, originX = downXByPointer[pid],
                    anchorRightCell = true,
                )
            }
            key.alternates.isNotEmpty() && (route == ROUTE_ENGINE || route == ROUTE_SPECIAL) -> {
                if (route == ROUTE_ENGINE) {
                    // Movement past the tap threshold cancels the hold timer,
                    // so a committed swipe can never reach this point; the
                    // check is a belt against event-order races.
                    if (engine?.isSwipeCommitted(pid) == true) return
                    engine?.cancelPointer(pid)
                }
                routeByPointer[pid] = ROUTE_ALT_POPUP
                popupPointer = pid
                val base = if (uppercase && key.isLetter) key.label.uppercase() else key.label
                val alts = if (uppercase && key.isLetter) {
                    key.alternates.map { it.uppercase() }
                } else {
                    key.alternates
                }
                // Base char first, then the alternates; the first alternate is
                // pre-selected so a plain lift commits it immediately.
                showPopup(
                    keyIdx, listOf(base) + alts, selected = 1,
                    requireInside = false, originX = downXByPointer[pid],
                )
            }
        }
    }

    private fun showPopup(
        anchorIdx: Int,
        cells: List<String>,
        selected: Int,
        requireInside: Boolean,
        originX: Float = 0f,
        anchorRightCell: Boolean = false,
    ) {
        if (anchorIdx !in keyRects.indices || cells.isEmpty()) return
        val anchor = keyRects[anchorIdx]
        val cellW = maxOf(anchor.width(), 48f * density)
            .coerceAtMost((width - 8f * density) / cells.size)
        val w = cellW * cells.size
        val h = maxOf(anchor.height(), 48f * density)
        val margin = 4f * density
        // anchorRightCell places the LAST cell above the anchor and extends the
        // strip left (enter's primary symbol sits on top of the key with
        // the alternates reached by sliding left). Otherwise the strip centers.
        val rawLeft = if (anchorRightCell) {
            anchor.centerX() - w + cellW / 2f
        } else {
            anchor.centerX() - w / 2f
        }
        val left = rawLeft.coerceIn(margin, width - w - margin)
        // Above the key when there is room. When there is none (top row, or
        // any row on a very short keyboard) the strip would clamp onto its own
        // row under the pressing thumb, so alternates popups render in the
        // elevated window at the unclamped position instead; the state rect
        // stays clamped and view-local either way, because selection tracking
        // reads it. Gear popups (requireInside) never elevate - their commit
        // test is lift-inside-this-rect.
        val desiredTop = anchor.top - h - 8f * density
        val top = desiredTop.coerceAtLeast(margin)
        val elevated = desiredTop < margin && !requireInside &&
            showElevatedPopup(left, desiredTop, w, h)
        popup = PopupState(
            cells, RectF(left, top, left + w, top + h), cellW, selected,
            requireInside, originX, elevated = elevated,
        )
        popupStrip?.invalidate()
        invalidate()
    }

    /**
     * Shows the render-only window for a strip whose unclamped position
     * extends above the keyboard view. Non-touchable and non-focusable (all
     * input stays on this view), clipping disabled so it may leave the IME
     * window's bounds, positioned in window coordinates so a negative top is
     * legal. Returns false - canvas fallback - if the window cannot be shown
     * (detached view, dead token: both occur during IME teardown races).
     */
    private fun showElevatedPopup(left: Float, top: Float, w: Float, h: Float): Boolean {
        if (windowToken == null) return false
        return try {
            popupWindow?.dismiss()
            val strip = popupStrip ?: PopupStripView(context).also { popupStrip = it }
            val win = PopupWindow(strip, w.toInt(), h.toInt()).apply {
                isTouchable = false
                isFocusable = false
                isClippingEnabled = false
                inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            }
            val loc = IntArray(2)
            getLocationInWindow(loc)
            win.showAtLocation(
                this, Gravity.NO_GRAVITY,
                loc[0] + left.toInt(), loc[1] + top.toInt(),
            )
            popupWindow = win
            true
        } catch (e: RuntimeException) {
            popupWindow = null
            false
        }
    }

    private fun dismissPopup() {
        popupWindow?.dismiss()
        popupWindow = null
        if (popup != null) {
            popup = null
            popupPointer = -1
            invalidate()
        }
    }

    private fun updatePopupSelection(x: Float, y: Float) {
        val p = popup ?: return
        if (!p.requireInside && !p.engaged) {
            if (abs(x - p.originX) < holdSlopPx) return
            p.engaged = true
        }
        val idx = ((x - p.rect.left) / p.cellW).toInt().coerceIn(0, p.cells.size - 1)
        val newSel = if (p.requireInside && !p.rect.contains(x, y)) -1 else idx
        if (newSel != p.selected) {
            p.selected = newSel
            if (p.elevated) popupStrip?.invalidate()
            invalidate()
        }
    }

    private fun handleMove(ev: MotionEvent) {
        val e = engine
        for (h in 0 until ev.historySize) {
            for (p in 0 until ev.pointerCount) {
                val pid = ev.getPointerId(p)
                if (pid !in 0 until MAX_POINTERS) continue
                if (routeByPointer[pid] == ROUTE_ENGINE && e != null) {
                    val hx = ev.getHistoricalX(p, h)
                    val hy = ev.getHistoricalY(p, h)
                    val ht = ev.getHistoricalEventTime(h)
                    e.onPointerMove(pid, hx, hy, ht)
                    addTrailPoint(e, pid, hx, hy, ht)
                }
            }
        }
        for (p in 0 until ev.pointerCount) {
            val pid = ev.getPointerId(p)
            if (pid !in 0 until MAX_POINTERS) continue
            val x = ev.getX(p)
            val y = ev.getY(p)
            trackPeak(pid, x, y)
            if (pid == pendingHoldPid || (pid == modeHoldPointer && !modeHoldMoved)) {
                val dx = x - downXByPointer[pid]
                val dy = y - downYByPointer[pid]
                if (dx * dx + dy * dy > holdSlopPx * holdSlopPx) {
                    if (pid == pendingHoldPid) cancelHold()
                    if (pid == modeHoldPointer) modeHoldMoved = true
                }
            }
            when (routeByPointer[pid]) {
                ROUTE_ENGINE -> if (e != null) {
                    e.onPointerMove(pid, x, y, ev.eventTime)
                    addTrailPoint(e, pid, x, y, ev.eventTime)
                }
                ROUTE_SPACE -> spaceController.onMove(x)
                ROUTE_BACKSPACE -> backspaceController.onMove(x)
                ROUTE_SPECIAL -> maybeArmEnterPopup(pid, x, y)
                ROUTE_MODE_HOLD, ROUTE_ALT_POPUP ->
                    if (pid == popupPointer) updatePopupSelection(x, y)
            }
        }
    }

    /**
     * Keeps the displacement at this pointer's furthest sample from its down
     * point. Radial, so one pair of values serves all four directions, and the
     * comparison is on the squared distance - no roots on the move path.
     */
    private fun trackPeak(pid: Int, x: Float, y: Float) {
        val dx = x - downXByPointer[pid]
        val dy = y - downYByPointer[pid]
        val px = peakDxByPointer[pid]
        val py = peakDyByPointer[pid]
        if (dx * dx + dy * dy > px * px + py * py) {
            peakDxByPointer[pid] = dx
            peakDyByPointer[pid] = dy
        }
    }

    private fun addTrailPoint(e: GestureEngine, pid: Int, x: Float, y: Float, t: Long) {
        if (zenMode || !trailsEnabled) return
        if (!e.isSwipeCommitted(pid)) return
        val stream = e.streamIdOf(pid) ?: return
        trailRenderer.addPoint(stream, x, y, t)
        ensureAnimating()
    }

    private fun handleUp(pid: Int, x: Float, y: Float, t: Long) {
        if (pid == pendingHoldPid) cancelHold()
        val key = keyAtDown(pid)
        val dx = x - downXByPointer[pid]
        val dy = y - downYByPointer[pid]
        trackPeak(pid, x, y)
        val shortcut = key?.let {
            EdgeSwipeDetector.detect(
                it, dx, dy, peakDxByPointer[pid], peakDyByPointer[pid], density, edgeSwipeBindings,
            )
        }

        when (routeByPointer[pid]) {
            ROUTE_ENGINE -> {
                if (shortcut != null) {
                    // A designated edge swipe wins over gesture decoding.
                    engine?.cancelPointer(pid)
                    listener?.onEdgeSwipe(shortcut)
                } else {
                    engine?.onPointerUp(pid, x, y, t)
                }
            }
            ROUTE_SPACE -> {
                if (spaceController.onUp()) {
                    dispatchTap(pid, t)
                }
                spacePointer = -1
            }
            ROUTE_BACKSPACE -> {
                if (shortcut != null) {
                    backspaceController.cancel()
                    listener?.onEdgeSwipe(shortcut)
                } else {
                    backspaceController.onUp()
                }
                backspacePointer = -1
            }
            ROUTE_SPECIAL -> {
                val modeSlide = detectModeSlide(key, dx, dy)
                when {
                    // A fired chord consumes the ?123 lift entirely.
                    modeChordFired && key?.type == KeyType.MODE_SYMBOLS -> Unit
                    shortcut != null -> listener?.onEdgeSwipe(shortcut)
                    modeSlide != null -> listener?.onModeSlide(modeSlide)
                    else -> dispatchTap(pid, t)
                }
            }
            ROUTE_MODE_HOLD -> {
                val selected = popup?.selected ?: -1
                dismissPopup()
                if (selected == 0 && !modeChordFired) listener?.onSettingsRequested()
            }
            ROUTE_ALT_POPUP -> {
                val p = popup
                dismissPopup()
                if (p != null && p.selected in p.cells.indices) {
                    keyAtDown(pid)?.let { listener?.onKeyAlternate(it, p.cells[p.selected]) }
                }
            }
            ROUTE_CHORD -> {
                if (modeHoldPointer != -1) {
                    key?.takeIf { it.isLetter }?.let {
                        modeChordFired = true
                        // The gear popup (if it already appeared) yields to
                        // the chord; further letters can chord in this hold.
                        cancelHold()
                        dismissPopup()
                        listener?.onChordTriggered(it.output[0] - 'a')
                    }
                }
            }
        }
        val keyIdx = downKeyByPointer[pid]
        if (keyIdx != -1) {
            pressedKeys.remove(keyIdx)
            invalidate()
        }
        if (pid == modeHoldPointer) {
            modeHoldPointer = -1
            modeHoldMoved = false
            modeChordFired = false
        }
        routeByPointer[pid] = ROUTE_NONE
        downKeyByPointer[pid] = -1
    }

    /**
     * A decisive up-slide on the enter key (before the hold timer)
     * arms its alternate popup with "?" pre-selected; a later lateral slide
     * then selects "!"/"," through the existing horizontal [updatePopupSelection]
     * (a straight-up lift keeps "?"). Skipped when the user rebound enter-up to
     * anything but the default "?", so their custom edge swipe still fires on
     * lift instead. The 30dp vertical gate mirrors [detectModeSlide]'s horizontal
     * one; dominance keeps it clear of enter's slide-left mode switch.
     */
    private fun maybeArmEnterPopup(pid: Int, x: Float, y: Float) {
        if (popup != null) return
        val key = keyAtDown(pid) ?: return
        if (key.type != KeyType.ENTER || key.alternates.isEmpty()) return
        val up = edgeSwipeBindings.outputFor("enter", EdgeSwipeBinding.Direction.UP)
        if (up != null && up != "?") return
        val dx = x - downXByPointer[pid]
        val dy = y - downYByPointer[pid]
        val minTravel = 30f * density
        if (-dy < minTravel || -dy < 1.5f * abs(dx)) return
        cancelHold()
        routeByPointer[pid] = ROUTE_ALT_POPUP
        popupPointer = pid
        // Primary on top of the key, alternates to the left (see onHoldTimerFired).
        val cells = key.alternates.reversed()
        showPopup(
            downKeyByPointer[pid], cells, selected = cells.lastIndex,
            requireInside = false, originX = downXByPointer[pid],
            anchorRightCell = true,
        )
    }

    /** Slide right on ?123 opens the numpad; slide left on enter returns to alpha. */
    private fun detectModeSlide(key: Key?, dx: Float, dy: Float): KeyType? {
        if (key == null) return null
        val minTravel = 30f * density
        if (abs(dx) < minTravel || abs(dx) < 1.5f * abs(dy)) return null
        return when {
            key.type == KeyType.MODE_SYMBOLS && dx > 0 -> KeyType.MODE_NUMPAD
            key.type == KeyType.ENTER && dx < 0 -> KeyType.MODE_ALPHA
            else -> null
        }
    }

    private fun keyAtDown(pid: Int): Key? {
        val l = layout ?: return null
        val keyIdx = downKeyByPointer[pid]
        return if (keyIdx in l.keys.indices) l.keys[keyIdx] else null
    }

    private fun dispatchTap(pid: Int, @Suppress("UNUSED_PARAMETER") upTime: Long) {
        // Long presses are consumed by the hold timer before the lift, so
        // everything arriving here is a plain tap.
        val key = keyAtDown(pid) ?: return
        listener?.onKeyTap(key)
    }

    private fun cancelActivePointers() {
        engine?.cancelAll()
        backspaceController.cancel()
        cancelHold()
        dismissPopup()
        modeHoldPointer = -1
        modeHoldMoved = false
        modeChordFired = false
        spacePointer = -1
        backspacePointer = -1
        java.util.Arrays.fill(routeByPointer, ROUTE_NONE)
        java.util.Arrays.fill(downKeyByPointer, -1)
        pressedKeys.clear()
        trailRenderer.clear()
        burstRenderer.clear()
        invalidate()
    }

    private fun keyIndexAt(x: Float, y: Float): Int {
        for (i in keyRects.indices) {
            if (keyRects[i].contains(x, y)) return i
        }
        return -1
    }

    private companion object {
        const val MAX_POINTERS = 64
        const val ROUTE_NONE = 0
        const val ROUTE_ENGINE = 1
        const val ROUTE_SPECIAL = 2
        const val ROUTE_SPACE = 3
        const val ROUTE_BACKSPACE = 4
        const val ROUTE_MODE_HOLD = 5
        const val ROUTE_ALT_POPUP = 6
        const val ROUTE_CHORD = 7
        const val GEAR_GLYPH = "⚙"

        /** See the ?123 interaction table above. */
        const val CHORD_ARM_MS = 150L
    }
}
