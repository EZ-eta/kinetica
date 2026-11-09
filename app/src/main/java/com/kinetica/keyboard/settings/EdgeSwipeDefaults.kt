package com.kinetica.keyboard.settings

import android.content.SharedPreferences
import com.kinetica.keyboard.keys.EdgeSwipeBinding
import com.kinetica.keyboard.keys.EdgeSwipeBindings

/** Swipes added to the defaults after a swipe set could be saved, given to a saved set once each. */
object EdgeSwipeDefaults {

    fun applyTo(prefs: SharedPreferences) {
        seed(prefs, Prefs.SHIFT_CASE_SEEDED, EdgeSwipeBindings::withShiftCase)
        seed(prefs, Prefs.EDIT_PAGE_SEEDED, EdgeSwipeBindings::withEditPage)
        seed(prefs, Prefs.EDIT_PAGE_UP_SEEDED, EdgeSwipeBindings::withEditPageUp)
    }

    /** Once per [flag]: a saved set gets [add]'s change; nothing saved reads as the defaults. */
    private fun seed(prefs: SharedPreferences, flag: String, add: (List<EdgeSwipeBinding>) -> List<EdgeSwipeBinding>) {
        if (prefs.getBoolean(flag, false)) return
        val edit = prefs.edit().putBoolean(flag, true)
        prefs.getString(Prefs.EDGE_SWIPES, null)?.let { stored ->
            edit.putString(Prefs.EDGE_SWIPES, EdgeSwipeBindings(add(EdgeSwipeBindings.parse(stored).bindings)).serialize())
        }
        // apply() updates the in-memory set at once, so the next seed reads what this one wrote.
        edit.apply()
    }
}
