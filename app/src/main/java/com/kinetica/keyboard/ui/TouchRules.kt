package com.kinetica.keyboard.ui

import com.kinetica.keyboard.engine.KineticaConstants

/**
 * Touches that are not the word, decided apart from the view so each rule is testable.
 *
 * A thumb resting on the keys, a third finger, or a numbers-row key brushed by the other thumb
 * mid-swipe would each become a letter or a digit and end the word in progress.
 */
object TouchRules {

    /**
     * One pointer's pass over the apostrophe key, for a swipe that writes `'` by crossing it. The
     * cut is due once the pointer has entered the key, left it, and reached a letter: a swipe that
     * overshoots onto the key and lifts there writes no `'`. Re-entering before a letter keeps the
     * first entry.
     */
    class ApostropheCut {
        private var tEnter = -1L
        private var tExit = -1L

        /** The cut's entry and exit times when this sample makes it due, else null; then it starts over. */
        fun onSample(inside: Boolean, onLetter: Boolean, t: Long): Pair<Long, Long>? {
            if (inside) {
                if (tEnter < 0) tEnter = t
                tExit = -1L
                return null
            }
            if (tEnter < 0) return null
            if (tExit < 0) tExit = t
            if (!onLetter) return null
            val cut = tEnter to tExit
            reset()
            return cut
        }

        fun reset() {
            tEnter = -1L
            tExit = -1L
        }
    }

    /**
     * A numbers-row digit tapped while the other thumb draws a swipe is dropped: it would
     * commit the word so far and leave the rest of the swipe a word of its own.
     */
    fun dropsDigitTap(fromNumberRow: Boolean, otherSwiping: Boolean, plainTap: Boolean): Boolean =
        fromNumberRow && otherSwiping && plainTap

    /**
     * A touch that began on a numbers-row digit becomes a swipe once it travels past the hold
     * slop onto a letter: a word started a little high is still a word, not a digit.
     */
    fun handsOverToSwipe(fromNumberRow: Boolean, travelSq: Float, slopSq: Float, onLetter: Boolean): Boolean =
        fromNumberRow && onLetter && travelSq > slopSq

    /**
     * A stream held still for [KineticaConstants.RESTING_FINGER_MIN_MS] while another stream
     * was down is a finger at rest. Its tap would land at the front of the word, by start time.
     */
    fun isRestingFinger(stationary: Boolean, heldMs: Long, overlapped: Boolean): Boolean =
        stationary && overlapped && heldMs >= KineticaConstants.RESTING_FINGER_MIN_MS

    /**
     * A third finger on a letter, with both thumbs already drawing, is ignored: typed, it would end
     * the word with its raw letter.
     */
    fun ignoresExtraFinger(onLetter: Boolean, engineFull: Boolean): Boolean = onLetter && engineFull

    /**
     * No long-press menu opens while another thumb is on the letters, nor once another thumb swiped
     * during the hold: two thumbs down is typing, and a thumb resting on a word's letter while the
     * other draws is too, even when the other lifts first.
     */
    fun holdOpensMenu(otherOnLetters: Boolean, swipeBeside: Boolean): Boolean = !otherOnLetters && !swipeBeside
}
