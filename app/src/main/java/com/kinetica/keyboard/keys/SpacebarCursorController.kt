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

    /**
     * Whether a second space tap inside [DOUBLE_TAP_MS] ends the sentence instead (R69).
     *
     * Off by default: it spends the second of two deliberate spaces, which anyone who
     * types a double space on purpose would notice immediately.
     */
    var doubleSpacePeriod = false

    private var lastSpaceAt = 0L
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
    fun onUp(nowMs: Long = 0L): Lift {
        val lift = when {
            cursorMode -> Lift.SLIDE
            inSpacelessZone -> Lift.SPACELESS
            doubleSpacePeriod && lastSpaceAt != 0L && nowMs - lastSpaceAt <= DOUBLE_TAP_MS ->
                Lift.DOUBLE
            else -> Lift.SPACE
        }
        // Only a plain space opens the window and a double closes it, so three taps are a
        // sentence end followed by a fresh space rather than two sentence ends. A slide or
        // a spaceless tap closes it too: neither wrote the space a period would replace.
        lastSpaceAt = if (lift == Lift.SPACE) nowMs else 0L
        return lift
    }

    /** Outcome of a spacebar touch. */
    enum class Lift { SPACE, SPACELESS, SLIDE, DOUBLE }

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

        /**
         * Window for the second tap of a double space.
         *
         * The same 300 ms ShiftState uses for caps lock, so the two double taps on this
         * keyboard feel like one gesture. Deliberately a separate constant rather than a
         * shared one: they are independent gestures and either could be retuned alone.
         */
        const val DOUBLE_TAP_MS = 300L
    }
}
