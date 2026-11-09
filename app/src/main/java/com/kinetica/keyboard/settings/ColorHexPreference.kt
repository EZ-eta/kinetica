package com.kinetica.keyboard.settings

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import androidx.preference.Preference

/**
 * A colour stored as `#RRGGBB`, picked in [ColourDialog]: sliders, presets or the code typed.
 */
class ColorHexPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    /** The stored colour, or the code default before one is typed: KeyboardConfig reads the same. */
    val hex: String
        get() {
            val fallback = HexColor.colorOr(Prefs.colorHexDefault(key), 0)
            return HexColor.format(HexColor.colorOr(getPersistedString(null), fallback))
        }

    override fun getSummary(): CharSequence {
        val color = HexColor.colorOr(hex, 0)
        // A square in the colour itself, ahead of the code: the code alone says little.
        return SpannableStringBuilder("■  ").apply {
            setSpan(ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(hex)
        }
    }

    /** Redraws the swatch after the colour was written elsewhere ([RowSync]). */
    fun refresh() = notifyChanged()

    override fun onClick() {
        ColourDialog.show(context, title, hex) { value ->
            if (callChangeListener(value)) {
                persistString(value)
                notifyChanged()
            }
        }
    }
}
