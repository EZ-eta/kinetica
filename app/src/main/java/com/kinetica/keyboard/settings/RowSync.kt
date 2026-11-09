package com.kinetica.keyboard.settings

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.SeekBarPreference
import androidx.preference.TwoStatePreference

/**
 * Sets a row to the value stored under its key, when something other than the row wrote it.
 *
 * A row reads its value once, at inflation. The keyboard writes some of these settings too: a
 * language change sets the letter arrangement, drag-resize the height, the one-handed and peck
 * toggles their own rows. A stale row touched afterwards writes the old value back. Setting the
 * value the store already holds writes nothing, so no second change follows.
 */
internal object RowSync {

    /** Every row under [group], nested screens included. */
    fun applyAll(group: PreferenceGroup, prefs: SharedPreferences) {
        for (i in 0 until group.preferenceCount) {
            val child = group.getPreference(i)
            apply(child, prefs)
            if (child is PreferenceGroup) applyAll(child, prefs)
        }
    }

    fun apply(row: Preference, prefs: SharedPreferences) {
        val key = row.key ?: return
        // An absent key shows its default; nothing was written behind the row's back.
        if (!prefs.contains(key)) return
        try {
            when (row) {
                is TwoStatePreference -> {
                    val v = prefs.getBoolean(key, row.isChecked)
                    if (v != row.isChecked) row.isChecked = v
                }
                is ListPreference -> {
                    val v = prefs.getString(key, null)
                    if (v != null && v != row.value) row.value = v
                }
                is SeekBarPreference -> {
                    // A value past the slider's ends would be clamped and written back.
                    val v = prefs.getInt(key, row.value)
                    if (v != row.value && v in row.min..row.max) row.value = v
                }
                is EditTextPreference -> {
                    val v = prefs.getString(key, null)
                    if (v != row.text) row.text = v
                }
                is MultiSelectListPreference -> {
                    val v = prefs.getStringSet(key, null)
                    if (v != null && v != row.values) row.values = v
                }
                is SteppedSliderPreference -> row.syncValue(prefs.getInt(key, 0))
                is ColorHexPreference -> row.refresh()
            }
        } catch (e: ClassCastException) {
            // Stored under another type: the row keeps what it shows, and the screen stays up.
        }
    }
}
