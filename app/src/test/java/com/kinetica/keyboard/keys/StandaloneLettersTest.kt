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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-language single-letter word sets. The first test shows why this is a curated list,
 * not a dictionary call.
 */
class StandaloneLettersTest {

    @Test
    fun aDictionaryTestCouldNotDoThis() {
        // Every letter a-z is an entry in every bundled wordlist, with OpenSubtitles frequencies
        // that look real: en holds l at 126 518, s at 110 199, t at 72 881, d at 64 304.
        // isWord answers yes for all of them, so only a curated list separates a lone `a`
        // from a lone `t`.
        for (c in "lstdmcehnbfgjrpxuwkvzq") {
            assertFalse("$c is not an English word on its own", StandaloneLetters.isWord(c, "en"))
        }
        assertTrue(StandaloneLetters.isWord('a', "en"))
        assertTrue(StandaloneLetters.isWord('i', "en"))
    }

    @Test
    fun theLanguageDecidesAndNotThePosition() {
        // The same argument AutoCapitalization makes: `e` is a word in Italian and not in
        // English, so the sets cannot be merged.
        assertTrue("e is Italian for and", StandaloneLetters.isWord('e', "it"))
        assertFalse("e alone is not English", StandaloneLetters.isWord('e', "en"))
        assertTrue("y is Spanish for and", StandaloneLetters.isWord('y', "es"))
        assertFalse("y alone is not Italian", StandaloneLetters.isWord('y', "it"))
        assertTrue("w is Polish for in", StandaloneLetters.isWord('w', "pl"))
        assertFalse("w alone is not Spanish", StandaloneLetters.isWord('w', "es"))
        // Added on a Polish speaker's report: colloquial, not a function word. The Polish set
        // has no capture behind it.
        assertTrue("e is a Polish interjection", StandaloneLetters.isWord('e', "pl"))
    }

    @Test
    fun eachSetIsTheClosedListOfThatLanguagesOneLetterWords() {
        assertEqualsSet("ai", "en")
        assertEqualsSet("aeio", "it")
        assertEqualsSet("aeoy", "es")
        assertEqualsSet("aeiouwz", "pl")
        assertEqualsSet("aikosuvz", "cs")
        assertEqualsSet("u", "nl")
        // German has no one-letter word. Registered as empty, because an unregistered language
        // falls back to the English set and would space and capitalize a lone "a" or "i".
        assertEqualsSet("", "de")
        assertEqualsSet("ay", "fr")
        assertEqualsSet("aio", "no")
        // Romanian: a (to / has), e (is, beside este), o (the feminine indefinite article).
        assertEqualsSet("aeo", "ro")
        // The other scripts: Russian's prepositions and conjunctions; Hebrew and Arabic write
        // their one-letter words joined to the next, so a lone one is still being typed.
        for (c in "авикосуя") assertTrue("ru $c", StandaloneLetters.isWord(c, "ru"))
        for (c in "бгдежзлмнпртфхцчшщыьэю") assertFalse("ru $c", StandaloneLetters.isWord(c, "ru"))
        for (c in "והבלמשכ") assertFalse("he $c", StandaloneLetters.isWord(c, "he"))
        for (c in "وبلف") assertFalse("ar $c", StandaloneLetters.isWord(c, "ar"))
        // And none of them falls back to English.
        // Ukrainian is not Russian's set: і and з, never и or к.
        for (c in "авзійоуя") assertTrue("uk $c", StandaloneLetters.isWord(c, "uk"))
        for (c in "икс") assertFalse("uk $c", StandaloneLetters.isWord(c, "uk"))
        for (lang in listOf("ru", "uk", "he", "ar")) assertFalse(StandaloneLetters.isWord('a', lang))
    }

    @Test
    fun accentsAndCaseArriveFolded() {
        // The composer's literal is already folded to a-z, so Italian `è` reaches this as
        // `e`; the case fold is a belt for a caller that passes the shifted glyph.
        assertTrue(StandaloneLetters.isWord('E', "it"))
        assertTrue(StandaloneLetters.isWord('I', "en"))
    }

    @Test
    fun anUnknownLanguageFallsBackToEnglish() {
        // An unregistered code must still behave, and the smallest set is the safe default.
        // `zz` is no language, so registering a new one cannot break this test.
        assertTrue(StandaloneLetters.isWord('a', "zz"))
        assertFalse(StandaloneLetters.isWord('e', "zz"))
        assertTrue(StandaloneLetters.isWord('i', ""))
    }

    private fun assertEqualsSet(expected: String, lang: String) {
        for (c in 'a'..'z') {
            val want = c in expected
            assertTrue(
                "$lang: $c should be ${if (want) "a word" else "no word"}",
                StandaloneLetters.isWord(c, lang) == want,
            )
        }
    }
}
