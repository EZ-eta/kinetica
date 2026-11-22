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
import android.graphics.Paint
import android.util.Log
import java.io.IOException

/**
 * The emoji set and its search words, read from assets once and shared by the pane and the
 * search panel. The set reaches Unicode 16 and ZWJ sequences, newer than many phones' fonts, so
 * what the font cannot draw as one glyph is left out here, once, not as a tofu box or as parts.
 *
 * Never throws: an empty pane is an inconvenience, a dead IME leaves the user unable to type.
 */
class EmojiAssets(private val context: Context) {

    private val glyphPaint = Paint()

    fun canDraw(ch: String): Boolean = glyphPaint.hasGlyph(ch)

    private val tonedDrawable = HashMap<String, Boolean>()

    /**
     * What [entry] shows and types in [tone] ([EmojiTone]); its plain form when the font has no
     * glyph for the toned one: an older phone shows yellow, not a hand and a swatch.
     */
    fun shown(entry: EmojiCatalog.Entry, tone: Int): String {
        val toned = EmojiTone.apply(entry, tone)
        if (toned == entry.ch) return toned
        return if (tonedDrawable.getOrPut(toned) { canDraw(toned) }) toned else entry.ch
    }

    val catalog: EmojiCatalog by lazy {
        read("emoji/emoji.tsv") { EmojiCatalog.parse(it, ::canDraw) } ?: EmojiCatalog(emptyList())
    }

    private val words = HashMap<String, List<EmojiCatalog.Words>>()

    /** One language's words, loaded on its first search; a language without a file has none. */
    fun words(lang: String): List<EmojiCatalog.Words> =
        words.getOrPut(lang) { read("emoji/kw_$lang.tsv") { EmojiCatalog.parseWords(it) } ?: emptyList() }

    private fun <T> read(path: String, parse: (Sequence<String>) -> T): T? = try {
        context.assets.open(path).bufferedReader().useLines(parse)
    } catch (e: IOException) {
        Log.w(TAG, "emoji asset unreadable: $path", e)
        null
    } catch (e: RuntimeException) {
        Log.w(TAG, "emoji asset unusable: $path", e)
        null
    }

    private companion object {
        const val TAG = "EmojiAssets"
    }
}
