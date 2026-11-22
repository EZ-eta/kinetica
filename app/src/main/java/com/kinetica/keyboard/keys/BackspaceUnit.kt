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

package com.kinetica.keyboard.keys

/**
 * What one unit of a backspace slide is: a character or a word (a piece, with punctuation stops).
 *
 * A held shift swaps the setting (Prefs.SHIFT_SWAPS_SLIDES) until the first unit is staged; from
 * there the mode is fixed for the touch, since a flip would change what the staged count means.
 * Pure, so the rule is testable without the controller's Handler.
 */
class BackspaceUnit {

    /** The setting: single characters instead of whole words. */
    var charMode = false

    /** A staged word stops at punctuation, so a link goes a piece at a time. Ignored for characters. */
    var stopsAtPunctuation = false

    /** A held shift, with its switch on. */
    var flipped = false

    private var fixedChars: Boolean? = null

    /** Whether a unit is a character, now: the fixed mode once a unit was staged. */
    val chars: Boolean get() = fixedChars ?: (charMode != flipped)

    val granularity: DeleteSpan.Granularity
        get() = when {
            chars -> DeleteSpan.Granularity.CHARS
            stopsAtPunctuation -> DeleteSpan.Granularity.PIECES
            else -> DeleteSpan.Granularity.WORDS
        }

    /** The first unit was staged: the mode holds until [release]. */
    fun fix() {
        if (fixedChars == null) fixedChars = chars
    }

    /** The touch ended: the next one reads the setting and shift again. */
    fun release() {
        fixedChars = null
    }
}
