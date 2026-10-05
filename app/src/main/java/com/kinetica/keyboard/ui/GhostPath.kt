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
