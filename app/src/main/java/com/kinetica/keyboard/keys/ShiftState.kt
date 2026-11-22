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

import android.os.SystemClock

/**
 * NONE -> SHIFT on tap; two taps inside the double-tap window lock caps; any tap in CAPS_LOCK
 * returns to NONE. Committing a letter in SHIFT auto-reverts.
 *
 * The lock is read from the pair of taps, not from the state the first one left: a sentence
 * start is already SHIFT, so its first tap clears the shift and the second must still lock.
 */
class ShiftState(private val clock: () -> Long = { SystemClock.uptimeMillis() }) {
    enum class State { NONE, SHIFT, CAPS_LOCK }

    var state: State = State.NONE
        private set

    // The tap a second one would pair with; NEVER after a tap that left caps lock, so turning
    // caps lock off and on again takes a real double tap.
    private var lastShiftTapAt = NEVER

    val isShifted: Boolean get() = state != State.NONE

    fun onShiftKey() {
        val now = clock()
        val leaving = state == State.CAPS_LOCK
        state = when {
            leaving -> State.NONE
            now - lastShiftTapAt < DOUBLE_TAP_MS -> State.CAPS_LOCK
            state == State.NONE -> State.SHIFT
            else -> State.NONE
        }
        lastShiftTapAt = if (leaving) NEVER else now
    }

    /** After committing a shifted letter or word. */
    fun onLetterCommitted() {
        if (state == State.SHIFT) state = State.NONE
        onOtherKey()
    }

    /**
     * Any key or gesture that is not shift. Two shift taps with a letter, a space or a backspace
     * between them are two taps, not a double tap.
     */
    fun onOtherKey() {
        lastShiftTapAt = NEVER
    }

    /** Auto-shift from cursor caps mode at input start / after sentence end. */
    fun autoShift(enable: Boolean) {
        if (state != State.CAPS_LOCK) {
            state = if (enable) State.SHIFT else State.NONE
        }
    }

    fun apply(c: Char): Char = if (isShifted) c.uppercaseChar() else c

    fun apply(word: String): String =
        when (state) {
            State.NONE -> word
            State.SHIFT -> word.replaceFirstChar { it.uppercaseChar() }
            State.CAPS_LOCK -> word.uppercase()
        }

    private companion object {
        const val DOUBLE_TAP_MS = 300L

        // Far enough back that no clock reading pairs with it, and no subtraction overflows.
        const val NEVER = Long.MIN_VALUE / 2
    }
}
