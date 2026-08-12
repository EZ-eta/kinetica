package com.kinetica.keyboard.settings

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import com.kinetica.keyboard.R
import com.kinetica.keyboard.ui.KeyboardTheme

class KeyboardPrefsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.keyboard_prefs, rootKey)
        wireThemePreview()
    }

    /**
     * Keeps the palette swatch under the hue slider in step with the three
     * preferences that decide it. The change listeners take the NEW value from the
     * callback rather than reading it back: onPreferenceChange runs before the
     * value is persisted, so reading preferences here would always show the
     * previous palette.
     */
    private fun wireThemePreview() {
        val preview = findPreference<ThemePreviewPreference>(PREVIEW_KEY) ?: return
        val hue = findPreference<SeekBarPreference>(Prefs.THEME_HUE)
        val mode = findPreference<ListPreference>(Prefs.THEME_MODE)
        val brightness = findPreference<ListPreference>(Prefs.THEME_BRIGHTNESS)

        // Repaint while the thumb is still moving, which is the whole point of a
        // preview; without this the swatch only catches up on release.
        hue?.updatesContinuously = true

        fun refresh(newHue: Int? = null, newMode: String? = null, newBrightness: String? = null) {
            val prefs = preferenceManager.sharedPreferences ?: return
            // KeyboardConfig owns the migration from the retired colour list, so
            // going through it is what makes the swatch correct on first open,
            // before the hue preference has ever been written.
            val config = KeyboardConfig.from(prefs)
            val primary = newHue?.let { KeyboardTheme.primaryForHue(it.toFloat()) }
                ?: config.themeColor
            val resolvedMode = newMode ?: config.themeMode
            preview.show(
                KeyboardTheme.resolve(
                    requireContext(),
                    resolvedMode,
                    primary,
                    newBrightness ?: config.themeBrightness,
                ),
                getString(
                    if (KeyboardTheme.hueAffects(resolvedMode)) {
                        R.string.theme_preview_note_custom
                    } else {
                        R.string.theme_preview_note_fixed
                    },
                ),
            )
        }

        hue?.setOnPreferenceChangeListener { _, value ->
            refresh(newHue = value as? Int)
            true
        }
        mode?.setOnPreferenceChangeListener { _, value ->
            refresh(newMode = value as? String)
            true
        }
        brightness?.setOnPreferenceChangeListener { _, value ->
            refresh(newBrightness = value as? String)
            true
        }
        refresh()
    }

    private companion object {
        const val PREVIEW_KEY = "pref_theme_preview"
    }
}
