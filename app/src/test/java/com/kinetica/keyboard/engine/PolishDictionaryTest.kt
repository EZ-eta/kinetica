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
 * Polish real-asset goldens (ADDING_A_LANGUAGE.md §6): load within the shared
 * memory budget, common-word swipe decodes, accent restoration through both
 * the forms path and tap-autocorrect, and a latency bound against the new
 * dictionary's fan-out. qwerty_pl uses the shared QWERTY letter geometry, so
 * TestData.qwertyGeometry() is the language layout's decode surface.
 */
class PolishDictionaryTest {

    private fun assetPath(name: String): Path {
        val direct = Paths.get("src/main/assets/dictionaries/$name")
        if (Files.exists(direct)) return direct
        return Paths.get("app/src/main/assets/dictionaries/$name")
    }

    private fun loadDict(): LoadedDictionary {
        val p = assetPath("pl_wordlist.txt")
        assumeTrue("pl wordlist asset not found", Files.exists(p))
        return Files.newBufferedReader(p).use { DictionaryLoader.load(it) }
    }

    @Test
    fun polishDictionaryLoadsWithinMemoryBudget() {
        val dict = loadDict()
        assertTrue("word count ${dict.trie.wordCount}", dict.trie.wordCount >= 30_000)
        assertTrue("trie bytes ${dict.trie.sizeBytes()}", dict.trie.sizeBytes() < 4 * 1024 * 1024)
        for (w in listOf("nie", "dobrze", "prosze", "dziekuje")) {
            assertTrue("missing $w", dict.trie.contains(w))
        }
        assertTrue("forms table empty", dict.forms.isNotEmpty())
    }

    @Test
    fun polishBigramsLoadAndBoost() {
        val dict = loadDict()
        val p = assetPath("pl_bigrams.txt")
        assumeTrue("pl bigram asset not found", Files.exists(p))
        val table = Files.newBufferedReader(p).use { DictionaryLoader.loadBigrams(it, dict.trie) }
        assertTrue("bigram count ${table.size}", table.size > 50_000)
        assertTrue("table bytes ${table.sizeBytes()}", table.sizeBytes() < 4 * 1024 * 1024)
        val boost = table.multiplier(dict.trie.nodeFor("nie"), dict.trie.nodeFor("jest"))
        assertTrue("nie->jest boost $boost", boost > 1.5f)
    }

    @Test
    fun commonPolishSwipesDecodeTop1() {
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        for (word in listOf("nie", "jest", "tak", "dobrze", "czas", "dzisiaj")) {
            val result = predictor.decode(
                listOf(TestData.swipe(word, g, 0, 100L * word.length)), emptyList(),
            )
            assertTrue("'$word' produced no candidates", result.isNotEmpty())
            assertEquals("'$word' lost top-1 to ${result[0].word}", word, result[0].word)
        }
    }

    @Test
    fun accentRestoredThroughDecodeForms() {
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        val result = predictor.decode(
            listOf(TestData.swipe("dziekuje", g, 0, 800)), emptyList(),
        )
        assertTrue(
            "'dziękuję' missing from ${result.map { it.word }}",
            result.map { it.word }.contains("dziękuję"),
        )
    }

    @Test
    fun accentRestoredThroughTapAutocorrect() {
        // "pozno" reaches the "późno" node but is not itself a dictionary
        // spelling, so exact taps can restore both Polish accents.
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        assertFalse(predictor.isWord("pozno"))
        val tokens = "pozno".mapIndexed { i, c -> TestData.tap(c, g, i * 100L) }
        val result = predictor.decode(tokens, emptyList())
        val target = predictor.autocorrectTarget(
            "pozno", result, KineticaConstants.AUTOCORRECT_CONF_NORMAL,
        )
        assertNotNull("autocorrect did not fire on ${result.map { it.word }}", target)
        assertEquals("późno", target?.word)
    }

    @Test
    fun polishDecodeLatencyIsBounded() {
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        val tokens = listOf(TestData.swipe("dobrze", g, 0, 600))
        predictor.decode(tokens, emptyList()) // warmup
        val t0 = System.nanoTime()
        repeat(20) { predictor.decode(tokens, emptyList()) }
        val perDecodeMs = (System.nanoTime() - t0) / 20 / 1_000_000.0
        assertTrue("pl decode took $perDecodeMs ms", perDecodeMs < 100.0)
    }
}
