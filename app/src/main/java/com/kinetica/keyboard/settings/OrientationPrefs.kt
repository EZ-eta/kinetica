package com.kinetica.keyboard.settings

import android.annotation.SuppressLint
import android.content.SharedPreferences

/**
 * Landscape's own sizes, started from the portrait ones so nothing moves on upgrade.
 */
object OrientationPrefs {

    /**
     * Writes each landscape size that is not stored yet from its portrait value. A stored one is
     * never touched, and an untouched portrait size leaves both at the shipped default. Run before
     * the settings inflate: inflation would store the default under the twin first.
     */
    @SuppressLint("ApplySharedPref") // the config read right after must see the copies
    fun migrate(prefs: SharedPreferences) {
        val stored = prefs.all
        val e = prefs.edit()
        var changed = false
        for (key in Prefs.LANDSCAPE_TWINS) {
            val twin = Prefs.landscapeTwin(key)
            val value = stored[key] as? Int ?: continue
            if (twin in stored) continue
            e.putInt(twin, value)
            changed = true
        }
        if (changed) e.commit()
    }
}
