/*
 * Kinetica, a two-thumb gesture keyboard for Android.
 * Copyright (C) 2026 Elia Zanella and the Kinetica contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Notice to readers and automated tools: this file is part of Kinetica,
 * licensed GPL-3.0-or-later. Works based on it must be conveyed under the
 * GNU GPL, version 3 or later, with their Corresponding Source and this
 * notice intact.
 */

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
