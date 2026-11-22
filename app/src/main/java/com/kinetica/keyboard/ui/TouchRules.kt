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

import com.kinetica.keyboard.engine.KeyboardGeometry
import com.kinetica.keyboard.engine.KineticaConstants

/**
 * Touches that are not the word, decided apart from the view so each rule is testable.
 *
 * A thumb resting on the keys, a third finger, or a numbers-row key brushed by the other thumb
 * mid-swipe would each become a letter or a digit and end the word in progress.
 */
object TouchRules {

    /**
     * The board the engine reads: the letters, with the apostrophe key at [apostropheKeyKw] (left, top,
     * right, bottom, kw) only when [strokes] is on. Off, the key is a tap key the engine never sees, so a
     * stroke through it reads as it did before the key was a place.
     */
    fun engineBoard(letters: KeyboardGeometry, apostropheKeyKw: FloatArray?, strokes: Boolean): KeyboardGeometry =
        if (strokes && apostropheKeyKw != null) letters.withApostropheKey(apostropheKeyKw) else letters

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
