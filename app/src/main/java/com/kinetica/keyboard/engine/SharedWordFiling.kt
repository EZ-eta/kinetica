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

package com.kinetica.keyboard.engine

/**
 * Which language a committed word is learned into when both resident lexicons hold it.
 *
 * Provenance cannot say: the merge keeps the active language's copy of a shared word, so English
 * typed with Italian active was learned into Italian word by word. The word's own frequency
 * usually can, `this` is 231 in English against 120 in Italian; where the two are close, `in`
 * 234 against 230, the last word only one lexicon holds decides. Over 139 hand-labelled shared
 * commits in two bilingual captures: 135 right, against 58 for provenance.
 */
class SharedWordFiling(private val margin: Int = KineticaConstants.SHARED_WORD_FREQ_MARGIN) {

    private var lastSingle: String? = null

    /**
     * The language to learn a commit into. [held] maps each resident language whose lexicon
     * holds the word to its frequency byte; [provenance] is where the decode said it came from,
     * and is the answer whenever the word is not shared.
     *
     * [residents] is given only with no primary language, where [held] reads the bundled lists
     * alone: there one holder is the word's language even when provenance read a copy the user's
     * own learning put in another list. A word no list holds goes where the last word only one
     * list holds went, since provenance then is the active language, which says nothing about the
     * text: Italian words missing from its list were learned into English.
     *
     * [anchors] false files the word without letting it lead: a word reloaded from the editor and
     * committed again, often autocorrected, says nothing of the language being written.
     */
    fun languageFor(
        held: Map<String, Int>,
        provenance: String,
        residents: Collection<String> = emptyList(),
        anchors: Boolean = true,
    ): String {
        if (held.size == 1 && anchors) lastSingle = held.keys.first()
        if (residents.isNotEmpty() && held.size == 1) return held.keys.first()
        if (residents.isNotEmpty() && held.isEmpty()) return lastSingle?.takeIf { it in residents } ?: provenance
        if (held.size < 2) return provenance
        val ranked = held.entries.sortedByDescending { it.value }
        if (ranked[0].value - ranked[1].value > margin) return ranked[0].key
        return lastSingle?.takeIf { it in held } ?: provenance
    }
}
