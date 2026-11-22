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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The built-in long-press lists follow the enabled languages: `ß` on `s` while German is on, without
 * the Default button. A list the user wrote is shown as written.
 */
class LanguageAccentsTest {

    private val cap = 14

    @Test
    fun eachLanguageLetterLandsOnTheKeyItFoldsTo() {
        assertEquals(listOf("ß"), LanguageAccents.of('s', listOf("de")))
        assertEquals(listOf("ä"), LanguageAccents.of('a', listOf("de")))
        assertEquals(listOf("ș", "ś"), LanguageAccents.of('s', listOf("ro", "pl")))
        assertTrue(LanguageAccents.of('s', listOf("en", "it")).isEmpty())
        // Every letter of every language reaches a key of the Latin board.
        for (lang in NativeLetters.languages) {
            for (c in NativeLetters.of(lang)) {
                val hits = ('a'..'z').filter { c.toString() in LanguageAccents.of(it, listOf(lang)) }
                assertEquals("$lang $c", 1, hits.size)
            }
        }
    }

    @Test
    fun aBuiltInListGainsTheLettersAfterItsLast() {
        assertEquals(listOf("š", "ß", "#"), LanguageAccents.added(listOf("š", "#"), 's', listOf("it", "de")))
        // No letter in it: at the end, so the plain long-press stays `#`.
        assertEquals(listOf("#", "ß"), LanguageAccents.added(listOf("#"), 's', listOf("de")))
        // Nothing to add: the same list, so the mutation copies no key.
        val same = listOf("ß", "#")
        assertSame(same, LanguageAccents.added(same, 's', listOf("de")))
    }

    private fun italian() = KeyboardLayout(
        name = "qwerty_it", locale = "it_IT",
        keys = listOf(
            Key("s", KeyType.CHAR, "s", "s", 0.15f, 0.25f, 0.1f, 0.25f, alternates = listOf("š", "#")),
            Key("a", KeyType.CHAR, "a", "a", 0.05f, 0.25f, 0.1f, 0.25f, alternates = listOf("à", "@")),
        ),
        nativeAccents = true,
    )

    private fun cells(prefs: Map<String, Any>, id: String): List<String> =
        AlphaLayouts.build(italian(), MapPrefs.config(prefs + (Prefs.NUMBER_PRIORITY to false)))
            .keys.first { it.id == id }.alternates

    @Test
    fun enablingGermanPutsItsLettersOnTheItalianBoard() {
        val it = mapOf<String, Any>(Prefs.LANGUAGE to "it", Prefs.ENABLED_LANGUAGES to setOf("it"))
        val de = mapOf<String, Any>(Prefs.LANGUAGE to "it", Prefs.ENABLED_LANGUAGES to setOf("it", "de"))
        assertEquals(listOf("š", "#"), cells(it, "s"))
        assertEquals(listOf("š", "ß", "#"), cells(de, "s"))
        assertEquals(listOf("à", "ä", "@"), cells(de, "a"))
    }

    private fun polish() = KeyboardLayout(
        name = "qwerty_pl", locale = "pl_PL",
        keys = listOf(
            Key("i", KeyType.CHAR, "i", "i", 0.75f, 0f, 0.1f, 0.25f, alternates = listOf("ì", "í", "î", "ï", "ī", "8")),
            Key("o", KeyType.CHAR, "o", "o", 0.85f, 0f, 0.1f, 0.25f, alternates = listOf("ó", "ò", "ô", "ö", "9")),
        ),
        nativeAccents = true,
    )

    private fun english() = polish().copy(name = "qwerty", locale = "en_US", nativeAccents = false)

    private fun cellsOn(board: KeyboardLayout, prefs: Map<String, Any>, id: String): List<String> =
        AlphaLayouts.build(board, MapPrefs.config(prefs + (Prefs.NUMBER_PRIORITY to false)))
            .keys.first { it.id == id }.alternates

    /**
     * A Polish and English writer took Default on `i`, then deleted some: `ï` belongs to neither
     * language and was dropped from the stored list, so Default again was the only way back.
     */
    @Test
    fun aStoredListKeepsALetterNoEnabledLanguageWrites() {
        val plEn = mapOf<String, Any>(
            Prefs.LANGUAGE to "pl",
            Prefs.ENABLED_LANGUAGES to setOf("pl", "en"),
            Prefs.LANGUAGE_MODE to "follow",
            Prefs.PLAIN_LETTER_ALTERNATES to true,
            Prefs.letterAlternatesKey('i') to "ï 8",
        )
        assertEquals(listOf("ï", "8"), cellsOn(polish(), plEn, "i"))
        // The English board too: the hide setting never trims a list the user wrote.
        assertEquals(listOf("ï", "8"), cellsOn(english(), plEn + (Prefs.LANGUAGE to "en"), "i"))
    }

    @Test
    fun aStoredListKeepsALetterTheUserDeleted() {
        // Default on `o`, then ó deleted: Polish is on, but the deletion is the user's.
        val pl = mapOf<String, Any>(
            Prefs.LANGUAGE to "pl",
            Prefs.ENABLED_LANGUAGES to setOf("pl", "en"),
            Prefs.letterAlternatesKey('o') to "ö 9",
        )
        assertEquals(listOf("ö", "9"), cellsOn(polish(), pl, "o"))
    }

    @Test
    fun aStoredListIgnoresALanguageTurnedOnOrOff() {
        val it = mapOf<String, Any>(Prefs.LANGUAGE to "it", Prefs.ENABLED_LANGUAGES to setOf("it"))
        val de = mapOf<String, Any>(Prefs.LANGUAGE to "it", Prefs.ENABLED_LANGUAGES to setOf("it", "de"))
        val stored = mapOf<String, Any>(Prefs.letterAlternatesKey('s') to "# $ ß")
        assertEquals(listOf("#", "$", "ß"), cells(it + stored, "s"))
        assertEquals(listOf("#", "$", "ß"), cells(de + stored, "s"))
        val noEszett = mapOf<String, Any>(Prefs.letterAlternatesKey('s') to "# $")
        assertEquals(listOf("#", "$"), cells(de + noEszett, "s"))
    }
}
