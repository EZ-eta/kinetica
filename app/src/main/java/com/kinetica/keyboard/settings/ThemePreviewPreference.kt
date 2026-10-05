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
 * A live swatch of the resolved keyboard palette, under the hue slider, so a colour can be judged
 * without leaving settings. It is handed the output of [KeyboardTheme.resolve], not a colour of its
 * own, so the preview cannot disagree with the keyboard.
 *
 * [KeyboardPrefsFragment] pushes a new theme on every hue, source or brightness change; the slider
 * is `updatesContinuously`, so the swatch follows the thumb while it moves.
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
     * [caption] says whether the hue does anything: the bundled and Material You palettes ignore
     * it, and a preview that stays unchanged while the slider moves would look broken.
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
