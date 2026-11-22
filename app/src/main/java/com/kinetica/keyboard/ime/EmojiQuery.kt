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

package com.kinetica.keyboard.ime

/**
 * The emoji search query the keyboard types into while the panel is up. Pure, so the editing
 * rules are JVM-tested; nothing here reaches the editor.
 *
 * Taps and swipes join one word in hand, as they do in the field, so a two-thumb word decodes
 * as one: each swipe decoded alone gave `h eat rt` for `heart`. The word's letters stand as
 * tapped until a swipe joins it; from then on each decode of the whole word replaces it.
 */
class EmojiQuery {

    var text = ""
        private set

    /** Where the word in hand starts, or -1 when no gesture is building one. */
    private var wordFrom = -1

    /** Whether a swipe is part of the word in hand: only then does its decode stand for it. */
    private var swiped = false

    /** True while gestures are building a word: the caller's composer holds its tokens. */
    val inWord: Boolean get() = wordFrom >= 0

    fun clear() {
        text = ""
        settle()
    }

    /** A tapped letter from the engine: it joins the word in hand, or starts one. */
    fun tapped(s: String) {
        startWord()
        append(s)
    }

    /** A letter outside the gesture path (a key, an accent): the word in hand ends before it. */
    fun typed(s: String) {
        settle()
        append(s)
    }

    /** One space between words, never leading or doubled. */
    fun space() {
        settle()
        if (text.isNotEmpty() && !text.endsWith(' ') && text.length < MAX_LEN) text += ' '
    }

    /** A swipe joined the word in hand, or started one. */
    fun beginSwipe() {
        startWord()
        swiped = true
    }

    /** A decode of the word in hand; a tapped word keeps its letters, a word with a swipe takes the decode. */
    fun decoded(word: String) {
        if (wordFrom < 0 || !swiped) return
        text = (text.substring(0, wordFrom) + word.lowercase()).take(MAX_LEN)
    }

    /** False when there was nothing to delete, so the caller knows the field was not touched. */
    fun backspace(): Boolean {
        settle()
        if (text.isEmpty()) return false
        text = text.substring(0, text.offsetByCodePoints(text.length, -1))
        return true
    }

    private fun startWord() {
        if (wordFrom < 0) wordFrom = text.length
    }

    private fun settle() {
        wordFrom = -1
        swiped = false
    }

    private fun append(s: String) {
        if (text.length + s.length <= MAX_LEN) text += s
    }

    companion object {
        /** A search is a word or two; the cap keeps a stuck key from growing the row's input. */
        const val MAX_LEN = 40
    }
}
