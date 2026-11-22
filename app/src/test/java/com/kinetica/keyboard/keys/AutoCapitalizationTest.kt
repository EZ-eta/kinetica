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

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoCapitalizationTest {

    @Test
    fun loneIIsCapitalizedInEnglish() {
        assertEquals("I", AutoCapitalization.forWord("i", "en"))
        // Already capital, e.g. at a sentence start where auto-shift got there
        // first: the rule must be idempotent, not a toggle.
        assertEquals("I", AutoCapitalization.forWord("I", "en"))
    }

    @Test
    fun loneIIsLeftAloneInLanguagesWhereItIsAWord() {
        // Italian "i" is the plural masculine article and stays lowercase mid-sentence, so the
        // rule is gated by language.
        assertEquals("i", AutoCapitalization.forWord("i", "it"))
        assertEquals("i", AutoCapitalization.forWord("i", "es"))
        assertEquals("i", AutoCapitalization.forWord("i", "pl"))
        assertEquals("i", AutoCapitalization.forWord("i", "cs"))
        assertEquals("i", AutoCapitalization.forWord("i", "nl"))
        assertEquals("i", AutoCapitalization.forWord("i", "de"))
        assertEquals("i", AutoCapitalization.forWord("i", "fr"))
        // Norwegian "i" is the preposition "in", and StandaloneLetters treats
        // it as a word, so the capitalization rule must still leave it alone.
        assertEquals("i", AutoCapitalization.forWord("i", "no"))
        assertEquals("i", AutoCapitalization.forWord("i", "ro"))
        assertEquals("i", AutoCapitalization.forWord("i", ""))
    }

    @Test
    fun everyOtherWordIsUntouched() {
        for (lang in listOf("en", "it", "es", "pl", "cs", "nl", "de", "fr", "no", "ro", "ru", "he", "ar")) {
            for (w in listOf("in", "if", "is", "it", "ii", "a", "o", "island", "iowa", "")) {
                assertEquals("$w changed under $lang", w, AutoCapitalization.forWord(w, lang))
            }
        }
    }

    @Test
    fun otherSingleLettersAreUntouchedInEnglish() {
        // "a" is the other English single-letter word and must stay lowercase;
        // the rule is about the pronoun, not about single letters.
        for (c in 'a'..'z') {
            if (c == 'i') continue
            assertEquals("$c changed", c.toString(), AutoCapitalization.forWord(c.toString(), "en"))
        }
    }

    @Test
    fun thePronounsContractionsAreCapitalizedInEnglish() {
        // A swiped `im` decodes to `i'm`, which must not stay lowercase mid-sentence.
        assertEquals("I'm", AutoCapitalization.forWord("i'm", "en"))
        assertEquals("I've", AutoCapitalization.forWord("i've", "en"))
        assertEquals("I'd", AutoCapitalization.forWord("i'd", "en"))
        assertEquals("I'll", AutoCapitalization.forWord("i'll", "en"))
        assertEquals("I'd've", AutoCapitalization.forWord("i'd've", "en"))
        assertEquals("I'm", AutoCapitalization.forWord("I'm", "en"))
    }

    @Test
    fun anEnglishContractionIsCapitalizedWhateverLanguageIsActive() {
        // Only the English list holds `i'm`: with Italian active and English mixed in, the
        // word is still English, so its language decides, not the active one.
        assertEquals("I'm", AutoCapitalization.forWord("i'm", "it", wordLang = "en"))
        assertEquals("I've", AutoCapitalization.forWord("i've", "pl", wordLang = "en"))
        // The lone article stays the active language's call.
        assertEquals("i", AutoCapitalization.forWord("i", "it", wordLang = "en"))
        assertEquals("i'm", AutoCapitalization.forWord("i'm", "it", wordLang = "it"))
    }

    @Test
    fun wordsThatOnlyLookLikeItAreUntouched() {
        for (w in listOf("im", "ive", "ill", "id", "it's", "i'", "i''", "i'2", "in'", "island's")) {
            assertEquals("$w changed", w, AutoCapitalization.forWord(w, "en"))
        }
    }
}
