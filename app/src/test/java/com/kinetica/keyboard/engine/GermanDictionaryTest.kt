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
 * German real-asset goldens (ADDING_A_LANGUAGE.md §6): load within the shared
 * memory budget, common-word swipe decodes, accent restoration through both
 * the forms path and tap-autocorrect, and a latency bound against the new
 * dictionary's fan-out. Common words run on both the base QWERTY geometry
 * and the Y/Z swap used by the QWERTZ setting.
 */
class GermanDictionaryTest {

    private fun assetPath(name: String): Path {
        val direct = Paths.get("src/main/assets/dictionaries/$name")
        if (Files.exists(direct)) return direct
        return Paths.get("app/src/main/assets/dictionaries/$name")
    }

    private fun loadDict(): LoadedDictionary {
        val p = assetPath("de_wordlist.txt")
        assumeTrue("de wordlist asset not found", Files.exists(p))
        return Files.newBufferedReader(p).use { DictionaryLoader.load(it) }
    }

    @Test
    fun germanDictionaryLoadsWithinMemoryBudget() {
        val dict = loadDict()
        assertTrue("word count ${dict.trie.wordCount}", dict.trie.wordCount >= 30_000)
        assertTrue("trie bytes ${dict.trie.sizeBytes()}", dict.trie.sizeBytes() < 4 * 1024 * 1024)
        for (w in listOf("ich", "groß", "dafür", "schön")) {
            assertTrue("missing $w", dict.trie.contains(AccentFolder.fold(w)))
        }
        assertTrue("forms table empty", dict.forms.isNotEmpty())
    }

    @Test
    fun germanBigramsLoadAndBoost() {
        val dict = loadDict()
        val p = assetPath("de_bigrams.txt")
        assumeTrue("de bigram asset not found", Files.exists(p))
        val table = Files.newBufferedReader(p).use { DictionaryLoader.loadBigrams(it, dict.trie) }
        
        assertTrue("bigram count ${table.size}", table.size > 40_000)
        assertTrue("table bytes ${table.sizeBytes()}", table.sizeBytes() < 4 * 1024 * 1024)
        val boost = table.multiplier(dict.trie.nodeFor("ich"), dict.trie.nodeFor("bin"))
        
        // Assert the asset's share of the available boost, independent of engine tuning.
        val share = (boost - 1f) / KineticaConstants.BIGRAM_BOOST_MAX
        assertTrue("ich->bin boost $boost, share $share of the cap", share > 0.5f)
    }

    @Test
    fun commonGermanSwipesDecodeTop1() =
        assertCommonGermanSwipesDecodeTop1(TestData.qwertyGeometry())

    @Test
    fun commonGermanQwertzSwipesDecodeTop1() =
        assertCommonGermanSwipesDecodeTop1(TestData.qwertzGeometry())

    private fun assertCommonGermanSwipesDecodeTop1(g: KeyboardGeometry) {
        val dict = loadDict()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        val words = mapOf(
            "ich" to "ich",
            "aber" to "aber",
            "danke" to "danke",
            "nicht" to "nicht",
            "naturlich" to "natürlich",
            "gross" to "groß",
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
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        val result = predictor.decode(
            listOf(TestData.swipe("fruher", g, 0, 600)), emptyList(),
        )
        assertTrue(
            "'früher' missing from ${result.map { it.word }}",
            result.map { it.word }.contains("früher"),
        )
    }

    @Test
    fun accentRestoredThroughTapAutocorrect() {
        // "fruh" reaches the "früh" node but is not itself a dictionary
        // spelling, so exact taps can restore the German umlaut.
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        assertFalse(predictor.isWord("fruh"))
        val tokens = "fruh".mapIndexed { i, c -> TestData.tap(c, g, i * 100L) }
        val result = predictor.decode(tokens, emptyList())
        val target = predictor.autocorrectTarget(
            "fruh", result, KineticaConstants.AUTOCORRECT_CONF_NORMAL,
        )
        assertNotNull("autocorrect did not fire on ${result.map { it.word }}", target)
        assertEquals("früh", target?.word)
    }

    @Test
    fun germanDecodeLatencyIsBounded() {
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        val tokens = listOf(TestData.swipe("danke", g, 0, 500))
        predictor.decode(tokens, emptyList()) // warmup
        val t0 = System.nanoTime()
        repeat(20) { predictor.decode(tokens, emptyList()) }
        val perDecodeMs = (System.nanoTime() - t0) / 20 / 1_000_000.0
        assertTrue("de decode took $perDecodeMs ms", perDecodeMs < 100.0)
    }
}
