package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.keys.ShiftState

/**
 * The word in progress as it stood before each of its taps and swipes, so a tap on backspace can
 * take the last one back and leave the bar as it was before it (#38). Letters a reload seeded
 * are not gestures: the stack starts empty at every reload.
 *
 * The automatic space commits a word within a few hundred ms of its last gesture, so the word it
 * committed is kept too, until anything else happens: one tap then takes that gesture back with
 * the space.
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

    /**
     * The word an automatic space committed: [word] as written, the cursor [end] after its space,
     * the composer's [tokens] and the [states] before each of its gestures.
     */
    class Committed(val word: String, val end: Int, val tokens: List<InputToken>, val states: List<State>)

    private val states = ArrayList<State>()

    var committed: Committed? = null
        private set

    val size: Int get() = states.size

    /** The word before each of its gestures, oldest first, read before a commit clears them. */
    fun snapshot(): List<State> = states.toList()

    /** Kept after the automatic space; any clear, any text written, any gesture drops it. */
    fun keep(word: Committed) {
        committed = word
    }

    /** The kept word, once. */
    fun takeCommitted(): Committed? = committed.also { committed = null }

    fun forgetCommitted() {
        committed = null
    }

    /** The word's gestures before the one taken back across the space, so the next tap goes on. */
    fun restore(before: List<State>) {
        states.clear()
        states.addAll(before)
    }

    /** Called before a gesture joins the word. */
    fun push(state: State) {
        states.add(state)
    }

    /** The word before its last gesture, or null when there is none to give back. */
    fun pop(): State? = if (states.isEmpty()) null else states.removeAt(states.size - 1)

    fun clear() {
        states.clear()
        committed = null
    }
}

/**
 * Whether a backspace takes the word's last gesture back instead of one letter: a tap, not the
 * hold's repeat, which deletes letter by letter; the setting on; a word in progress, nothing
 * selected, and a gesture to give back. After the commit every backspace is one letter.
 */
internal fun undoesGesture(tap: Boolean, enabled: Boolean, tentativeLength: Int, selection: Int, gestures: Int): Boolean =
    tap && enabled && tentativeLength > 0 && selection == 0 && gestures > 0

/**
 * Whether a backspace takes back the last gesture of the word the automatic space committed,
 * with the space: a tap, the setting on, no word in progress and nothing selected, and a word kept.
 * Only that space: one the user typed, or anything after it, and backspace deletes a character.
 */
internal fun undoesSpacedGesture(tap: Boolean, enabled: Boolean, tentativeLength: Int, selection: Int, kept: Boolean): Boolean =
    tap && enabled && tentativeLength == 0 && selection == 0 && kept

/** True while the editor still ends with [kept]'s word and its space, the cursor where it left it. */
internal fun committedStillThere(before: CharSequence, cursor: Int, kept: GestureUndo.Committed): Boolean =
    cursor == kept.end && kept.states.isNotEmpty() && before.endsWith(kept.word + " ")
