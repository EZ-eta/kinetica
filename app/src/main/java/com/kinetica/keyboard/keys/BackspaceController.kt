package com.kinetica.keyboard.keys

import android.os.Handler

/**
 * Backspace behaviors: tap deletes one char; holding past the arm delay
 * repeats char deletion at 50ms; sliding left STAGES one unit per threshold
 * crossed without deleting anything - the staged span is highlighted in a
 * preview chip and committed only at lift. Sliding back right retracts the
 * staged span unit by unit; lifting with nothing staged is a no-op, so a
 * slide too far is always recoverable.
 *
 * A unit is a whole word by default, or a single character when [charMode] is
 * set. Only the threshold and the meaning of the count change - the staging
 * protocol, the retraction and the lift semantics are shared, which is the whole
 * reason the mode is a flag here rather than a second gesture.
 */
class BackspaceController(
    private val density: Float,
    private val handler: Handler,
    private val onDeleteChar: () -> Unit,
    /**
     * Staged unit count changed; 0 clears the preview without deleting. The
     * boolean is [charMode] at the time of the event, so the receiver never has
     * to consult a second copy of the setting to know what the count means.
     */
    private val onStageUnits: (Int, Boolean) -> Unit,
    /** Pointer lifted with a staged span: delete exactly that span. */
    private val onCommitStaged: () -> Unit,
) {
    /**
     * Stage single characters instead of whole words. Pushed from
     * `KeyboardConfig` on every preference change, like the hold-arm delay.
     */
    var charMode = false

    private var startX = 0f
    private var stagedUnits = 0
    private var everStaged = false
    private var repeating = false

    private val repeatRunnable = object : Runnable {
        override fun run() {
            repeating = true
            onDeleteChar()
            handler.postDelayed(this, REPEAT_MS)
        }
    }

    fun onDown(x: Float, holdArmMs: Long) {
        startX = x
        stagedUnits = 0
        everStaged = false
        repeating = false
        handler.postDelayed(repeatRunnable, holdArmMs)
    }

    fun onMove(x: Float) {
        val threshold = DeleteSpan.slideDpPerUnit(charMode) * density
        val crossings = ((startX - x) / threshold).toInt().coerceAtLeast(0)
        if (crossings != stagedUnits) {
            if (crossings > 0) {
                // Sliding cancels the hold-repeat: the two must not stack.
                handler.removeCallbacks(repeatRunnable)
                everStaged = true
            }
            stagedUnits = crossings
            onStageUnits(crossings, charMode)
        }
    }

    fun onUp() {
        handler.removeCallbacks(repeatRunnable)
        when {
            stagedUnits > 0 -> onCommitStaged()
            // Slid out and back to zero: an explicit cancel, not a tap. A
            // finished hold-repeat run also must not add a tap delete.
            everStaged || repeating -> Unit
            else -> onDeleteChar()
        }
        stagedUnits = 0
        everStaged = false
    }

    fun cancel() {
        handler.removeCallbacks(repeatRunnable)
        if (everStaged) onStageUnits(0, charMode)
        stagedUnits = 0
        everStaged = false
    }

    private companion object {
        const val REPEAT_MS = 50L
    }
}
