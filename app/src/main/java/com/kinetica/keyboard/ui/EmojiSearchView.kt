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

package com.kinetica.keyboard.ui

import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.kinetica.keyboard.R

/**
 * The emoji search panel, in the bar's place while the keyboard types its query: the query on
 * top with a close control, a row of matching emoji under it, [rowPx] tall. Built like
 * [EmojiPickerView], from plain TextViews painted from the palette.
 */
class EmojiSearchView(
    context: Context,
    rowPx: Int,
    private val onPick: (ch: String, rank: Int) -> Unit,
    private val onClose: () -> Unit,
) : LinearLayout(context) {

    private val query = TextView(context)
    private val close = TextView(context)
    private val row = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val rowScroll = HorizontalScrollView(context)
    private var shownQuery = ""
    private var shownResults: List<String> = emptyList()

    /** Resolved colour roles, pushed from the service like [EmojiPickerView.theme]. */
    var theme: KeyboardTheme = KeyboardTheme.fromResources(context)
        set(value) {
            field = value
            applyTheme()
        }

    /** Under a background image the panel leaves its own fill off, as the bar does. */
    var overBackdrop = false
        set(value) {
            field = value
            applyTheme()
        }

    init {
        orientation = VERTICAL
        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        query.apply {
            textSize = 16f
            maxLines = 1
            // A long query shows its end: the letters just typed.
            ellipsize = TextUtils.TruncateAt.START
            setPadding(dp(12), 0, dp(8), 0)
        }
        close.apply {
            text = "✕"
            textSize = 18f
            gravity = Gravity.CENTER
            contentDescription = context.getString(R.string.emoji_search_close)
            setPadding(dp(16), 0, dp(16), 0)
            isClickable = true
            setOnClickListener { onClose() }
        }
        top.addView(query, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        top.addView(close, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(top, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        rowScroll.isHorizontalScrollBarEnabled = false
        rowScroll.addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(rowScroll, LayoutParams(LayoutParams.MATCH_PARENT, rowPx))
        applyTheme()
    }

    /** Shows [typed] (the hint when blank) over [results], best first. */
    fun show(typed: String, results: List<String>) {
        shownQuery = typed
        query.text = typed.ifBlank { context.getString(R.string.emoji_search_hint) }
        query.setTextColor(if (typed.isBlank()) theme.keyHint else theme.suggestionText)
        if (results == shownResults) return
        shownResults = results
        row.removeAllViews()
        // Eight to a screen, as in the pane's grid, so a cell is the size the thumb already knows.
        val cellPx = (resources.displayMetrics.widthPixels / COLUMNS).coerceAtLeast(1)
        for ((i, ch) in results.withIndex()) {
            row.addView(
                TextView(context).apply {
                    text = ch
                    textSize = 28f
                    gravity = Gravity.CENTER
                    setTextColor(theme.keyText)
                    setOnClickListener { onPick(ch, i) }
                },
                LayoutParams(cellPx, LayoutParams.MATCH_PARENT),
            )
        }
        rowScroll.scrollTo(0, 0)
    }

    private fun applyTheme() {
        if (overBackdrop) background = null else setBackgroundColor(theme.suggestionBg)
        close.setTextColor(theme.suggestionText)
        // The cells take the colour when built, so the shown ones are built again.
        val results = shownResults
        shownResults = emptyList()
        show(shownQuery, results)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private companion object {
        const val COLUMNS = 8
    }
}
