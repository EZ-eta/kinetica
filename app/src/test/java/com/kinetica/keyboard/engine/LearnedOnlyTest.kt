package com.kinetica.keyboard.engine

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * With no primary language, a spelling a trie holds only because the user learned it is not that
 * list's: it neither claims a commit nor moves the momentum. Otherwise an Italian word missing
 * from both lists, filed once into English, merges there after two commits and pulls every later
 * commit, and the momentum, to English. Real `en` and `it` lists.
 */
class LearnedOnlyTest {

    private val g = TestData.qwertyGeometry()

    @Test
    fun aLearnedOnlySpellingIsNotTheLists() {
        val en = predictor("en", "scarabocchiare" to 6)
        assertTrue(en.holdsSpelling("scarabocchiare"))
        assertFalse(en.holdsListSpelling("scarabocchiare"))
        assertEquals(0f, en.momentumScore("scarabocchiare"))
    }

    @Test
    fun aBundledWordWithACountStaysTheLists() {
        val en = predictor("en", "house" to 6)
        assertTrue(en.holdsListSpelling("house"))
        assertTrue(en.holdsListSpelling("House"))
        assertTrue(en.momentumScore("house") > 0f)
    }

    @Test
    fun aLearnedOnlyWordMovesNoMomentum() {
        val it = predictor("it")
        val en = predictor("en", "scarabocchiare" to 6)
        val m = LanguageMomentum()
        fun commit(word: String) = m.observe(mapOf("it" to it.momentumScore(word), "en" to en.momentumScore(word)))
        for (w in ITALIAN) commit(w)
        assertEquals("it", m.front(listOf("it", "en")))
        repeat(6) { commit("scarabocchiare") }
        val w = m.weightsFor(listOf("it", "en"))
        assertEquals("it", m.front(listOf("it", "en")))
        assertEquals(1f, w["it"])
        assertTrue("en=${w["en"]}", w.getValue("en") < 1f)
    }

    @Test
    fun aWordMisfiledIntoEnglishGoesBackToItalian() {
        // `scuola` learned into English 300 times: as English's trie holds it then, the two bytes
        // are close enough that only the last single-list word could decide, and there is none.
        val lists = mapOf("it" to predictor("it"), "en" to predictor("en", "scuola" to 300))
        assertTrue(lists.getValue("en").holdsSpelling("scuola"))
        assertEquals("it", SharedWordFiling().languageFor(held("scuola", lists), "en", lists.keys))
    }

    @Test
    fun anItalianWordNoListHoldsFollowsTheItalianAroundIt() {
        // Six commits of it in English: its trie holds it, so the decode draws it from there.
        val lists = mapOf("it" to predictor("it"), "en" to predictor("en", "scarabocchiare" to 6))
        val f = SharedWordFiling()
        for (w in ITALIAN) f.languageFor(held(w, lists), "it", lists.keys)
        assertEquals("it", f.languageFor(held("scarabocchiare", lists), "en", lists.keys))
    }

    /** ResidentDictionaries.heldBy with no primary language: the bundled lists alone. */
    private fun held(word: String, lists: Map<String, WordPredictor>): Map<String, Int> =
        lists.filterValues { it.holdsListSpelling(word) }.mapValues { it.value.frequencyByte(word) }

    private fun predictor(lang: String, vararg learned: Pair<String, Int>): WordPredictor {
        val path = assetPath("${lang}_wordlist.txt")
        assumeTrue("$lang assets not found", Files.exists(path))
        val dict = Files.newBufferedReader(path).use {
            DictionaryLoader.load(it, DictionaryLoader.userWordsForMerge(learned.toList()))
        }
        return WordPredictor(
            dict.trie, BigramTable.EMPTY, g, dict.forms, learned.toMap(), language = lang, learnedOnly = dict.learned,
        )
    }

    private fun assetPath(name: String): Path {
        val direct = Paths.get("src/main/assets/dictionaries/$name")
        if (Files.exists(direct)) return direct
        return Paths.get("app/src/main/assets/dictionaries/$name")
    }

    private companion object {
        val ITALIAN = listOf("oggi", "andiamo", "a", "scuola", "e", "poi", "domani", "di", "nuovo", "al", "mare")
    }
}
