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

import com.kinetica.keyboard.engine.models.WordCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The language a committed word is learned into, read off the candidate it came from.
 *
 * `languageOf` lowercases the word it is asked about, so the map is keyed by the lowercase form.
 * Keyed by display form, every capitalized German noun missed it and was learned into the active
 * language.
 */
class ProvenanceTest {

    private fun candidate(word: String, language: String) =
        WordCandidate(word, 1f, 0f, 1f, 1f, 0, WordCandidate.Source.SWIPE, language)

    @Test
    fun aCapitalizedCandidateIsFoundByItsLowercaseWord() {
        val map = provenanceOf(listOf(candidate("Haus", "de"), candidate("house", "en")))
        assertEquals("de", map["haus"])
        assertEquals("en", map["house"])
    }

    @Test
    fun theHigherRankedSpellingKeepsTheWord() {
        // `Leben` and `leben` are both German; the first one listed decides, as it did before.
        val map = provenanceOf(listOf(candidate("Leben", "de"), candidate("leben", "xx")))
        assertEquals("de", map["leben"])
    }

    @Test
    fun aCandidateWithNoLanguageSaysNothing() {
        assertNull(provenanceOf(listOf(candidate("word", "")))["word"])
    }
}
