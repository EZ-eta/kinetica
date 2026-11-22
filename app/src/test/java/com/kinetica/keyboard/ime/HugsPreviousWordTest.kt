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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which characters take back an automatically inserted space.
 *
 * Without it, swiping "Hi" and tapping "!" after the autospace gives "Hi !". Only the automatic
 * space is taken back, since a space the user typed is theirs, and only for punctuation that
 * sits against the word.
 */
class HugsPreviousWordTest {

    @Test
    fun sentenceAndClausePunctuationHugs() {
        for (c in listOf(".", ",", "!", "?", ";", ":")) {
            assertTrue("$c should hug", hugsPreviousWord(c))
        }
    }

    @Test
    fun closingBracketsAndQuotesHug() {
        for (c in listOf(")", "]", "}", "»", "…")) {
            assertTrue("$c should hug", hugsPreviousWord(c))
        }
    }

    @Test
    fun theListIsTheUsers() {
        // French spacing keeps the space before the high marks; a list without them does that.
        assertFalse(hugsPreviousWord("!", hugging = ".,"))
        assertTrue(hugsPreviousWord(".", hugging = ".,"))
    }

    @Test
    fun anOpeningBracketDoesNot() {
        // "a (b)" wants the space that is already there.
        for (c in listOf("(", "[", "{", "«")) {
            assertFalse("$c should not hug", hugsPreviousWord(c))
        }
    }

    @Test
    fun aDashKeepsItsSpace() {
        // "one - two" is the common case, and an em dash takes a space either side, so this is
        // an explicit list, not a character-class test.
        for (c in listOf("-", "–", "—", "_")) {
            assertFalse("$c should not hug", hugsPreviousWord(c))
        }
    }

    @Test
    fun lettersDigitsAndSymbolsAreNotPunctuation() {
        for (c in listOf("a", "Z", "7", "@", "#", "$", "%", "+", "=", "/", "*")) {
            assertFalse("$c should not hug", hugsPreviousWord(c))
        }
    }

    @Test
    fun anApostropheDoesNotHugHere() {
        // It hugs in "don't", but that arrives through the letter path or the
        // apostrophe key, never as punctuation after an autospace. Treating it as
        // hugging would eat the space in "he said 'hi'".
        assertFalse(hugsPreviousWord("'"))
        assertFalse(hugsPreviousWord("\""))
    }

    @Test
    fun onlySingleCharactersQualify() {
        // Chord expansions and the custom comma text arrive through the same path.
        assertFalse(hugsPreviousWord(""))
        assertFalse(hugsPreviousWord("..."))
        assertFalse(hugsPreviousWord("!!"))
        assertFalse(hugsPreviousWord(" "))
        assertFalse(hugsPreviousWord("\n"))
        assertFalse(hugsPreviousWord("kind regards,"))
    }
}
