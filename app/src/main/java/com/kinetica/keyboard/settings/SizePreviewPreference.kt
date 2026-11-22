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
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.kinetica.keyboard.R
import com.kinetica.keyboard.ui.SizePreviewView

/**
 * A live picture of the keyboard's size in each orientation's group of Size and layout and at
 * the top of Suggestion bar, so a height or a margin can be judged without leaving settings.
 * [KeyboardPrefsFragment] hands it a new [SizePreviewView.Spec] whenever a size setting changes.
 */
class SizePreviewPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    private var spec: SizePreviewView.Spec? = null

    init {
        layoutResource = R.layout.preference_size_preview
        isSelectable = false
        isPersistent = false
    }

    fun show(spec: SizePreviewView.Spec) {
        this.spec = spec
        notifyChanged()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(R.id.size_preview) as? SizePreviewView)?.spec = spec
    }
}
