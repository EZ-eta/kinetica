package com.kinetica.keyboard.keys

import android.os.Handler

/**
 * Backspace behaviors: a tap deletes one char; holding past the arm delay repeats char deletion
 * at 50 ms; sliding left stages one unit per threshold crossed without deleting anything. The
 * staged span is highlighted in a preview chip and deleted at lift. Sliding back right retracts it
 * unit by unit, and lifting with nothing staged is a no-op, so a slide too far is recoverable.
 *
 * A unit is a whole word by default, or a single character when [charMode] is set. Only the
 * threshold and the meaning of the count change; staging, retraction and lift are shared, so the
 * mode is a flag and not a second gesture. A held shift may swap it until the first unit
 * ([BackspaceUnit]).
 */
class BackspaceController(
    private val density: Float,
    private val handler: Handler,
    /** One deletion: true for a tap, false for a tick of the hold's repeat. */
    private val onDeleteChar: (Boolean) -> Unit,
    /**
     * Staged unit count changed; 0 clears the preview without deleting. The granularity is the
     * one in force at the time of the event, so the receiver needs no second copy of the
     * settings to know what the count means.
     */
    private val onStageUnits: (Int, DeleteSpan.Granularity) -> Unit,
    /** Pointer lifted with a staged span: delete that span. */
    private val onCommitStaged: () -> Unit,
) {
    private val unit = BackspaceUnit()

    /**
     * Stage single characters instead of whole words. Pushed from
     * `KeyboardConfig` on every preference change, like the hold-arm delay.
     */
    var charMode: Boolean
        get() = unit.charMode
        set(value) { unit.charMode = value }

    /** A staged word stops at punctuation, so a link goes a piece at a time. Ignored in [charMode]. */
    var stopsAtPunctuation: Boolean
        get() = unit.stopsAtPunctuation
        set(value) { unit.stopsAtPunctuation = value }

    /** A held shift swaps [charMode] until the first unit is staged. */
    var flipped: Boolean
        get() = unit.flipped
        set(value) { unit.flipped = value }

    private val granularity: DeleteSpan.Granularity get() = unit.granularity

    /** Travel per staged word, in dp; a character's step follows it (DeleteSpan.slideDpPerUnit). */
    var wordStepDp = DeleteSpan.WORD_STEP_DP.toFloat()

    private var startX = 0f
    private var stagedUnits = 0
    private var everStaged = false
    private var repeating = false

    private val repeatRunnable = object : Runnable {
        override fun run() {
            repeating = true
            onDeleteChar(false)
            handler.postDelayed(this, REPEAT_MS)
        }
    }

    fun onDown(x: Float, holdArmMs: Long) {
        startX = x
        stagedUnits = 0
        everStaged = false
        repeating = false
        unit.release()
        handler.postDelayed(repeatRunnable, holdArmMs)
    }

    fun onMove(x: Float) {
        val threshold = DeleteSpan.slideDpPerUnit(unit.chars, wordStepDp) * density
        val crossings = ((startX - x) / threshold).toInt().coerceAtLeast(0)
        if (crossings != stagedUnits) {
            if (crossings > 0) {
                // Sliding cancels the hold-repeat: the two must not stack.
                handler.removeCallbacks(repeatRunnable)
                everStaged = true
                unit.fix()
            }
            stagedUnits = crossings
            onStageUnits(crossings, granularity)
        }
    }

    fun onUp() {
        handler.removeCallbacks(repeatRunnable)
        when {
            stagedUnits > 0 -> onCommitStaged()
            // Slid out and back to zero: an explicit cancel, not a tap. A
            // finished hold-repeat run also must not add a tap delete.
            everStaged || repeating -> Unit
            else -> onDeleteChar(true)
        }
        stagedUnits = 0
        everStaged = false
        unit.release()
    }

    fun cancel() {
        handler.removeCallbacks(repeatRunnable)
        if (everStaged) onStageUnits(0, granularity)
        stagedUnits = 0
        everStaged = false
        unit.release()
    }


    companion object {
        /** The hold's step; the editing page's held keys repeat at the same pace (HoldRepeat). */
        const val REPEAT_MS = 50L
    }
}
