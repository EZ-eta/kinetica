package com.kinetica.keyboard.engine

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Romanian real-asset goldens (ADDING_A_LANGUAGE.md §6): load within the shared
 * memory budget, common-word swipe decodes, accent restoration through both
 * the forms path and tap-autocorrect, and a latency bound against the new
 * dictionary's fan-out. Romanian types on the base QWERTY geometry, so unlike
 * Czech there is no second arrangement to cover. Golden words were measured
 * first; every one leads its swipe at every overshoot value.
 */
class RomanianDictionaryTest {

    private fun assetPath(name: String): Path {
        val direct = Paths.get("src/main/assets/dictionaries/$name")
        if (Files.exists(direct)) return direct
        return Paths.get("app/src/main/assets/dictionaries/$name")
    }

    private fun loadDict(): LoadedDictionary {
        val p = assetPath("ro_wordlist.txt")
        assumeTrue("ro wordlist asset not found", Files.exists(p))
        return Files.newBufferedReader(p).use { DictionaryLoader.load(it) }
    }

    @Test
    fun romanianDictionaryLoadsWithinMemoryBudget() {
        val dict = loadDict()
        assertTrue("word count ${dict.trie.wordCount}", dict.trie.wordCount >= 30_000)
        assertTrue("trie bytes ${dict.trie.sizeBytes()}", dict.trie.sizeBytes() < 4 * 1024 * 1024)
        for (w in listOf("și", "mulțumesc", "română", "bună")) {
            assertTrue("missing $w", dict.trie.contains(AccentFolder.fold(w)))
        }
        assertTrue("forms table empty", dict.forms.isNotEmpty())
    }

    @Test
    fun romanianBigramsLoadAndBoost() {
        val dict = loadDict()
        val p = assetPath("ro_bigrams.txt")
        assumeTrue("ro bigram asset not found", Files.exists(p))
        val table = Files.newBufferedReader(p).use { DictionaryLoader.loadBigrams(it, dict.trie) }
        // The current Romanian Tatoeba corpus yields about 24k in-vocabulary
        // pairs after the generator's two-occurrence floor.
        assertTrue("bigram count ${table.size}", table.size > 20_000)
        assertTrue("table bytes ${table.sizeBytes()}", table.sizeBytes() < 4 * 1024 * 1024)
        val boost = table.multiplier(dict.trie.nodeFor("nu"), dict.trie.nodeFor("este"))
        // Assert the asset's share of the available boost, independent of engine tuning.
        val share = (boost - 1f) / KineticaConstants.BIGRAM_BOOST_MAX
        assertTrue("nu->este boost $boost, share $share of the cap", share > 0.5f)
    }

    @Test
    fun commonRomanianSwipesDecodeTop1() {
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        val words = mapOf(
            "familie" to "familie",
            "prieten" to "prieten",
            "timpul" to "timpul",
            "bine" to "bine",
            "multumesc" to "mulțumesc",
            "maine" to "mâine",
        )
        for ((folded, expected) in words) {
            for (overshoot in listOf(0f, 0.25f, 0.4f, 0.5f)) {
                val token = if (overshoot == 0f) {
                    TestData.swipe(folded, g, 0, 100L * folded.length)
                } else {
                    TestData.sloppySwipe(
                        folded, g, 0, 100L * folded.length, overshootKw = overshoot,
                    )
                }
                val result = predictor.decode(listOf(token), emptyList())
                assertTrue("'$expected' ($overshoot) produced no candidates", result.isNotEmpty())
                assertEquals(
                    "'$expected' ($overshoot) lost top-1 to ${result[0].word}",
                    expected,
                    result[0].word,
                )
            }
        }
    }

    @Test
    fun accentRestoredThroughDecodeForms() {
        // "fara" is a left-off spelling the wordlist dropped, so the swipe
        // reaches the "fără" node and the forms table offers the accent.
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        val result = predictor.decode(
            listOf(TestData.swipe("fara", g, 0, 600)), emptyList(),
        )
        assertTrue(
            "'fără' missing from ${result.map { it.word }}",
            result.map { it.word }.contains("fără"),
        )
    }

    @Test
    fun accentRestoredThroughTapAutocorrect() {
        // "masina" reaches the "mașina" node but is not itself a dictionary
        // spelling, so exact taps can restore the comma-below ș.
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        assertFalse(predictor.isWord("masina"))
        val tokens = "masina".mapIndexed { i, c -> TestData.tap(c, g, i * 100L) }
        val result = predictor.decode(tokens, emptyList())
        val target = predictor.autocorrectTarget(
            "masina", result, KineticaConstants.AUTOCORRECT_CONF_NORMAL,
        )
        assertNotNull("autocorrect did not fire on ${result.map { it.word }}", target)
        assertEquals("mașina", target?.word)
    }

    @Test
    fun romanianDecodeLatencyIsBounded() {
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        val tokens = listOf(TestData.swipe("multumesc", g, 0, 600))
        predictor.decode(tokens, emptyList()) // warmup
        val t0 = System.nanoTime()
        repeat(20) { predictor.decode(tokens, emptyList()) }
        val perDecodeMs = (System.nanoTime() - t0) / 20 / 1_000_000.0
        assertTrue("ro decode took $perDecodeMs ms", perDecodeMs < 100.0)
    }
}
