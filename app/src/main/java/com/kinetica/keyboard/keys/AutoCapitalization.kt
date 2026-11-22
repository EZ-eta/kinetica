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

package com.kinetica.keyboard.keys

/**
 * Language-specific capitalization that does not come from the shift state.
 *
 * Case otherwise comes from position: [ShiftState] reads taps and the editor's caps mode and knows
 * nothing about the word. English's lone first-person pronoun is the exception, where the
 * language decides: "i" written alone is always "I".
 *
 * Not a dictionary or autocorrect rule: autocorrect never touches a word the dictionary holds,
 * and "i" is in `en_wordlist`. The English list stays lowercase (`FoldCaseInertnessTest`), since a
 * capital there would split a word's frequency and move its ranking; the lone `i` gets its case
 * by rule and every other word from word data.
 *
 * The pronoun's contractions, `i'm`, `i've`, `i'd`, follow the word's language, not the active
 * one: only the English list holds them, and an English word typed with Italian active is still
 * English.
 *
 * A pure function in its own file because its caller, `KineticaIME`, has no JVM tests; the rule
 * is test-locked and only its wiring is device-verified.
 */
object AutoCapitalization {

    /**
     * [word]'s spelling after any language-mandated capitalization, or [word] unchanged. [lang]
     * is the active language code: "i" is a real word in Italian (the plural masculine article)
     * and in Spanish loanwords, so the rule must not fire there.
     *
     * Applied to the word as a unit, so "in" is left alone but the "i" of "i.e." becomes "I",
     * the accepted cost of the rule and what other keyboards do too.
     */
    fun forWord(word: String, lang: String, wordLang: String = lang): String = when {
        lang == "en" && word.length == 1 && (word[0] == 'i' || word[0] == 'I') -> "I"
        (lang == "en" || wordLang == "en") && isPronounContraction(word) -> "I" + word.substring(1)
        else -> word
    }

    /** `i'` then letters: `i'm`, `i've`, `i'd've`. Not `im` or `ill`, which are words of their own. */
    private fun isPronounContraction(word: String): Boolean =
        word.length >= 3 && (word[0] == 'i' || word[0] == 'I') && word[1] == '\'' &&
            word.substring(2).all { it.isLetter() || it == '\'' } && word[2].isLetter()
}
