package com.kinetica.keyboard.settings

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.kinetica.keyboard.R
import com.kinetica.keyboard.ui.KeyboardTheme
import com.kinetica.keyboard.ui.ThemePreviewView

/**
 * A live swatch of the resolved keyboard palette, sitting under the hue slider.
 *
 * The hue slider showed a number and nothing else, so choosing a colour meant
 * leaving settings, opening a text field, judging it, and going back. This shows
 * the palette the service will actually build - it is handed the output of
 * [KeyboardTheme.resolve], not a colour of its own, so the preview cannot
 * disagree with the keyboard.
 *
 * [KeyboardPrefsFragment] pushes a new theme on every hue, source or brightness
 * change, and the slider is marked `updatesContinuously` so that happens while the
 * thumb is still moving rather than on release.
 */
class ThemePreviewPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    private var theme: KeyboardTheme? = null
    private var note: String = ""

    init {
        layoutResource = R.layout.preference_theme_preview
        isSelectable = false
        isPersistent = false
    }

    /**
     * [caption] says whether the hue is doing anything at all: in the bundled and
     * Material You palettes it is ignored, and a preview that sat there unchanged
     * while the slider moved would look broken rather than informative.
     */
    fun show(theme: KeyboardTheme, caption: String) {
        this.theme = theme
        this.note = caption
        notifyChanged()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(R.id.theme_preview) as? ThemePreviewView)?.theme = theme
        (holder.findViewById(R.id.theme_preview_note) as? TextView)?.text = note
    }
}
