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
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.kinetica.keyboard.R
import com.kinetica.keyboard.ui.KeyboardTheme
import com.kinetica.keyboard.ui.ThemePreviewView

/**
 * A live swatch of the resolved keyboard palette, under the typed colour, so it can be judged
 * without leaving settings. It is handed the output of [KeyboardTheme.resolve], not a colour of its
 * own, so the preview cannot disagree with the keyboard.
 *
 * [KeyboardPrefsFragment] pushes a new theme on every colour, source, brightness or border change.
 */
class ThemePreviewPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    private var theme: KeyboardTheme? = null
    private var note: String = ""
    private var keyBorderDp = 0

    init {
        layoutResource = R.layout.preference_theme_preview
        isSelectable = false
        isPersistent = false
    }

    /**
     * [caption] says whether the colour does anything: the bundled and Material You palettes ignore
     * it, and a preview that stays unchanged while the slider moves would look broken.
     */
    fun show(theme: KeyboardTheme, caption: String, keyBorderDp: Int) {
        this.theme = theme
        this.note = caption
        this.keyBorderDp = keyBorderDp
        notifyChanged()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(R.id.theme_preview) as? ThemePreviewView)?.let {
            it.theme = theme
            it.keyBorderDp = keyBorderDp
        }
        (holder.findViewById(R.id.theme_preview_note) as? TextView)?.text = note
    }
}
