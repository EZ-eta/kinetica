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

import java.text.Normalizer

/**
 * Maps accented letters onto the trie alphabet's base letters. Gesture geometry knows only base
 * keys (an Italian user swipes the same path for "perche" and "perché"), so accented words are
 * stored under their folded key and come back as display variants at emit time (see
 * [LoadedDictionary.forms]).
 */
object AccentFolder {

    /**
     * Letters that fold to two letters, so a word holding one is reached by swiping both keys and
     * a popup's `ß` goes into the word as two taps on `s`.
     */
    private val DIGRAPHS = mapOf('ß' to "ss", 'œ' to "oe", 'ĳ' to "ij", 'þ' to "th")

    private val FOLD = HashMap<Char, Char>().apply {
        "àáâäãåąæă".forEach { put(it, 'a') }
        "èéêëęě".forEach { put(it, 'e') }
        "ìíîï".forEach { put(it, 'i') }
        "òóôöõø".forEach { put(it, 'o') }
        "ùúûüů".forEach { put(it, 'u') }
        put('ç', 'c'); put('ć', 'c'); put('č', 'c')
        put('ď', 'd')
        put('ñ', 'n'); put('ń', 'n'); put('ň', 'n')
        put('ý', 'y'); put('ÿ', 'y')
        put('ł', 'l')
        put('ř', 'r')
        put('ś', 's'); put('š', 's')
        // Romanian writes ș/ț with a comma below; the cedilla forms are the legacy spellings.
        put('ș', 's'); put('ş', 's')
        put('ť', 't')
        put('ț', 't'); put('ţ', 't')
        put('ž', 'z'); put('ź', 'z'); put('ż', 'z')
        // Russian writes ё on е's key and most text writes е for it.
        put('ё', 'е')
        // Ukrainian writes ґ on г's key, and its apostrophe often as the modifier letter.
        put('ґ', 'г')
        put('\u02BC', '\'')
        // Arabic: the alef-hamza forms and wasla sit on alef's key.
        "أإآٱ".forEach { put(it, 'ا') }
        // The schwa of inclusive Italian (`carə`, plural `tuttɜ`) stands where the e was.
        put('ə', 'e'); put('ɜ', 'e')
        // Letters with a stroke or no dot have no decomposition to read the base letter from.
        put('đ', 'd'); put('ð', 'd'); put('ħ', 'h'); put('ı', 'i'); put('ŀ', 'l'); put('ŧ', 't')
        // Every other lowercase Latin letter with a mark folds to the letter Unicode builds it
        // from (`ē`, `ő`, `ğ`, `ŵ`), so a popup letter or a learned word never misses its key.
        // Latin blocks only: Cyrillic `й` decomposes to `и`, a key of its own.
        val latin = ('\u00C0'..'\u024F') + ('\u1E00'..'\u1EFF')
        for (c in latin) {
            if (!c.isLowerCase() || containsKey(c) || c in DIGRAPHS) continue
            val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0]
            if (base != c && base in 'a'..'z') put(c, base)
        }
    }

    /**
     * The typographic apostrophe, as other keyboards and smart-quote editors write `I’d`. The
     * trie spells contractions with U+0027, so a reopened or learned `I’d` reaches `i'd`. Kept
     * out of [FOLD]: a popup `’` is punctuation, not a letter of the word.
     */
    private const val CURLY_APOSTROPHE = '\u2019'

    /**
     * Marks a folded key never carries: Arabic tatweel and short vowels. Text almost always omits
     * them, and a key cannot be drawn for a mark.
     */
    private val DROPPED: Set<Char> = HashSet<Char>().apply {
        add('\u0640')
        for (c in '\u064B'..'\u0652') add(c)
        add('\u0670')
    }

    /**
     * Letter codes of [text] when it is a single accented letter of this alphabet, else null:
     * one code for `ó`, two for `ß` (s s) and `œ` (o e).
     *
     * An accented letter from a long-press popup continues the word; a digit, symbol or emoji
     * from the same popup ends it. Only one character folding to letters other than itself
     * qualifies, so the popup's base cell ("o" under "ó") still commits and then inserts.
     *
     * Pure so the composing decision is JVM-testable, unlike the buffer code in KineticaIME.
     */
    fun accentedLetterCodes(text: String, alphabet: Alphabet = Alphabet.LATIN): IntArray? {
        if (text.length != 1) return null
        val lower = text[0].lowercaseChar()
        val folded = DIGRAPHS[lower] ?: FOLD[lower]?.toString() ?: return null
        return alphabet.encode(folded)
    }

    /**
     * Folded form of [word]; returns the same instance when nothing folds.
     *
     * Case folds too: the trie alphabet has no capitals, so "Haus" would encode to null and
     * [Trie.build] would drop it silently. A wordlist can then carry a capitalized display form on
     * a lowercase key, and German nouns use the same forms mechanism as "perché".
     */
    fun fold(word: String): String {
        var needsFold = false
        for (ch in word) {
            if (ch in DIGRAPHS || FOLD.containsKey(ch) || ch.isUpperCase() || ch in DROPPED || ch == CURLY_APOSTROPHE) {
                needsFold = true
                break
            }
        }
        if (!needsFold) return word
        val sb = StringBuilder(word.length + 1)
        for (ch in word) {
            // Lowercase first, so the maps need only lowercase keys and "Ä" folds as "ä" does.
            val lower = ch.lowercaseChar()
            if (lower in DROPPED) continue
            if (lower == CURLY_APOSTROPHE) {
                sb.append('\'')
                continue
            }
            val digraph = DIGRAPHS[lower]
            if (digraph != null) sb.append(digraph) else sb.append(FOLD[lower] ?: lower)
        }
        return sb.toString()
    }
}
