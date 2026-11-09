package com.kinetica.keyboard.keys

import android.os.Handler

/**
 * A key that sends its output again while held, at backspace's pace: armed after the same delay,
 * then a step every [BackspaceController.REPEAT_MS]. For the editing page's arrows, word moves and
 * Del ([EditPage.repeats]).
 */
class HoldRepeat(private val handler: Handler, private val onStep: () -> Unit) {

    private var repeated = false

    private val step = object : Runnable {
        override fun run() {
            repeated = true
            onStep()
            handler.postDelayed(this, BackspaceController.REPEAT_MS)
        }
    }

    fun onDown(armMs: Long) {
        repeated = false
        handler.removeCallbacks(step)
        handler.postDelayed(step, armMs)
    }

    /** Stops the hold; true when it repeated, so the lift sends nothing more. */
    fun onUp(): Boolean {
        handler.removeCallbacks(step)
        return repeated.also { repeated = false }
    }

    fun cancel() {
        handler.removeCallbacks(step)
        repeated = false
    }
}
