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

package com.kinetica.keyboard.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A letter's Default: every enabled language's built-in list, to trim again. */
class LetterDefaultsTest {

    // The shape of the shipped lists for `a`, active language first.
    private val italian = listOf("à", "á", "â", "ä", "@")
    private val english = listOf("à", "á", "â", "ä", "ã", "å", "æ", "ā", "@")
    private val polish = listOf("ą", "à", "á", "@")

    @Test
    fun anAccentTrimmedAwayComesBack() {
        // An accent trimmed from a's list can be restored, and the Default draws on every
        // enabled language: Polish's `ą` comes from Polish.
        val trimmed = listOf("à", "á", "â", "@")
        val back = LetterDefaults.union(listOf(italian, english, polish), KeyboardConfig.MAX_LETTER_ALTERNATES)
        assertTrue("ä" in back)
        assertTrue("ą" in back)
        assertTrue(back.containsAll(trimmed))
    }

    @Test
    fun theActiveLanguageComesFirstAndEachCharacterOnce() {
        val u = LetterDefaults.union(listOf(italian, english, polish), KeyboardConfig.MAX_LETTER_ALTERNATES)
        assertEquals(listOf("à", "á", "â", "ä", "@", "ã", "å", "æ", "ā", "ą"), u)
    }

    @Test
    fun oneLanguageGivesItsOwnList() {
        assertEquals(italian, LetterDefaults.union(listOf(italian), KeyboardConfig.MAX_LETTER_ALTERNATES))
    }

    @Test
    fun theCapHolds() {
        val long = (1..20).map { "x$it" }
        assertEquals(long.take(14), LetterDefaults.union(listOf(long), 14))
        assertEquals(KeyboardConfig.MAX_LETTER_ALTERNATES, LetterDefaults.union(listOf(english, long), KeyboardConfig.MAX_LETTER_ALTERNATES).size)
    }

    @Test
    fun everyLetterOfEveryLanguageIsMerged() {
        val m = LetterDefaults.unionPerLetter(
            listOf(mapOf('a' to italian), mapOf('a' to english, 'n' to listOf("ñ"))),
            KeyboardConfig.MAX_LETTER_ALTERNATES,
        )
        assertEquals(setOf('a', 'n'), m.keys)
        assertEquals(listOf("ñ"), m['n'])
        assertEquals(LetterDefaults.union(listOf(italian, english), 14), m['a'])
    }
}
