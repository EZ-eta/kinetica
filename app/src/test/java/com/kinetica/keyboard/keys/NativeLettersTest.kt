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

import com.kinetica.keyboard.ime.AlphaLayouts
import com.kinetica.keyboard.layout.Key
import com.kinetica.keyboard.layout.KeyType
import com.kinetica.keyboard.layout.KeyboardLayout
import com.kinetica.keyboard.settings.MapPrefs
import com.kinetica.keyboard.settings.Prefs
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Hiding accented letters keeps the letters of every enabled language.
 *
 * Trimmed to the active board alone, a Polish writer with English active would lose ą and ł though
 * Polish is enabled, and write each letter's list back by hand.
 */
class NativeLettersTest {

    private fun english() = KeyboardLayout(
        name = "qwerty", locale = "en_US",
        keys = listOf(
            Key("a", KeyType.CHAR, "a", "a", 0.05f, 0.25f, 0.1f, 0.25f,
                alternates = listOf("à", "á", "ä", "ą", "@")),
            Key("l", KeyType.CHAR, "l", "l", 0.85f, 0.25f, 0.1f, 0.25f, alternates = listOf("ł")),
            Key("o", KeyType.CHAR, "o", "o", 0.8f, 0f, 0.1f, 0.25f,
                alternates = listOf("ò", "ó", "ö", "9")),
        ),
    )

    private fun cells(languages: List<String>, id: String): List<String> {
        val config = MapPrefs.config(
            mapOf(
                Prefs.PLAIN_LETTER_ALTERNATES to true,
                Prefs.ENABLED_LANGUAGES to languages.toSet(),
            ),
        )
        assertEquals(languages.toSet(), config.enabledLanguages.toSet())
        return AlphaLayouts.build(english(), config).keys.first { it.id == id }.alternates
    }

    @Test
    fun polishEnabledKeepsItsLettersOnTheEnglishKeys() {
        // As sets: number priority, on by default, puts the symbol first.
        assertEquals(setOf("ą", "@"), cells(listOf("en", "pl"), "a").toSet())
        assertEquals(setOf("ó", "9"), cells(listOf("en", "pl"), "o").toSet())
    }

    @Test
    fun englishAloneStillHidesThem() {
        assertEquals(listOf("@"), cells(listOf("en"), "a"))
        assertEquals(listOf("9"), cells(listOf("en"), "o"))
    }

    @Test
    fun everyLetterIsOneItsLanguageWrites() {
        for (lang in Prefs.ALL_LANGUAGES) {
            val letters = NativeLetters.of(lang)
            if (letters.isEmpty()) continue
            val p = listOf(
                Paths.get("src/main/assets/dictionaries/${lang}_wordlist.txt"),
                Paths.get("app/src/main/assets/dictionaries/${lang}_wordlist.txt"),
            ).firstOrNull { Files.exists(it) }
            assumeTrue("$lang wordlist not found", p != null)
            val text = Files.readAllLines(p!!).joinToString("\n") { it.substringBefore('\t').lowercase() }
            for (c in letters) assertTrue("$lang never writes $c", c in text)
        }
        assertTrue(NativeLetters.of("en").isEmpty())
        assertFalse(NativeLetters.keptBy(listOf("en", "pl")).contains("à"))
    }
}
