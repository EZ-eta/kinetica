package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.models.InputToken

/**
 * A swipe that crossed the apostrophe key, between the cut and the `'` it writes.
 *
 * The stroke before the key is already in the composer; its word is written once its decode is
 * back, or a key goes down, or [KineticaConstants.APOSTROPHE_CUT_WAIT_MS] passes. Tokens finished
 * meanwhile (the rest of the stroke lifting, the other thumb) belong to the next word, so they wait
 * here and go in after the `'`.
 */
internal class ApostropheSwipe {

    /** Whether a `'` is still to be written. */
    var pending = false
        private set

    private var generation = 0
    private val held = ArrayList<InputToken>()

    /** The cut is in: its word's decode is the composer's [generation] or a later one. */
    fun begin(generation: Int) {
        pending = true
        this.generation = generation
        held.clear()
    }

    /** Keeps [token] for after the `'`: true when it was taken. */
    fun hold(token: InputToken): Boolean {
        if (!pending) return false
        held.add(token)
        return true
    }

    /**
     * Whether a decode of [generation] carries the word before the key. Any later one does too: tokens
     * are held, so only a redecode of the same buffer can come after it.
     */
    fun due(generation: Int): Boolean = pending && generation >= this.generation

    /** Ends the wait: the tokens held, in the order they lifted, to be finished now. */
    fun take(): List<InputToken> {
        pending = false
        val out = held.toList()
        held.clear()
        return out
    }

    /** The word was abandoned (a new field, a cursor move): no `'`, nothing held. */
    fun drop() {
        pending = false
        held.clear()
    }
}
