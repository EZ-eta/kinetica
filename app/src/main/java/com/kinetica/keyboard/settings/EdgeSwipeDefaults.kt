package com.kinetica.keyboard.settings

import android.content.SharedPreferences
import com.kinetica.keyboard.keys.EdgeSwipeBindings

/** Shift's re-case swipes for a swipe set saved before they existed, once. */
object EdgeSwipeDefaults {

    fun applyTo(prefs: SharedPreferences) {
        if (prefs.getBoolean(Prefs.SHIFT_CASE_SEEDED, false)) return
        val edit = prefs.edit().putBoolean(Prefs.SHIFT_CASE_SEEDED, true)
        // Nothing saved reads as the defaults, which hold them already.
        prefs.getString(Prefs.EDGE_SWIPES, null)?.let { stored ->
            val bindings = EdgeSwipeBindings.withShiftCase(EdgeSwipeBindings.parse(stored).bindings)
            edit.putString(Prefs.EDGE_SWIPES, EdgeSwipeBindings(bindings).serialize())
        }
        edit.apply()
    }
}
