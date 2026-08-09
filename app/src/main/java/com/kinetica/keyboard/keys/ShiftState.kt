package com.kinetica.keyboard.keys

import android.os.SystemClock

/**
 * NONE -> SHIFT on tap; SHIFT -> CAPS_LOCK on a second tap within the
 * double-tap window; any tap in CAPS_LOCK returns to NONE. Committing a letter
 * in SHIFT auto-reverts.
 */
class ShiftState {
    enum class State { NONE, SHIFT, CAPS_LOCK }

    var state: State = State.NONE
        private set

    private var lastShiftTapAt = 0L

    val isShifted: Boolean get() = state != State.NONE

    fun onShiftKey() {
        val now = SystemClock.uptimeMillis()
        state = when (state) {
            State.NONE -> State.SHIFT
            State.SHIFT ->
                if (now - lastShiftTapAt < DOUBLE_TAP_MS) State.CAPS_LOCK else State.NONE
            State.CAPS_LOCK -> State.NONE
        }
        lastShiftTapAt = now
    }

    /** After committing a shifted letter or word. */
    fun onLetterCommitted() {
        if (state == State.SHIFT) state = State.NONE
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
    }
}
