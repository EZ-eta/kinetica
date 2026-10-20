package com.kinetica.keyboard.ime

import com.kinetica.keyboard.keys.ShiftState

/**
 * The word in progress as it stood before each of its taps and swipes, so a tap on backspace can
 * take the last one back and leave the bar as it was before it (#38). Letters a reload seeded
 * are not gestures: the stack starts empty at every reload.
 */
internal class GestureUndo {

    /**
     * The word before one gesture: the [text] the editor showed, the [tokens] the composer held,
     * the keyboard's [shift] and the word's own [wordShift], and whether its decode had come back
     * empty.
     */
    data class State(
        val text: String,
        val tokens: Int,
        val shift: ShiftState.State,
        val wordShift: ShiftState.State,
        val decodeEmpty: Boolean,
    )

    private val states = ArrayList<State>()

    val size: Int get() = states.size

    /** Called before a gesture joins the word. */
    fun push(state: State) {
        states.add(state)
    }

    /** The word before its last gesture, or null when there is none to give back. */
    fun pop(): State? = if (states.isEmpty()) null else states.removeAt(states.size - 1)

    fun clear() {
        states.clear()
    }
}

/**
 * Whether a backspace takes the word's last gesture back instead of one letter: a tap, not the
 * hold's repeat, which deletes letter by letter; the setting on; a word in progress, nothing
 * selected, and a gesture to give back. After the commit every backspace is one letter.
 */
internal fun undoesGesture(tap: Boolean, enabled: Boolean, tentativeLength: Int, selection: Int, gestures: Int): Boolean =
    tap && enabled && tentativeLength > 0 && selection == 0 && gestures > 0
