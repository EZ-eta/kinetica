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

import android.view.View
import java.util.Collections
import java.util.WeakHashMap

/**
 * A word's strokes for the keyboard to draw over its own keys while the gesture tutor asks
 * for it. The tutor and the keyboard share the app's process and main thread, so a plain
 * holder is enough; the tutor clears it whenever it is not in front.
 */
object GhostPath {

    /** One thumb's letters, in the order they are written. */
    class Stroke(val left: Boolean, val letters: String)

    var strokes: List<Stroke> = emptyList()
        private set

    private val views = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    fun show(strokes: List<Stroke>) {
        this.strokes = strokes
        for (v in views.toList()) v.invalidate()
    }

    fun clear() = show(emptyList())

    internal fun attach(view: View) {
        views.add(view)
    }

    internal fun detach(view: View) {
        views.remove(view)
    }
}
