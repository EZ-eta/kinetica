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
import android.content.res.TypedArray
import android.util.AttributeSet
import android.widget.SeekBar
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.kinetica.keyboard.R

/**
 * A slider over a table of values instead of a linear range (#2).
 *
 * On a linear 10-800 ms slider one pixel is several milliseconds, so 10, 15 and 20 cannot be hit
 * by thumb; the table is dense where a few milliseconds are felt and sparse where they are not.
 *
 * It stores plain milliseconds under the key the linear slider used, so existing settings and
 * backups read unchanged. A stored value that is not a step shows at its nearest step and is
 * replaced only when the user moves the slider.
 */
class SteppedSliderPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    private val steps: IntArray
    private var value = 0
    private var tracking = false

    init {
        layoutResource = R.layout.preference_stepped_slider
        isSelectable = false
        val a = context.obtainStyledAttributes(attrs, R.styleable.SteppedSliderPreference)
        val res = a.getResourceId(R.styleable.SteppedSliderPreference_sliderSteps, 0)
        a.recycle()
        val parsed = if (res == 0) {
            IntArray(0)
        } else {
            context.resources.getStringArray(res).mapNotNull { it.trim().toIntOrNull() }.toIntArray()
        }
        // A misconfigured row degrades to a single fixed value, since a preference must not crash
        // the settings screen on inflation. SteppedSliderSourceTest and TimingStepsTest check
        // that every shipped row resolves.
        steps = if (parsed.isNotEmpty()) parsed else intArrayOf(FALLBACK_STEP_MS)
    }

    /**
     * Must not read [steps]: the androidx [Preference] base constructor calls this before this
     * class's `init` block has run, so [steps] is still null and reading it crashes every
     * settings open. The default is the XML `android:defaultValue`, which is always set; the
     * const is a formal fallback. Same family as
     */
    override fun onGetDefaultValue(a: TypedArray, index: Int): Any = a.getInt(index, FALLBACK_STEP_MS)

    override fun onSetInitialValue(defaultValue: Any?) {
        // Written back as the linear slider did, so the derived defaults in KeyboardConfig behave
        // the same once the screen has been opened.
        value = getPersistedInt((defaultValue as? Int) ?: steps.first())
        persistInt(value)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val bar = holder.findViewById(R.id.stepped_slider_bar) as? SeekBar ?: return
        val label = holder.findViewById(R.id.stepped_slider_value) as? TextView
        bar.setOnSeekBarChangeListener(null)
        bar.max = steps.size - 1
        bar.progress = SliderSteps.nearestIndex(steps, value)
        label?.text = format(steps[bar.progress])
        bar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    label?.text = format(steps[progress])
                    // A key press moves the bar without a touch to end.
                    if (fromUser && !tracking) commit(sb, label)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {
                    tracking = true
                }

                override fun onStopTrackingTouch(sb: SeekBar) {
                    tracking = false
                    commit(sb, label)
                }
            },
        )
    }

    private fun commit(bar: SeekBar, label: TextView?) {
        val chosen = steps[bar.progress]
        if (chosen == value) return
        if (callChangeListener(chosen)) {
            value = chosen
            persistInt(chosen)
        } else {
            bar.progress = SliderSteps.nearestIndex(steps, value)
            label?.text = format(value)
        }
    }

    private fun format(ms: Int): String = context.getString(R.string.slider_value_ms, ms)

    /** The stored value, written elsewhere ([RowSync]); redraws the bar only when it moved. */
    fun syncValue(stored: Int) {
        if (stored == value) return
        value = stored
        notifyChanged()
    }

    private companion object {
        // Only reached if a row declares no valid sliderSteps, which no shipped row does.
        // Present so onGetDefaultValue never has to read the step table (see its comment).
        const val FALLBACK_STEP_MS = 100
    }
}
