package com.kinetica.keyboard.settings

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import com.kinetica.keyboard.R

class KeyboardPrefsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.keyboard_prefs, rootKey)
    }
}
