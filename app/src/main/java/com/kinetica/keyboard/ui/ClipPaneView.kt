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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.kinetica.keyboard.R
import com.kinetica.keyboard.data.Clip

/**
 * The clipboard history in the bar's place: Clear all and close on top, the clips under them,
 * newest first, each a tap to paste with its own delete. Plain TextViews painted from the palette,
 * as [EmojiSearchView]; the keys below keep typing into the field.
 *
 * Built in code only, with its callbacks: no layout file inflates it.
 */
@SuppressLint("ViewConstructor")
class ClipPaneView(
    context: Context,
    rowPx: Int,
    private val onPaste: (Clip) -> Unit,
    private val onDelete: (Clip) -> Unit,
    private val onClearAll: () -> Unit,
    private val onClose: () -> Unit,
) : LinearLayout(context) {

    private val clearAll = TextView(context)
    private val close = TextView(context)
    private val row = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val rowScroll = HorizontalScrollView(context)
    private var shown: List<Clip> = emptyList()

    /** Resolved colour roles, pushed from the service like [EmojiSearchView.theme]. */
    var theme: KeyboardTheme = KeyboardTheme.fromResources(context)
        set(value) {
            field = value
            applyTheme()
        }

    /** Under a background image the pane leaves its own fill off, as the bar does. */
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
        clearAll.apply {
            text = context.getString(R.string.clips_clear_all)
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            isClickable = true
            setOnClickListener { onClearAll() }
        }
        close.apply {
            text = "✕"
            textSize = 18f
            gravity = Gravity.CENTER
            contentDescription = context.getString(R.string.clips_close)
            setPadding(dp(16), 0, dp(16), 0)
            isClickable = true
            setOnClickListener { onClose() }
        }
        top.addView(clearAll, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        top.addView(TextView(context), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        top.addView(close, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(top, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        rowScroll.isHorizontalScrollBarEnabled = false
        rowScroll.addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(rowScroll, LayoutParams(LayoutParams.MATCH_PARENT, rowPx))
        applyTheme()
    }

    /** Shows [clips], newest first; an empty list says how one gets here. */
    fun show(clips: List<Clip>) {
        shown = clips
        row.removeAllViews()
        clearAll.visibility = if (clips.isEmpty()) INVISIBLE else VISIBLE
        if (clips.isEmpty()) {
            row.addView(
                TextView(context).apply {
                    text = context.getString(R.string.clips_empty)
                    textSize = 14f
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), 0, dp(12), 0)
                    setTextColor(theme.keyHint)
                },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT),
            )
            return
        }
        // Two and a half to a screen: the half shows there is more to scroll.
        val cellPx = (resources.displayMetrics.widthPixels * 2 / 5).coerceAtLeast(1)
        for (clip in clips) row.addView(cell(clip), LayoutParams(cellPx, LayoutParams.MATCH_PARENT))
        rowScroll.scrollTo(0, 0)
    }

    private fun cell(clip: Clip): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(4), 0, dp(4))
        val body = TextView(context).apply {
            text = preview(clip.text)
            textSize = 14f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(4), 0)
            setTextColor(theme.keyText)
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(theme.chip)
            }
            isClickable = true
            setOnClickListener { onPaste(clip) }
        }
        val delete = TextView(context).apply {
            text = "✕"
            textSize = 14f
            gravity = Gravity.CENTER
            contentDescription = context.getString(R.string.clips_delete)
            setPadding(dp(8), 0, dp(8), 0)
            setTextColor(theme.keyHint)
            isClickable = true
            setOnClickListener { onDelete(clip) }
        }
        addView(body, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(delete, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    private fun applyTheme() {
        if (overBackdrop) background = null else setBackgroundColor(theme.suggestionBg)
        clearAll.setTextColor(theme.suggestionText)
        close.setTextColor(theme.suggestionText)
        // The cells take the colour when built, so the shown ones are built again.
        show(shown)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        /** A cell shows two lines at most: no need to lay out a long clip whole. */
        private const val PREVIEW_CHARS = 160

        /** The clip as one run of text: line breaks as spaces, cut to what a cell can show. */
        fun preview(text: String): String {
            val head = if (text.length > PREVIEW_CHARS) text.substring(0, PREVIEW_CHARS) else text
            return head.replace(Regex("\\s+"), " ").trim()
        }
    }
}
