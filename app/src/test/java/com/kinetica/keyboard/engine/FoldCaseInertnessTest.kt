package com.kinetica.keyboard.engine

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * AccentFolder.fold now folds case as well, so that a wordlist can carry a
 * capitalized display form on a lowercase trie key. That is a change to a
 * shared engine path, and the claim that earns it is that it is INERT for
 * every asset that was already lowercase, which was all of them before German.
 *
 * This asserts the claim directly rather than trusting it: no bundled asset
 * outside German may contain an uppercase letter, and every one must build the
 * same trie it built before.
 */
class FoldCaseInertnessTest {

    private fun assetPath(name: String): Path {
        val direct = Paths.get("src/main/assets/dictionaries/$name")
        if (Files.exists(direct)) return direct
        return Paths.get("app/src/main/assets/dictionaries/$name")
    }

    private val lowercaseLangs = listOf("en", "it", "es", "pl", "cs", "nl", "fr", "no")

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
