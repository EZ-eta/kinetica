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

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * AccentFolder.fold folds case too, so a wordlist can carry a capitalized display form on a
 * lowercase trie key. The fold is shared, so it must be inert for every lowercase asset, which
 * is all of them but German: no bundled asset outside German may contain an uppercase letter,
 * and every language still loads its full vocabulary.
 */
class FoldCaseInertnessTest {

    private fun assetPath(name: String): Path {
        val direct = Paths.get("src/main/assets/dictionaries/$name")
        if (Files.exists(direct)) return direct
        return Paths.get("app/src/main/assets/dictionaries/$name")
    }

    private val lowercaseLangs = listOf("en", "it", "es", "pl", "cs", "nl", "fr", "no", "ro")

    @Test
    fun everyAssetOutsideGermanIsStillEntirelyLowercase() {
        // If this ever fails, some other language has grown a display form that
        // depends on the case fold, and its own goldens have to say so.
        for (lang in lowercaseLangs) {
            val p = assetPath("${lang}_wordlist.txt")
            assumeTrue("$lang wordlist not found", Files.exists(p))
            var offenders = 0
            var first = ""
            Files.newBufferedReader(p).use { r ->
                r.forEachLine { line ->
                    val tab = line.indexOf('\t')
                    if (tab > 0) {
                        val w = line.substring(0, tab)
                        if (w.any { it.isUpperCase() }) {
                            if (offenders == 0) first = w
                            offenders++
                        }
                    }
                }
            }
            assertEquals("$lang has $offenders uppercase entries, first '$first'", 0, offenders)
        }
    }

    @Test
    fun theCaseFoldChangesNothingForALowercaseCorpus() {
        // The fold's own contract on the shape every existing asset has.
        for (w in listOf("perche", "citta", "zazolc", "prilis", "vare", "soeur", "colour")) {
            assertEquals(AccentFolder.fold(w), AccentFolder.fold(w.lowercase()))
        }
    }

    @Test
    fun everyBundledLanguageStillLoadsAndKeepsItsVocabulary() {
        for (lang in lowercaseLangs + "de") {
            val p = assetPath("${lang}_wordlist.txt")
            assumeTrue("$lang wordlist not found", Files.exists(p))
            val dict = Files.newBufferedReader(p).use { DictionaryLoader.load(it) }
            assertTrue(
                "$lang lost words to the fold: ${dict.trie.wordCount}",
                dict.trie.wordCount >= 30_000,
            )
            assertTrue(
                "$lang trie over budget: ${dict.trie.sizeBytes()}",
                dict.trie.sizeBytes() < 4 * 1024 * 1024,
            )
        }
    }
}
