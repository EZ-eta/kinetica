package com.kinetica.keyboard.keys

import com.kinetica.keyboard.engine.DecodeTrace
import com.kinetica.keyboard.layout.Key
import kotlin.math.abs

/**
 * Directional shortcut swipes resolved against the user-configurable
 * [EdgeSwipeBindings] (defaults: backspace up = "!", enter up = "?", V down =
 * ",", B down = ".", X down = emoji). Travel must exceed 30dp with a clearly
 * dominant axis so real letter swipes are not stolen.
 *
 * Read at the pointer's FURTHEST point as well as at its lift, and that
 * distinction is the whole of the 2026-08-28 fix. The 30dp/1.5x pair was
 * device-verified in one direction only - down-swipes on the bottom row, where
 * `V`, `B` and `X` carry the shipped defaults and there is a screen's worth of
 * room below. The implicit alternates put the same test on top-row UP swipes,
 * where there is no room at all: 30dp above `y` is off the keyboard, so the
 * flick is short, and a thumb pivoting from the knuckle curves as it goes, which
 * grows abs(dx) until the dominance test fails. It then falls through to gesture
 * decoding and the path spells a word - a reporter swiping up on `y` for `6` got
 * `to` about half the time.
 *
 * Reading the furthest point widens this only where the lift refused the gesture
 * outright: a lift that resolves to any direction still decides alone, so
 * nothing that works today changes meaning. What it adds is the curved or
 * retracted flick, and only where a binding exists. On the top row it cannot
 * steal a typed word, because an upward excursion of 30dp from the top row
 * leaves the keyboard - there are no letters up there to be swiping to.
 */
object EdgeSwipeDetector {

    private const val MIN_TRAVEL_DP = 30f
    private const val DOMINANCE = 1.5f

    /**
     * Keys a pointer may touch and still be read as a shortcut.
     *
     * A shortcut flick leaves its own key and stops; a swiped word crosses the board. That
     * was not asked before, so `Connecticut` - which starts on `c` and ends on the top row -
     * read as a dominant up-swipe and fired the `c` binding, giving `On°Cicut`. The peak
     * reading above widened the window it fires in, deliberately, and this narrows the
     * thing it is allowed to fire on.
     *
     * Three rather than one, because a flick is not confined to its key. The minimum travel
     * is 30dp against a row pitch of about 1.6 kw, so a real flick leaves the key it starts
     * on and enters the next row: two contacts, routinely. Three leaves room for a curved
     * one and still refuses anything that has been travelling - `Connecticut` contacts
     * eleven.
     *
     * **Reasoned from that geometry, not measured.** A fired edge swipe emitted no trace
     * line until this change, so there is no capture to price it against; the line added
     * beside it is what lets the next one settle the value.
     */
    private const val MAX_SHORTCUT_CONTACTS = 3

    /**
     * Returns the bound output ("emoji" is a reserved value), or null.
     *
     * [peakDxPx]/[peakDyPx] are the displacement at the pointer's furthest
     * sample from its down point. Passing the lift displacement for both
     * reproduces the endpoint-only behaviour exactly.
     *
     * [contacts] is how many keys the pointer has touched; see [MAX_SHORTCUT_CONTACTS].
     * It defaults to one so a caller with no gesture stream - every key that is not a
     * letter - reads exactly as it did before.
     */
    fun detect(
        key: Key,
        dxPx: Float,
        dyPx: Float,
        peakDxPx: Float,
        peakDyPx: Float,
        density: Float,
        bindings: EdgeSwipeBindings,
        contacts: Int = 1,
    ): String? {
        val minTravel = MIN_TRAVEL_DP * density
        // The lift decides first and alone wherever it decides anything, so a
        // gesture that reads as one direction at lift is never re-read as
        // another. The peak is consulted only for a gesture the lift refused
        // outright - too short, or no dominant axis - which is the shape the
        // report describes and the narrowest widening that covers it.
        val direction = directionOf(dxPx, dyPx, minTravel)
            ?: directionOf(peakDxPx, peakDyPx, minTravel)
        val bound = direction?.let { bindings.outputFor(key.id, it) }
        if (bound != null) {
            // The threshold refusal is traced beside the firing on purpose: it is the only
            // measurement of what MAX_SHORTCUT_CONTACTS costs, and a guard whose cost is
            // invisible is a guard nobody can price later.
            if (contacts <= MAX_SHORTCUT_CONTACTS) {
                DecodeTrace.log {
                    "  edgeswipe fired key=${key.id} dir=$direction out=$bound " +
                        "contacts=$contacts"
                }
                return bound
            }
            DecodeTrace.log {
                "  edgeswipe refused key=${key.id} dir=$direction bound=$bound " +
                    "reason=contacts contacts=$contacts"
            }
            return null
        }
        // Not a bound shortcut, so the pointer goes to the decoder. Traced when
        // the key had a binding in the direction the gesture was mostly headed,
        // which is the miss this exists to measure: the next capture answers how
        // often the thresholds refuse an intended shortcut, rather than the rate
        // being estimated from a report.
        if (DecodeTrace.enabled) {
            val intended = dominantAxis(peakDxPx, peakDyPx)
            val bound = intended?.let { bindings.outputFor(key.id, it) }
            if (bound != null) {
                DecodeTrace.log {
                    "  edgeswipe refused key=${key.id} dir=$intended bound=$bound " +
                        "reason=threshold contacts=$contacts " +
                        "lift=(${dxPx.toInt()},${dyPx.toInt()}) " +
                        "peak=(${peakDxPx.toInt()},${peakDyPx.toInt()}) " +
                        "minTravel=${minTravel.toInt()}"
                }
            }
        }
        return null
    }

    private fun directionOf(dx: Float, dy: Float, minTravel: Float): EdgeSwipeBinding.Direction? =
        when {
            -dy >= minTravel && -dy > DOMINANCE * abs(dx) -> EdgeSwipeBinding.Direction.UP
            dy >= minTravel && dy > DOMINANCE * abs(dx) -> EdgeSwipeBinding.Direction.DOWN
            -dx >= minTravel && -dx > DOMINANCE * abs(dy) -> EdgeSwipeBinding.Direction.LEFT
            dx >= minTravel && dx > DOMINANCE * abs(dy) -> EdgeSwipeBinding.Direction.RIGHT
            else -> null
        }

    /** The axis the travel is mostly along, thresholds ignored. Tracing only. */
    private fun dominantAxis(dx: Float, dy: Float): EdgeSwipeBinding.Direction? = when {
        dx == 0f && dy == 0f -> null
        abs(dy) >= abs(dx) ->
            if (dy < 0) EdgeSwipeBinding.Direction.UP else EdgeSwipeBinding.Direction.DOWN
        else ->
            if (dx < 0) EdgeSwipeBinding.Direction.LEFT else EdgeSwipeBinding.Direction.RIGHT
    }
}
