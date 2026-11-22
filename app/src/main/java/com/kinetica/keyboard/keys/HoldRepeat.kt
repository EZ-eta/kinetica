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
