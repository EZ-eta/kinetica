package com.kinetica.keyboard.keys

import kotlin.math.abs

/**
 * Spacebar slide-to-move-cursor. Under 8dp of travel the touch is a plain
 * space tap; past that it becomes cursor mode, emitting one cursor step per
 * [stepDp] of horizontal movement from a moving anchor.
 *
 * Both the step and its granularity are settings, asked for in the field: "maybe a
 * setting could change it to scroll by word (like the delete) rather than by character?
 * Or just make the scroll sensitivity adjustable so it moves faster through characters."
 * Both halves are cheap here because the travel-to-steps arithmetic does not care what a
 * step means - [wordMode] is passed straight out to the listener, which is the only place
 * that has to know.
 *
 * The default step stays 20dp and the default granularity stays characters, so a keyboard
 * nobody has touched behaves exactly as it did.
 */
class SpacebarCursorController(
    private val density: Float,
    private val onCursorMove: (direction: Int, byWord: Boolean) -> Unit,
) {
    /**
     * Travel that advances the cursor by one step.
     *
     * Lower is faster. Floored at the enter threshold: a step shorter than the travel
     * that gets you INTO cursor mode would fire on the very sample that armed it, so the
     * first movement would jump two.
     */
    var stepDp = DEFAULT_STEP_DP

    /** Whether a step is a word rather than a character, like the backspace slide's. */
    var wordMode = false

    private var startX = 0f
    private var anchorX = 0f
    private var cursorMode = false

    fun onDown(x: Float) {
        startX = x
        anchorX = x
        cursorMode = false
    }

    fun onMove(x: Float) {
        if (!cursorMode && abs(x - startX) >= ENTER_SLIDE_DP * density) {
            cursorMode = true
            anchorX = x
        }
        if (!cursorMode) return
        val step = effectiveStepDp() * density
        while (x - anchorX >= step) {
            onCursorMove(1, wordMode)
            anchorX += step
        }
        while (anchorX - x >= step) {
            onCursorMove(-1, wordMode)
            anchorX -= step
        }
    }

    /** Returns true when the touch was a plain tap (insert a space). */
    fun onUp(): Boolean = !cursorMode

    internal fun effectiveStepDp(): Float = stepDp.coerceIn(ENTER_SLIDE_DP, MAX_STEP_DP)

    companion object {
        const val ENTER_SLIDE_DP = 8f
        const val DEFAULT_STEP_DP = 20f
        const val MAX_STEP_DP = 60f
    }
}
