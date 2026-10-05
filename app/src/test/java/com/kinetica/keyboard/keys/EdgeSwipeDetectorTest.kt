package com.kinetica.keyboard.keys

import com.kinetica.keyboard.keys.EdgeSwipeBinding.Direction
import com.kinetica.keyboard.layout.Key
import com.kinetica.keyboard.layout.KeyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The directional-shortcut thresholds, and the top-row up-flick.
 *
 * Read at the lift alone, swiping up on `y` for `6` typed `to` about half the time: a flick
 * off the top row is short, and a thumb pivoting from the knuckle curves, so abs(dx) grows
 * until the 1.5x dominance test fails and the path falls through to the word decoder. The
 * furthest point decides when the lift refuses.
 *
 * Densities here are 1f so the 30dp minimum reads directly as 30 units.
 */
class EdgeSwipeDetectorTest {

    private val y = Key(
        id = "y", type = KeyType.CHAR, label = "y", output = "y",
        x = 0.5f, y = 0f, w = 0.1f, h = 0.25f, alternates = listOf("6"),
    )
    private val b = Key(
        id = "b", type = KeyType.CHAR, label = "b", output = "b",
        x = 0.5f, y = 0.5f, w = 0.1f, h = 0.25f, alternates = listOf("."),
    )

    private val bindings = EdgeSwipeBindings(
        listOf(
            EdgeSwipeBinding("y", Direction.UP, "6"),
            EdgeSwipeBinding("b", Direction.DOWN, "."),
        ),
    )

    private fun detect(
        key: Key,
        dx: Float,
        dy: Float,
        px: Float = dx,
        py: Float = dy,
        contacts: Int = 1,
    ) = EdgeSwipeDetector.detect(key, dx, dy, px, py, 1f, bindings, contacts)

    @Test
    fun aCleanUpFlickStillFires() {
        assertEquals("6", detect(y, dx = 2f, dy = -40f))
    }

    @Test
    fun aCurvedUpFlickIsReadAtItsFurthestPointNotItsLift() {
        // The reported gesture: the thumb reaches 38 units up and 6 across, then
        // slides back down and out to the right before lifting. At the lift there
        // is no dominant axis at all; at the peak there is.
        assertNull("the lift alone must not resolve this", detect(y, dx = 22f, dy = -14f))
        assertEquals("6", detect(y, dx = 22f, dy = -14f, px = 6f, py = -38f))
    }

    @Test
    fun aShortUpFlickThatRetractedStillFires() {
        // 34 units up at the peak, 18 at the lift: under the 30 minimum, the other half of
        // "half the times the number does not register".
        assertEquals("6", detect(y, dx = 1f, dy = -18f, px = 2f, py = -34f))
    }

    @Test
    fun aLiftThatResolvesToADirectionStillDecidesAlone() {
        // The peak is consulted only for a gesture the lift refused outright. Here the lift
        // reads RIGHT, which `y` has no binding for, and the upward peak must not be
        // substituted, or a gesture that already works could change meaning.
        assertNull(detect(y, dx = 44f, dy = -2f, px = 6f, py = -38f))
    }

    @Test
    fun aHorizontalTypingSwipeIsStillNotAShortcut() {
        // y to o and back: the shape a swiped word makes across the top row. No
        // vertical excursion anywhere on it, so nothing is stolen.
        assertNull(detect(y, dx = 120f, dy = 4f, px = 130f, py = 6f))
    }

    @Test
    fun theBottomRowDefaultsAreUnchanged() {
        assertEquals(".", detect(b, dx = 0f, dy = 36f))
        assertNull(detect(b, dx = 0f, dy = 20f))
    }

    @Test
    fun anUnboundDirectionStaysUnbound() {
        // `y` is bound UP only; a clean down-flick on it is not a shortcut.
        assertNull(detect(y, dx = 0f, dy = 40f))
    }

    // ---- A shortcut leaves one key, a word crosses the board --------------------
    //
    // With `c` bound up to a degree sign, `Connecticut` became `On°Cicut`: the word starts on
    // a bound key and ends two rows higher, so the lift reads as a clean up-flick.

    @Test
    fun aSwipedWordThatEndsUpwardIsNotAShortcut() {
        // The gesture geometry of the report: a long travel that resolves UP, on a key
        // with an UP binding. Only the contact count separates it from a real flick.
        assertEquals("6", detect(y, dx = 2f, dy = -40f, contacts = 3))
        assertNull(detect(y, dx = 2f, dy = -40f, contacts = 4))
        assertNull(detect(y, dx = 2f, dy = -40f, contacts = 11))
    }

    @Test
    fun aFlickThatLeavesItsOwnKeyStillFires() {
        // 30dp against a row pitch near 1.6kw: a real flick often enters the next row, so the
        // threshold cannot be one contact. A naive guard would break this case, the one the
        // reporter binds.
        assertEquals(".", detect(b, dx = 0f, dy = 36f, contacts = 2))
        assertEquals("6", detect(y, dx = 2f, dy = -40f, contacts = 2))
    }

    @Test
    fun aKeyWithNoStreamIsUnaffected() {
        // Backspace, enter, comma and every symbol layer never reach the engine, so the
        // caller passes the default. Nothing about those bindings changes.
        assertEquals("6", detect(y, dx = 2f, dy = -40f))
        assertEquals(".", detect(b, dx = 0f, dy = 36f))
    }

}
