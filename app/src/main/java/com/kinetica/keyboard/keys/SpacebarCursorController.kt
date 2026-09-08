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

    /**
     * Whether the left [SPACELESS_FRACTION] of the key ends the word without a space.
     *
     * Off by default: it spends a third of the spacebar's tap area, which is a real cost
     * for anyone who did not ask for it.
     */
    var spacelessZone = false

    private var startX = 0f
    private var anchorX = 0f
    private var cursorMode = false
    private var inSpacelessZone = false

    /**
     * [keyLeft] and [keyWidth] are the spacebar's own rect, in the same coordinates as
     * [x]. Zero width means the caller does not know it, and then no touch is ever in the
     * zone - which is also what an unconfigured controller does.
     */
    fun onDown(x: Float, keyLeft: Float = 0f, keyWidth: Float = 0f) {
        startX = x
        anchorX = x
        cursorMode = false
        inSpacelessZone =
            spacelessZone && keyWidth > 0f && x < keyLeft + keyWidth * SPACELESS_FRACTION
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

    /**
     * What the lift was.
     *
     * The slide always wins: a touch that starts in the spaceless zone and then travels
     * past the enter threshold is a cursor slide, because that is the gesture the finger
     * actually made and the zone is only ever a sub-decision of the tap.
     */
    fun onUp(): Lift = when {
        cursorMode -> Lift.SLIDE
        inSpacelessZone -> Lift.SPACELESS
        else -> Lift.SPACE
    }

    /** Outcome of a spacebar touch. */
    enum class Lift { SPACE, SPACELESS, SLIDE }

    internal fun effectiveStepDp(): Float = stepDp.coerceIn(ENTER_SLIDE_DP, MAX_STEP_DP)

    companion object {
        const val ENTER_SLIDE_DP = 8f

        /**
         * Share of the spacebar, from the left, that is the spaceless zone.
         *
         * 0.30, the figure the feature was requested with. Not swept: the cost is tap
         * area rather than accuracy, and only a thumb can price that.
         */
        const val SPACELESS_FRACTION = 0.30f
        const val DEFAULT_STEP_DP = 20f
        const val MAX_STEP_DP = 60f
    }
}
