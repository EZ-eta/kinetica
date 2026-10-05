package com.kinetica.keyboard.keys

import com.kinetica.keyboard.engine.DecodeTrace
import com.kinetica.keyboard.layout.Key
import kotlin.math.abs

/**
 * Directional shortcut swipes resolved against the user-configurable [EdgeSwipeBindings]
 * (defaults: backspace up = "!", enter up = "?", plus each letter's corner glyph).
 * Travel must exceed 30dp along an axis 1.5x the other, so real letter swipes are not stolen.
 *
 * Read at the pointer's furthest point as well as at its lift. A top-row up-flick has no room:
 * 30dp above `y` is off the keyboard, and a thumb pivoting from the knuckle curves, so abs(dx)
 * grows until the dominance test fails at lift and the path decodes as a word (up on `y` for `6`
 * gave `to` about half the time). The furthest point is consulted only when the lift refused the
 * gesture outright and only where a binding exists, so nothing the lift resolves changes meaning.
 * On the top row it cannot steal a typed word: there are no letters above it.
 */
object EdgeSwipeDetector {

    private const val MIN_TRAVEL_DP = 30f
    private const val DOMINANCE = 1.5f

    /**
     * Keys a pointer may touch and still be read as a shortcut.
     *
     * A shortcut flick leaves its own key and stops; a swiped word crosses the board. Without this
     * cap `Connecticut`, which starts on `c` and ends on the top row, read as an up-swipe and fired
     * the `c` binding.
     *
     * Three, not one: the minimum travel is 30dp against a row pitch of about 1.6 kw, so a real
     * flick routinely enters the next row (two contacts), and three leaves room for a curved one.
     * `Connecticut` contacts eleven. Reasoned from that geometry, not measured; the trace lines in
     * [detect] record both firings and refusals so a capture can settle it.
     */
    private const val MAX_SHORTCUT_CONTACTS = 3

    /**
     * Returns the bound output ("emoji" is a reserved value), or null.
     *
     * [peakDxPx]/[peakDyPx] are the displacement at the pointer's furthest sample from its down
     * point. Passing the lift displacement for both gives the endpoint-only reading.
     *
     * [contacts] is how many keys the pointer has touched; see [MAX_SHORTCUT_CONTACTS]. It
     * defaults to one for a caller with no gesture stream, every key that is not a letter.
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
        // The lift decides first and alone wherever it decides anything, so a gesture that reads
        // as one direction at lift is never re-read as another. The peak is consulted only for a
        // gesture the lift refused outright: too short, or no dominant axis.
        val direction = directionOf(dxPx, dyPx, minTravel)
            ?: directionOf(peakDxPx, peakDyPx, minTravel)
        val bound = direction?.let { bindings.outputFor(key, it) }
        if (bound != null) {
            // The contact refusal is traced beside the firing: it is the only measurement of what
            // MAX_SHORTCUT_CONTACTS costs.
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
        // Not a bound shortcut, so the pointer goes to the decoder. Traced when the key had a
        // binding in the direction the gesture mostly headed, to measure how often the thresholds
        // refuse an intended shortcut.
        if (DecodeTrace.enabled) {
            val intended = dominantAxis(peakDxPx, peakDyPx)
            val bound = intended?.let { bindings.outputFor(key, it) }
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
