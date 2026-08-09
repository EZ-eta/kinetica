package com.kinetica.keyboard.keys

import kotlin.math.abs

/**
 * Spacebar slide-to-move-cursor. Under 8dp of travel the touch is a plain
 * space tap; past that it becomes cursor mode, emitting one cursor step per
 * 20dp of horizontal movement from a moving anchor.
 */
class SpacebarCursorController(
    private val density: Float,
    private val onCursorMove: (direction: Int) -> Unit,
) {
    private var startX = 0f
    private var anchorX = 0f
    private var cursorMode = false

    fun onDown(x: Float) {
        startX = x
        anchorX = x
        cursorMode = false
    }

    fun onMove(x: Float) {
        if (!cursorMode && abs(x - startX) >= ENTER_SLIDE_DP * density) {
            cursorMode = true
            anchorX = x
        }
        if (!cursorMode) return
        val step = STEP_DP * density
        while (x - anchorX >= step) {
            onCursorMove(1)
            anchorX += step
        }
        while (anchorX - x >= step) {
            onCursorMove(-1)
            anchorX -= step
        }
    }

    /** Returns true when the touch was a plain tap (insert a space). */
    fun onUp(): Boolean = !cursorMode

    private companion object {
        const val ENTER_SLIDE_DP = 8f
        const val STEP_DP = 20f
    }
}
