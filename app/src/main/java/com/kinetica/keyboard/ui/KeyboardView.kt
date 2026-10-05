package com.kinetica.keyboard.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
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
import com.kinetica.keyboard.layout.LayoutMutations
import com.kinetica.keyboard.layout.LandscapeArrangement
import com.kinetica.keyboard.layout.LayoutTransforms
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.keys.BackspaceController
import com.kinetica.keyboard.keys.ChordTrigger
import com.kinetica.keyboard.keys.EdgeSwipeBinding
import com.kinetica.keyboard.keys.EdgeSwipeBindings
import com.kinetica.keyboard.keys.EdgeSwipeDetector
import com.kinetica.keyboard.keys.SpacebarCursorController
import kotlin.math.abs
import kotlin.math.hypot

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

        /** Spacebar slide: one cursor step left (-1) or right (+1), a whole word when [byWord]. */
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

        /** A letter's long-press menu was held still: open its list in settings (#8). */
        fun onEditAlternates(letter: Char) = Unit

        /**
         * A cell of the ?123 hold menu was chosen, by its index into [modeMenuCells].
         * The bar's vocabulary, but its own list: the two surfaces hold separate sets.
         */
        fun onMenuAction(index: Int)

        /** Synchronous query: does [key] have a chord under [trigger]? */
        fun hasChord(trigger: ChordTrigger, key: Char): Boolean

        /** [trigger] held [heldMs] and a chord-assigned key tapped: fire it. */
        fun onChordTriggered(trigger: ChordTrigger, key: Char, heldMs: Long)

        /** A chord key typed instead of firing, for the trace: the trigger lifted, or it moved. */
        fun onChordMissed(trigger: ChordTrigger, key: Char, reason: String) = Unit

        /** Any key-down; the service decides whether to vibrate. */
        fun onKeyPressFeedback()

        /**
         * Spacebar tapped inside its spaceless zone: end the word, write no space. Its own
         * callback, not a flag on [onKeyTap]: only the service knows what ending a word means.
         */
        fun onSpacelessSpace()

        /** Spacebar tapped twice in quick succession: end the sentence. Sent only while the setting is on. */
        fun onDoubleSpace()
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

    /** A second spacebar tap inside the double-tap window ends the sentence. */
    var doubleSpacePeriod: Boolean
        get() = spaceController.doubleSpacePeriod
        set(value) { spaceController.doubleSpacePeriod = value }

    /** Left 30% of the spacebar ends the word without writing a space. */
    var spacelessSpace: Boolean
        get() = spaceController.spacelessZone
        set(value) {
            if (spaceController.spacelessZone != value) {
                spaceController.spacelessZone = value
                renderStaticLayer()
                invalidate()
            }
        }

    /** Active edge-swipe shortcut set; swapped live on preference changes. */
    var edgeSwipeBindings: EdgeSwipeBindings = EdgeSwipeBindings.DEFAULTS

    /**
     * Cells for the ?123 hold menu, ordered and filtered by the service. Empty falls back to
     * the gear, so the hold is never a dead gesture.
     */
    var modeMenuCells: List<String> = emptyList()

    /** Threshold for long-press alternates on tap-dispatched keys. */
    var longPressMs = 500L

    /** ?123 lead-in before a letter tap counts as a chord. See the interaction table. */
    var chordArmMs = CHORD_ARM_MS_DEFAULT

    /** The spacebar's own lead-in, longer: it is pressed every word. */
    var spaceChordArmMs = SPACE_CHORD_ARM_MS_DEFAULT

    var layoutMode: LayoutMode = LayoutMode.FULL
        set(value) {
            if (field != value) {
                field = value
                cancelActivePointers()
                if (width > 0 && height > 0) rebuild()
            }
        }

    /**
     * Side inset in dp, from the setting. Applied to the key block only: the background still
     * paints edge to edge, so the inset reads as a margin around the keys, not a smaller keyboard.
     *
     * Declared above the init block because rebuild reads it: InitOrderTest fails a property
     * that a running init block would read as null.
     */
    var sidePadDp: Int = 0
        set(value) {
            val clamped = value.coerceIn(0, LayoutTransforms.MAX_SIDE_PAD_DP)
            if (field == clamped) return
            field = clamped
            if (width > 0 && height > 0) rebuild()
        }

    /**
     * The landscape arrangement, or null in portrait. Only the full layout mode takes it; the
     * others keep their own projection in both orientations.
     */
    var landscapeArrangement: LandscapeArrangement? = null
        set(value) {
            if (field == value) return
            field = value
            cancelActivePointers()
            if (width > 0 && height > 0) rebuild()
        }

    /**
     * Long-press grid width in cells (#8), or 0 for the strip above the key. The strip holds
     * [STRIP_MAX_CELLS] and wraps into a second row past that.
     */
    var popupColumns: Int = 0

    /** Landscape split gap, percent of the view width between the halves; set by the service. */
    var landscapeSplitGapPct: Int = 0
        set(value) {
            if (field == value) return
            field = value
            if (width > 0 && height > 0 && landscapeArrangement == LandscapeArrangement.SPLIT) rebuild()
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

    /**
     * Transient text across the spacebar after a shortcut, or null. The service owns the
     * timer and this only draws it: centred at the label size, where the spacebar has no
     * label of its own, so the dot and the language code keep their places.
     */
    var spacebarNotice: String? = null
        set(value) {
            if (field != value) {
                field = value
                renderStaticLayer()
                invalidate()
            }
        }

    /**
     * The action enter runs in this field, as a word on the key (Search, Send), or null for
     * the key's own glyph. The service owns it, per field.
     */
    var enterLabel: String? = null
        set(value) {
            if (field != value) {
                field = value
                renderStaticLayer()
                invalidate()
            }
        }

    /**
     * Words per minute at the spacebar's bottom-right, or null. The overlay draws it, not the
     * static layer, so it changes at every commit without redrawing every key.
     */
    var speedLabel: String? = null
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** True when enter runs an action, so its hold adds [LayoutMutations.NEWLINE_ALTERNATE] as the last cell. */
    var enterNewlineCell = false

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

    // Sized for the widest alphabet, indexed by the board's own letter codes.
    private val letterCenterX = FloatArray(Alphabet.MAX_SIZE)
    private val letterCenterY = FloatArray(Alphabet.MAX_SIZE)

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
    // Displacement at the furthest sample from the down point, per pointer. Read only by
    // EdgeSwipeDetector: a flick off the top row is short and curves back before the lift.
    private val peakDxByPointer = FloatArray(MAX_POINTERS)
    private val peakDyByPointer = FloatArray(MAX_POINTERS)
    private val downTimeByPointer = LongArray(MAX_POINTERS)
    private val pressedKeys = LinkedHashSet<Int>()

    // ------------------------------------------------------------ key popups
    //
    // One popup at a time, drawn above its anchor key and owned by one pointer: the ?123 hold
    // menu and long-press alternates. Hold detection runs on a timer so it fires mid-press;
    // 12dp of travel (the swipe threshold) cancels it, so it never steals a tap (a lift under
    // 150 ms beats the timer) or a swipe.
    //
    // A strip that would clamp onto its own row (top row, or a very short keyboard) renders in
    // a non-touchable PopupWindow above the keyboard edge, not under the thumb. Touch stays
    // view-local: PopupState.rect keeps view coordinates, so both paths select the same way.
    // The gear popup needs a lift inside its rect and never elevates (its anchor is on the
    // bottom row); a window failure falls back to the clamped canvas strip.

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
        /** Cell layout for the long-press grid; null for the one-row strip. */
        val grid: PopupGrid? = null,
        val cellH: Float = 0f,
        val originY: Float = 0f,
    )

    private var popup: PopupState? = null
    private var popupPointer = -1
    private var pendingHoldPid = -1
    private var pendingHoldKeyIdx = -1
    private var popupWindow: PopupWindow? = null
    private var popupStrip: PopupStripView? = null

    /** Render surface for elevated popups: draws the canvas path's strip, translated to the
     *  window's origin, so the two cannot drift apart. */
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
    // Four gestures share the mode key, told apart by time and travel:
    //  tap        lift before longPressMs, <12dp travel        -> symbols
    //  slide      >=30dp travel, dominant horizontal           -> numpad
    //  hold       >=longPressMs stationary, no other key       -> gear popup
    //  chord      held >=CHORD_ARM_MS and a chord key tapped   -> its chord
    // chordArmMs (150 ms default, settable 0-300) stops a two-thumb typist who brushes ?123
    // with a letter from firing a chord. Every chord pays it as a lead-in and a letter inside
    // it types normally; it is not measured, so it is a setting. A chord consumes both
    // touches: the letter never reaches the engine and the ?123 lift switches no layer. Only
    // assigned keys chord (none by default).
    //
    // The spacebar is a trigger too, held still past spaceChordArmMs; a slide or 12dp of
    // travel ends it as one. Both decide at the chord key's lift: a key that lands while a
    // trigger is armed and has a chord there is a candidate, and fires only if the trigger is
    // still held and unmoved and the key did not travel. Otherwise it types, so a thumb that
    // leaves the trigger first loses nothing; a letter candidate stays on the gesture path for
    // that reason. A fired chord consumes the trigger's lift: no layer switch, no space.
    private var modeHoldPointer = -1
    private var modeHoldDownTime = 0L
    private var modeHoldMoved = false
    private var modeChordFired = false
    private var spaceHoldDownTime = 0L
    private var spaceHoldMoved = false
    private var spaceChordFired = false
    private val chordTriggerByPointer = arrayOfNulls<ChordTrigger>(MAX_POINTERS)
    private val holdHandler = Handler(Looper.getMainLooper())
    private val holdRunnable = Runnable { onHoldTimerFired() }

    /** Whether holding a letter's menu still opens its list in settings (#8); off unless chosen. */
    var editFromMenu = false
    private var editPid = -1
    private var editLetter = ' '
    private var editX = 0f
    private var editY = 0f
    private val editRunnable = Runnable {
        val pid = editPid
        editPid = -1
        if (pid == -1 || pid != popupPointer) return@Runnable
        dismissPopup()
        // The lift that follows must type nothing.
        routeByPointer[pid] = ROUTE_NONE
        listener?.onEditAlternates(editLetter)
    }

    private fun cancelEditHold() {
        if (editPid != -1) {
            holdHandler.removeCallbacks(editRunnable)
            editPid = -1
        }
    }
    private val holdSlopPx = KineticaConstants.TAP_MAX_DISP_DP * density

    private val popupBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val popupSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ------------------------------------------------ staged-delete preview
    //
    // The backspace slide touches the editor only at lift; until then the staged span shows
    // struck through in a chip above the backspace key. Drawn in the IME's own window, not as
    // a composing region, so it renders the same in every app: the text model is commit-only
    // and some editors drop or restyle composing spans.
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

    // The tutor's ghost path (GhostPath), drawn over the letters.
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val ghostDot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ghostLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val ghostPath = android.graphics.Path()

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
        if (code in letterCenterX.indices) {
            burstRenderer.spawn(letterCenterX[code], letterCenterY[code], SystemClock.uptimeMillis())
            ensureAnimating()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            rebuild()
            claimEdgeGestures(w, h)
        }
    }

    /**
     * Asks the system not to read a swipe from this view's left or right edge as the back gesture.
     *
     * With gesture navigation on, the bottom row's outer keys otherwise need aiming away from the
     * edges. Unlike the side margin it costs no width, so it is always on and not a setting.
     *
     * Unverified: the platform caps the exclusion per edge (documented as 200dp), so a tall
     * keyboard may be covered only in part, and whether it applies to an IME window has not been
     * observed on a device. Asking for more than the cap is safe: the system clamps it.
     */
    private fun claimEdgeGestures(w: Int, h: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        GhostPath.attach(this)
    }

    override fun onDetachedFromWindow() {
        cancelActivePointers()
        GhostPath.detach(this)
        super.onDetachedFromWindow()
    }

    private fun rebuild() {
        val l = layout ?: return
        keyRects.clear()
        keyInsets.clear()
        val w = width.toFloat()
        val h = height.toFloat()
        val sidePad = LayoutTransforms.sidePadPx(sidePadDp, density, w)
        val arrangement = landscapeArrangement
            ?.takeIf { it != LandscapeArrangement.STRETCH && layoutMode == LayoutMode.FULL }
        val letter = l.keys.firstOrNull { it.isLetter }
        val minKeyPx = LayoutTransforms.LANDSCAPE_MIN_KEY_DP * density
        val blockPx = when {
            arrangement == null || letter == null -> 0f
            arrangement == LandscapeArrangement.SPLIT -> LayoutTransforms.splitBlockPx(
                w, sidePad, landscapeSplitGapPct / 100f, letter.w, minKeyPx,
            )
            else -> LayoutTransforms.landscapeBlockPx(w, h, sidePad, letter.w, letter.h, minKeyPx)
        }
        for (k in l.keys) {
            val rect = if (arrangement != null && blockPx > 0f) {
                LayoutTransforms.applyLandscape(k, w, h, sidePad, arrangement, blockPx)
            } else {
                LayoutTransforms.apply(layoutMode, k, w, h, sidePad)
            }
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
            val code = k.letterCode
            codes.add(code)
            letterCenterX[code] = r.centerX()
            letterCenterY[code] = r.centerY()
            if (r.width() < minLetterW) minLetterW = r.width()
        }
        engineActive = rects.isNotEmpty()
        if (!engineActive || minLetterW <= 0f) return
        // The stream-split line is the centre of the letter block, not of the view: with a side
        // inset, half the view width would put the divider off centre and misassign a thumb.
        var blockLeft = Float.MAX_VALUE
        var blockRight = 0f
        for (r in rects) {
            if (r[0] < blockLeft) blockLeft = r[0]
            if (r[2] > blockRight) blockRight = r[2]
        }
        val g = KeyboardGeometry.fromPx(
            minLetterW, (blockLeft + blockRight) / 2f, rects, codes.toIntArray(), l.alphabet,
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
        // Chromeless keys (apostrophe) paint no background, only the glyph, so they blend in
        // Nintype-style.
        if (!key.chromeless) {
            val paint = if (key.type == KeyType.CHAR) keyPaint else specialKeyPaint
            c.drawRoundRect(inset, cornerPx, cornerPx, paint)
        }

        val action = if (key.type == KeyType.ENTER) enterLabel else null
        if (action != null) {
            // Shrunk to fit like the spacebar notice: an app's own label has no length bound.
            drawSpacebarNotice(c, action, inset)
        }
        val label = when {
            key.type == KeyType.SPACE -> ""
            action != null -> ""
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
        // A line at the spaceless zone's edge, since the zone has no other visible boundary.
        // Ambient alpha, not the dot's: it is a standing affordance, not a state.
        if (key.type == KeyType.SPACE && spaceController.spacelessZone) {
            hintPaint.alpha = 102
            val edge = inset.left + inset.width() * SpacebarCursorController.SPACELESS_FRACTION
            c.drawLine(edge, inset.top + inset.height() * 0.30f, edge, inset.bottom - inset.height() * 0.30f, hintPaint)
        }
        if (key.type == KeyType.SPACE && autospaceDot) {
            hintPaint.alpha = 255
            c.drawCircle(inset.centerX(), inset.top + inset.height() * 0.22f, 2.5f * density, hintPaint)
        }
        if (key.type == KeyType.SPACE) {
            spacebarNotice?.let { drawSpacebarNotice(c, it, inset) }
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

    /**
     * [text] centred on the spacebar, shrunk to fit its width. Measured, unlike the key labels:
     * a notice can be a language name or a layout mode, which nothing bounds at three characters.
     */
    private fun drawSpacebarNotice(c: Canvas, text: String, inset: RectF) {
        labelPaint.textSize = inset.height() * 0.32f
        val maxW = inset.width() * 0.9f
        val w = labelPaint.measureText(text)
        if (w > maxW) labelPaint.textSize *= maxW / w
        val baseline = inset.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
        c.drawText(text, inset.centerX(), baseline, labelPaint)
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
        drawSpeed(canvas)
        drawGhost(canvas)
        popup?.let { if (!it.elevated) drawPopup(canvas, it) }
        deletePreview?.let { drawDeletePreview(canvas, it) }
    }

    /** The speed in the spacebar's free corner; not while it is held or a notice covers it. */
    private fun drawSpeed(canvas: Canvas) {
        val text = speedLabel ?: return
        if (spacebarNotice != null) return
        val l = layout ?: return
        val idx = l.keys.indexOfFirst { it.type == KeyType.SPACE }
        if (idx < 0 || idx !in keyInsets.indices || idx in pressedKeys) return
        val inset = keyInsets[idx]
        hintPaint.alpha = 102
        hintPaint.textSize = inset.height() * 0.22f
        hintPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(text, inset.right - 6f * density, inset.bottom - 4f * density, hintPaint)
    }

    /**
     * The tutor's word drawn over the letters, one colour per thumb and numbered in writing
     * order, a tap as a ring; only while the letters are on screen.
     */
    private fun drawGhost(canvas: Canvas) {
        val strokes = GhostPath.strokes
        // The tutor's words are English, so its ghost is drawn on a Latin board only.
        if (strokes.isEmpty() || !engineActive || layout?.alphabet != Alphabet.LATIN) return
        ghostPaint.strokeWidth = 5f * density
        ghostLabel.textSize = 9f * density
        for ((i, s) in strokes.withIndex()) {
            val pts = s.letters.mapNotNull { c ->
                val code = Alphabet.LATIN.codeOf(c)
                if (code in 0 until Alphabet.LETTERS) letterCenterX[code] to letterCenterY[code] else null
            }
            if (pts.isEmpty()) continue
            val color = if (s.left) theme.accent else theme.suggestionPrimary
            ghostPaint.color = color
            // Faint, so the letter reads through it.
            ghostPaint.alpha = 90
            if (pts.size == 1) {
                canvas.drawCircle(pts[0].first, pts[0].second, 9f * density, ghostPaint)
            } else {
                ghostPath.reset()
                ghostPath.moveTo(pts[0].first, pts[0].second)
                for (p in pts.drop(1)) ghostPath.lineTo(p.first, p.second)
                canvas.drawPath(ghostPath, ghostPaint)
            }
            // The order badge beside the first key, up and left, never on its letter.
            val bx = pts[0].first - 12f * density
            val by = pts[0].second - 12f * density
            ghostDot.color = color
            canvas.drawCircle(bx, by, 6f * density, ghostDot)
            ghostLabel.color = theme.background
            val base = by - (ghostLabel.descent() + ghostLabel.ascent()) / 2f
            canvas.drawText("${i + 1}", bx, base, ghostLabel)
        }
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
        val g = p.grid
        if (g != null) {
            drawPopupGrid(canvas, p, g)
            return
        }
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

    /** Each filled cell on its own background, so a partial row reads as a shape, not a hole. */
    private fun drawPopupGrid(canvas: Canvas, p: PopupState, g: PopupGrid) {
        labelPaint.textSize = p.cellH * 0.45f
        val inset = 2f * density
        val gap = 1f * density
        for (i in p.cells.indices) {
            val l = p.rect.left + p.cellW * g.colOf(i)
            val t = p.rect.top + p.cellH * g.rowOf(i)
            canvas.drawRoundRect(
                l + gap, t + gap, l + p.cellW - gap, t + p.cellH - gap,
                cornerPx, cornerPx, popupBgPaint,
            )
            if (i == p.selected) {
                canvas.drawRoundRect(
                    l + inset, t + inset, l + p.cellW - inset, t + p.cellH - inset,
                    cornerPx, cornerPx, popupSelectedPaint,
                )
            }
            val baseline = t + p.cellH / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f
            canvas.drawText(p.cells[i], l + p.cellW / 2f, baseline, labelPaint)
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

        // The symbol pages arm from their page key, as the letters do from ?123.
        if ((key?.type == KeyType.MODE_SYMBOLS || key?.type == KeyType.MODE_SYMBOLS2) && modeHoldPointer == -1) {
            modeHoldPointer = pid
            modeHoldDownTime = t
            modeHoldMoved = false
            modeChordFired = false
        }

        // Decided here, fired or typed at the lift: see the interaction table.
        val chord = key?.chordChar?.let { c ->
            armedTriggers(t).firstOrNull { listener?.hasChord(it, c) == true }
        }
        chordTriggerByPointer[pid] = chord
        routeByPointer[pid] = when {
            // The engine never takes a symbol or a digit, so those wait for their lift here.
            chord != null && key?.isLetter != true -> ROUTE_CHORD
            engineActive && engine?.onPointerDown(pid, x, y, t) == true -> {
                val stream = engine?.streamIdOf(pid)
                if (stream != null && !zenMode && trailsEnabled && chord == null) {
                    trailRenderer.startStream(stream)
                    // The board's own letter code: a Cyrillic key's char minus 'a' runs past the array.
                    val code = key?.letterCode?.takeIf { it in letterCenterX.indices }
                    if (code != null) {
                        burstRenderer.spawn(letterCenterX[code], letterCenterY[code], t)
                        ensureAnimating()
                    }
                }
                ROUTE_ENGINE
            }
            chord != null -> ROUTE_CHORD
            key?.type == KeyType.SPACE && spacePointer == -1 -> {
                spacePointer = pid
                spaceHoldDownTime = t
                spaceHoldMoved = false
                spaceChordFired = false
                val r = keyRects.getOrNull(keyIdx)
                spaceController.onDown(x, r?.left ?: 0f, r?.width() ?: 0f)
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
        // A chord candidate's own menu would open mid-chord and cancel the key.
        if (holdCapable && chord == null && popup == null && pendingHoldPid == -1) {
            scheduleHold(pid, keyIdx)
        }
        if (keyIdx != -1) {
            pressedKeys.add(keyIdx)
            invalidate()
        }
    }

    /** The triggers armed at [t], `?123` first: each rests in place past its own lead-in. */
    private fun armedTriggers(t: Long): List<ChordTrigger> = armedChordTriggers(
        modeArmed = chordArms(modeHoldPointer != -1, modeHoldMoved, t - modeHoldDownTime, chordArmMs),
        spaceArmed = chordArms(spacePointer != -1, spaceHoldMoved, t - spaceHoldDownTime, spaceChordArmMs),
    )

    private fun triggerHeld(trigger: ChordTrigger): Boolean = when (trigger) {
        ChordTrigger.MODE -> modeHoldPointer != -1
        ChordTrigger.SPACE -> spacePointer != -1
    }

    private fun triggerMoved(trigger: ChordTrigger): Boolean = when (trigger) {
        ChordTrigger.MODE -> modeHoldMoved
        ChordTrigger.SPACE -> spaceHoldMoved
    }

    /** What a chord candidate's lift does, and the trace line when it types instead. */
    private fun chordLift(pid: Int, dx: Float, dy: Float): ChordLift? {
        val trigger = chordTriggerByPointer[pid] ?: return null
        val c = keyAtDown(pid)?.chordChar ?: return null
        val lift = chordAtLift(triggerHeld(trigger), triggerMoved(trigger), dx * dx + dy * dy, holdSlopPx * holdSlopPx)
        if (lift != ChordLift.FIRE) listener?.onChordMissed(trigger, c, lift.name.lowercase())
        return lift
    }

    private fun fireChord(pid: Int, t: Long) {
        val trigger = chordTriggerByPointer[pid] ?: return
        val c = keyAtDown(pid)?.chordChar ?: return
        when (trigger) {
            ChordTrigger.MODE -> {
                modeChordFired = true
                // The gear popup (if it already appeared) yields to the chord; further keys
                // can chord in this hold.
                cancelHold()
                dismissPopup()
                listener?.onChordTriggered(trigger, c, t - modeHoldDownTime)
            }
            ChordTrigger.SPACE -> {
                spaceChordFired = true
                listener?.onChordTriggered(trigger, c, t - spaceHoldDownTime)
            }
        }
    }

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
                // requireInside stays: the finger has to travel up into the strip, so a hold that
                // lifts without moving does nothing and a menu of several cells is safe.
                showPopup(
                    keyIdx, modeMenuCells.ifEmpty { listOf(GEAR_GLYPH) },
                    selected = -1, requireInside = true,
                )
            }
            key.type == KeyType.ENTER && key.alternates.isNotEmpty() && route == ROUTE_SPECIAL -> {
                // Enter's popup shows only its alternates: the base glyph would commit "⏎"
                // through onKeyAlternate. The primary (first alternate) sits over the key as
                // the rightmost cell, pre-selected, so a plain hold-and-lift commits it and
                // sliding left reaches the others.
                routeByPointer[pid] = ROUTE_ALT_POPUP
                popupPointer = pid
                val cells = enterCells(key)
                showPopup(
                    keyIdx, cells, selected = cells.lastIndex,
                    requireInside = false, originX = downXByPointer[pid],
                    anchorRightCell = true,
                )
            }
            key.type == KeyType.SHIFT && key.alternates.isNotEmpty() && route == ROUTE_SPECIAL -> {
                // Shift's popup shows only its cells, like enter's: the base glyph would be a
                // fourth cell committing "⇧" as text. Centred, not right-anchored: shift is the
                // leftmost key and a strip hung off its right edge clamps against the screen.
                // Nothing is pre-selected: the cells re-case text that already exists, so a hold
                // that lifts without sliding leaves the word alone. Enter pre-selects because
                // its popup has a primary the key already advertises.
                routeByPointer[pid] = ROUTE_ALT_POPUP
                popupPointer = pid
                showPopup(
                    keyIdx, key.alternates, selected = -1,
                    requireInside = false, originX = downXByPointer[pid],
                )
            }
            key.alternates.isNotEmpty() && (route == ROUTE_ENGINE || route == ROUTE_SPECIAL) -> {
                if (route == ROUTE_ENGINE) {
                    // Travel past the tap threshold cancels the hold timer, so a committed swipe
                    // cannot get here; the check guards against event-order races.
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
                val cells = listOf(base) + alts
                // Any letter has a list to edit, in any script; a digit or symbol has none.
                if (key.isLetter && editFromMenu) {
                    cancelEditHold()
                    editPid = pid
                    editLetter = key.output[0]
                    editX = downXByPointer[pid]
                    editY = downYByPointer[pid]
                    holdHandler.postDelayed(editRunnable, EDIT_HOLD_MS)
                }
                when {
                    popupColumns > 0 -> showGridPopup(
                        keyIdx, cells, selected = 1,
                        originX = downXByPointer[pid], originY = downYByPointer[pid],
                        columns = popupColumns,
                    )
                    cells.size > STRIP_MAX_CELLS -> showGridPopup(
                        keyIdx, cells, selected = 1,
                        originX = downXByPointer[pid], originY = downYByPointer[pid],
                        columns = 0,
                    )
                    else -> showPopup(
                        keyIdx, cells, selected = 1,
                        requireInside = false, originX = downXByPointer[pid],
                    )
                }
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
        // anchorRightCell puts the last cell over the anchor and extends the strip left, so
        // enter's primary sits on the key and the alternates are a slide left. Otherwise it centres.
        val rawLeft = if (anchorRightCell) {
            anchor.centerX() - w + cellW / 2f
        } else {
            anchor.centerX() - w / 2f
        }
        val left = rawLeft.coerceIn(margin, width - w - margin)
        // Above the key when there is room. Otherwise (top row, or a very short keyboard) the
        // strip would clamp onto its own row under the thumb, so an alternates popup renders in
        // the elevated window at the unclamped position. The state rect stays clamped and
        // view-local, because selection reads it. Gear popups (requireInside) never elevate:
        // their commit test is a lift inside this rect.
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
     * A long-press grid. With [columns] it rises from the pressed key, the letter's cell over the
     * key so the thumb starts on it; with 0 it is the strip wrapped into rows, placed where the
     * strip is. Its rect is where it is drawn, on the canvas or in the elevated window, because
     * selection reads both axes and a finger may leave the view upward to reach the top row.
     */
    private fun showGridPopup(
        anchorIdx: Int,
        cells: List<String>,
        selected: Int,
        originX: Float,
        originY: Float,
        columns: Int,
    ) {
        if (anchorIdx !in keyRects.indices || cells.isEmpty()) return
        val anchor = keyRects[anchorIdx]
        val margin = 4f * density
        val wide = if (columns > 0) columns else minOf(cells.size, STRIP_MAX_CELLS)
        val cell = maxOf(anchor.width(), 48f * density).coerceAtMost((width - 2f * margin) / wide)
        val grid = if (columns > 0) {
            val col = PopupGrid.letterColumn(columns, anchor.centerX(), cell, width.toFloat(), margin)
            PopupGrid.rising(cells.size, columns, col)
        } else {
            PopupGrid.wrapped(cells.size, STRIP_MAX_CELLS)
        }
        val w = cell * grid.cols
        val h = cell * grid.rows
        val rawLeft: Float
        val rawTop: Float
        if (columns > 0) {
            rawLeft = anchor.centerX() - cell * (grid.colOf(0) + 0.5f)
            rawTop = anchor.centerY() - cell * (grid.rowOf(0) + 0.5f)
        } else {
            rawLeft = anchor.centerX() - w / 2f
            rawTop = anchor.top - h - 8f * density
        }
        // Held inside the view on the sides and the bottom, which the IME window ends at; only
        // the top may leave it, into the elevated window.
        val left = rawLeft.coerceIn(margin, (width - w - margin).coerceAtLeast(margin))
        val desiredTop = rawTop.coerceAtMost(height - h - margin)
        val elevated = desiredTop < margin && showElevatedPopup(left, desiredTop, w, h)
        val top = if (elevated) desiredTop else desiredTop.coerceAtLeast(margin)
        popup = PopupState(
            cells, RectF(left, top, left + w, top + h), cell, selected,
            requireInside = false, originX = originX, elevated = elevated,
            grid = grid, cellH = cell, originY = originY,
        )
        popupStrip?.invalidate()
        invalidate()
    }

    /**
     * Shows the render-only window for a strip whose unclamped position extends above the view.
     * Non-touchable and non-focusable, so all input stays on this view; clipping disabled so it
     * may leave the IME window; placed in window coordinates so a negative top is legal. Returns
     * false, for the canvas fallback, when the window cannot be shown (a detached view or dead
     * token, both seen in IME teardown races).
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
        cancelEditHold()
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
        val g = p.grid
        if (g != null) {
            // Engaged by travel in any direction: the grid's cells lie on both axes.
            if (!p.engaged) {
                if (hypot(x - p.originX, y - p.originY) < holdSlopPx) return
                p.engaged = true
            }
            val sel = g.itemUnder(x, y, p.rect.left, p.rect.top, p.cellW, p.cellH)
            if (sel != p.selected) {
                p.selected = sel
                if (p.elevated) popupStrip?.invalidate()
                invalidate()
            }
            return
        }
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
            if (pid == pendingHoldPid || (pid == modeHoldPointer && !modeHoldMoved) ||
                (pid == spacePointer && !spaceHoldMoved)
            ) {
                val dx = x - downXByPointer[pid]
                val dy = y - downYByPointer[pid]
                if (dx * dx + dy * dy > holdSlopPx * holdSlopPx) {
                    if (pid == pendingHoldPid) cancelHold()
                    if (pid == modeHoldPointer) modeHoldMoved = true
                    if (pid == spacePointer) spaceHoldMoved = true
                }
            }
            when (routeByPointer[pid]) {
                ROUTE_ENGINE -> if (e != null) {
                    e.onPointerMove(pid, x, y, ev.eventTime)
                    addTrailPoint(e, pid, x, y, ev.eventTime)
                }
                // After a spacebar chord the touch is spent: it neither slides nor arms again.
                ROUTE_SPACE -> if (!spaceChordFired) {
                    spaceController.onMove(x)
                    if (spaceController.sliding) spaceHoldMoved = true
                }
                ROUTE_BACKSPACE -> backspaceController.onMove(x)
                ROUTE_SPECIAL -> maybeArmEnterPopup(pid, x, y)
                ROUTE_MODE_HOLD, ROUTE_ALT_POPUP -> if (pid == popupPointer) {
                    // Choosing a cell is not holding still.
                    if (pid == editPid) {
                        val ex = x - editX
                        val ey = y - editY
                        if (ex * ex + ey * ey > holdSlopPx * holdSlopPx) cancelEditHold()
                    }
                    updatePopupSelection(x, y)
                }
            }
        }
    }

    /**
     * Keeps the displacement at this pointer's furthest sample from its down point. Radial, so
     * one pair of values serves all four directions; compared squared, so the move path takes no roots.
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
            // Read before the stream is cancelled or finished below; after that the engine no
            // longer knows where this pointer has been.
            EdgeSwipeDetector.detect(
                it, dx, dy, peakDxByPointer[pid], peakDyByPointer[pid], density,
                edgeSwipeBindings, engine?.contactCount(pid) ?: 1,
            )
        }

        when (routeByPointer[pid]) {
            ROUTE_ENGINE -> {
                if (chordLift(pid, dx, dy) == ChordLift.FIRE) {
                    // The letter was a chord after all: it never becomes a token.
                    engine?.cancelPointer(pid)
                    fireChord(pid, t)
                } else if (shortcut != null) {
                    // A designated edge swipe wins over gesture decoding.
                    engine?.cancelPointer(pid)
                    listener?.onEdgeSwipe(shortcut)
                } else {
                    engine?.onPointerUp(pid, x, y, t)
                }
            }
            ROUTE_SPACE -> {
                if (spaceChordFired) spaceController.consume() else when (spaceController.onUp(t)) {
                    SpacebarCursorController.Lift.SPACE -> dispatchTap(pid, t)
                    SpacebarCursorController.Lift.SPACELESS -> listener?.onSpacelessSpace()
                    SpacebarCursorController.Lift.DOUBLE -> listener?.onDoubleSpace()
                    SpacebarCursorController.Lift.SLIDE -> Unit
                }
                spacePointer = -1
                spaceHoldMoved = false
                spaceChordFired = false
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
                    modeChordFired && (key?.type == KeyType.MODE_SYMBOLS || key?.type == KeyType.MODE_SYMBOLS2) -> Unit
                    shortcut != null -> listener?.onEdgeSwipe(shortcut)
                    modeSlide != null -> listener?.onModeSlide(modeSlide)
                    else -> dispatchTap(pid, t)
                }
            }
            ROUTE_MODE_HOLD -> {
                // Snapshot before dismissing, as the alternates branch does: dismissPopup nulls
                // the state this reads.
                val p = popup
                dismissPopup()
                if (p != null && p.selected in p.cells.indices && !modeChordFired) {
                    if (modeMenuCells.isEmpty()) {
                        listener?.onSettingsRequested()
                    } else {
                        listener?.onMenuAction(p.selected)
                    }
                }
            }
            ROUTE_ALT_POPUP -> {
                val p = popup
                dismissPopup()
                if (p != null && p.selected in p.cells.indices) {
                    keyAtDown(pid)?.let { listener?.onKeyAlternate(it, p.cells[p.selected]) }
                }
            }
            ROUTE_CHORD -> {
                // A trigger let go first means the key was typed, not chorded: it types now.
                if (chordLift(pid, dx, dy) == ChordLift.FIRE) fireChord(pid, t) else dispatchTap(pid, t)
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
        chordTriggerByPointer[pid] = null
        downKeyByPointer[pid] = -1
    }

    /**
     * A decisive up-slide on enter, before the hold timer, opens its alternates with "?"
     * pre-selected; a sideways slide then picks "!" or "," through [updatePopupSelection], and a
     * straight-up lift keeps "?". Skipped when enter-up is rebound to anything but "?", so that
     * edge swipe still fires on lift. The 30dp vertical gate mirrors [detectModeSlide]'s
     * horizontal one; dominance keeps it clear of enter's slide-left mode switch.
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
        val cells = enterCells(key)
        showPopup(
            downKeyByPointer[pid], cells, selected = cells.lastIndex,
            requireInside = false, originX = downXByPointer[pid],
            anchorRightCell = true,
        )
    }

    /**
     * Enter's popup cells, primary last so it sits on the key. The newline goes furthest
     * from it: the primary stays what the user set, and the newline is one slide away.
     */
    private fun enterCells(key: Key): List<String> =
        (key.alternates + if (enterNewlineCell) listOf(LayoutMutations.NEWLINE_ALTERNATE) else emptyList()).reversed()

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
        spaceHoldMoved = false
        spaceChordFired = false
        backspacePointer = -1
        java.util.Arrays.fill(routeByPointer, ROUTE_NONE)
        java.util.Arrays.fill(chordTriggerByPointer, null)
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
        /** The strip's width in cells; it wraps into a second row past this (#8). */
        const val STRIP_MAX_CELLS = 9
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

        /** How long a letter's menu rests still before it opens for editing: well past a slow pick. */
        const val EDIT_HOLD_MS = 1200L

    }
}

/**
 * Default `?123` lead-in, in ms. Mirrors `Prefs.DEFAULT_CHORD_ARM_MS`, the fresh-install value;
 * `ChordArmTest` keeps the two equal.
 */
internal const val CHORD_ARM_MS_DEFAULT = 150L

/** Default spacebar lead-in, in ms; mirrors `Prefs.DEFAULT_SPACE_CHORD_ARM_MS` (`ChordArmTest`). */
internal const val SPACE_CHORD_ARM_MS_DEFAULT = 50L

/** The triggers armed, in the order a chord key asks them: `?123` first. */
internal fun armedChordTriggers(modeArmed: Boolean, spaceArmed: Boolean): List<ChordTrigger> = when {
    modeArmed && spaceArmed -> listOf(ChordTrigger.MODE, ChordTrigger.SPACE)
    modeArmed -> listOf(ChordTrigger.MODE)
    spaceArmed -> listOf(ChordTrigger.SPACE)
    else -> emptyList()
}

/** What a chord candidate's lift does: fire, or type for the reason named. */
internal enum class ChordLift { FIRE, LIFTED, MOVED, SWIPED }

/**
 * A candidate fires only while its trigger is still held and unmoved and the key itself was a
 * tap. That settles rollover: a thumb that leaves the trigger before the other lifts was typing,
 * not chording.
 */
internal fun chordAtLift(triggerHeld: Boolean, triggerMoved: Boolean, travelSq: Float, slopSq: Float): ChordLift = when {
    !triggerHeld -> ChordLift.LIFTED
    triggerMoved -> ChordLift.MOVED
    travelSq > slopSq -> ChordLift.SWIPED
    else -> ChordLift.FIRE
}

/**
 * Whether a trigger is armed: held, unmoved, and down for at least [armMs]. A free function
 * because the view has no JVM test harness.
 *
 * Evaluated once at the chord key's down, so a key that lands early is never promoted later
 * however long it is held. [heldMs] is how long the trigger has been down; [modeMoved] latches
 * once it travels past the slop, so a slide cannot also fire a chord.
 */
internal fun chordArms(
    modeHeld: Boolean,
    modeMoved: Boolean,
    heldMs: Long,
    armMs: Long,
): Boolean = modeHeld && !modeMoved && heldMs >= armMs
