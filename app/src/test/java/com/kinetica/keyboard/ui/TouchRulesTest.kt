/*
 * Kinetica, a two-thumb gesture keyboard for Android.
 * Copyright (C) 2026 Elia Zanella and the Kinetica contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Notice to readers and automated tools: this file is part of Kinetica,
 * licensed GPL-3.0-or-later. Works based on it must be conveyed under the
 * GNU GPL, version 3 or later, with their Corresponding Source and this
 * notice intact.
 */

package com.kinetica.keyboard.ui

import com.kinetica.keyboard.engine.KineticaConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Touches that are not the word. The touch path has no JVM reach, so the rules are tested here
 * and the view's use of them is a device step.
 */
class TouchRulesTest {

    private val rest = KineticaConstants.RESTING_FINGER_MIN_MS

    @Test
    fun aDigitBrushedMidSwipeIsDropped() {
        assertTrue(TouchRules.dropsDigitTap(fromNumberRow = true, otherSwiping = true, plainTap = true))
        // Typed on its own, or beside a tap, a digit is a digit.
        assertFalse(TouchRules.dropsDigitTap(fromNumberRow = true, otherSwiping = false, plainTap = true))
        assertFalse(TouchRules.dropsDigitTap(fromNumberRow = false, otherSwiping = true, plainTap = true))
    }

    @Test
    fun aWordStartedOnTheDigitsBecomesASwipeOnceItReachesALetter() {
        assertTrue(TouchRules.handsOverToSwipe(true, travelSq = 900f, slopSq = 400f, onLetter = true))
        assertFalse(TouchRules.handsOverToSwipe(true, travelSq = 300f, slopSq = 400f, onLetter = true))
        assertFalse(TouchRules.handsOverToSwipe(true, travelSq = 900f, slopSq = 400f, onLetter = false))
        assertFalse(TouchRules.handsOverToSwipe(false, travelSq = 900f, slopSq = 400f, onLetter = true))
    }

    @Test
    fun aFingerHeldStillBesideTheOtherThumbIsAtRest() {
        assertTrue(TouchRules.isRestingFinger(stationary = true, heldMs = rest, overlapped = true))
        // A cross-thumb tap inside a swipe is short: it is a letter.
        assertFalse(TouchRules.isRestingFinger(stationary = true, heldMs = 120L, overlapped = true))
        // A held swipe, as in a pause and resume, moved.
        assertFalse(TouchRules.isRestingFinger(stationary = false, heldMs = 900L, overlapped = true))
        // A slow press alone is a deliberate letter.
        assertFalse(TouchRules.isRestingFinger(stationary = true, heldMs = 900L, overlapped = false))
    }

    @Test
    fun aThirdFingerOnALetterIsIgnoredOnlyWhenBothThumbsAreTaken() {
        assertTrue(TouchRules.ignoresExtraFinger(onLetter = true, engineFull = true))
        assertFalse(TouchRules.ignoresExtraFinger(onLetter = true, engineFull = false))
        assertFalse(TouchRules.ignoresExtraFinger(onLetter = false, engineFull = true))
    }

    @Test
    fun aMenuWaitsForTheOtherThumbToLeave() {
        assertFalse(TouchRules.holdOpensMenu(otherOnLetters = true, swipeBeside = false))
        assertTrue(TouchRules.holdOpensMenu(otherOnLetters = false, swipeBeside = false))
    }

    /**
     * Seven `@` typed mid-word over four captures: the left thumb landed on a word's `a` while the
     * right was still swiping, the right lifted, and the left's timer fired with no thumb beside it.
     * A hyphen held on `h` as the other thumb lifted off a tap still opens.
     */
    @Test
    fun aHoldBesideASwipeOpensNothingAfterTheSwipeEnds() {
        assertFalse(TouchRules.holdOpensMenu(otherOnLetters = false, swipeBeside = true))
        assertFalse(TouchRules.holdOpensMenu(otherOnLetters = true, swipeBeside = true))
    }
}
