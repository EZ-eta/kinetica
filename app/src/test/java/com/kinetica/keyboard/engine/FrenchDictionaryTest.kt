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
 * French real-asset goldens (ADDING_A_LANGUAGE.md §6). Common words run on
 * the AZERTY geometry as well as QWERTY, because AZERTY is a different board
 * (rows 10/10/6, M on the home row) rather than a letter swap, and a golden
 * decoded on the wrong geometry would prove nothing about it.
 */
class FrenchDictionaryTest {

    private fun assetPath(name: String): Path {
        val direct = Paths.get("src/main/assets/dictionaries/$name")
        if (Files.exists(direct)) return direct
        return Paths.get("app/src/main/assets/dictionaries/$name")
    }

    private fun loadDict(): LoadedDictionary {
        val p = assetPath("fr_wordlist.txt")
        assumeTrue("fr wordlist asset not found", Files.exists(p))
        return Files.newBufferedReader(p).use { DictionaryLoader.load(it) }
    }

    @Test
    fun frenchDictionaryLoadsWithinMemoryBudget() {
        val dict = loadDict()
        assertTrue("word count ${dict.trie.wordCount}", dict.trie.wordCount >= 30_000)
        assertTrue("trie bytes ${dict.trie.sizeBytes()}", dict.trie.sizeBytes() < 4 * 1024 * 1024)
        for (w in listOf("merci", "bonjour", "toujours", "être")) {
            assertTrue("missing $w", dict.trie.contains(AccentFolder.fold(w)))
        }
        assertTrue("forms table empty", dict.forms.isNotEmpty())
    }

    @Test
    fun frenchBigramsLoadAndBoost() {
        val dict = loadDict()
        val p = assetPath("fr_bigrams.txt")
        assumeTrue("fr bigram asset not found", Files.exists(p))
        val table = Files.newBufferedReader(p).use { DictionaryLoader.loadBigrams(it, dict.trie) }
        // 733k Tatoeba sentences fill the generator's 100k pair cap.
        assertTrue("bigram count ${table.size}", table.size > 95_000)
        assertTrue("table bytes ${table.sizeBytes()}", table.sizeBytes() < 4 * 1024 * 1024)
        val boost = table.multiplier(dict.trie.nodeFor("il"), dict.trie.nodeFor("est"))
        // Assert the asset's share of the available boost, independent of engine tuning.
        val share = (boost - 1f) / KineticaConstants.BIGRAM_BOOST_MAX
        assertTrue("il->est boost $boost, share $share of the cap", share > 0.5f)
    }

    @Test
    fun commonFrenchQwertySwipesDecodeTop1() =
        assertCommonFrenchSwipesDecodeTop1(TestData.qwertyGeometry())

    @Test
    fun commonFrenchAzertySwipesDecodeTop1() =
        assertCommonFrenchSwipesDecodeTop1(TestData.azertyGeometry())


    private fun assertCommonFrenchSwipesDecodeTop1(g: KeyboardGeometry) {
        val dict = loadDict()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        val words = mapOf(
            "merci" to "merci",
            "bonjour" to "bonjour",
            "aussi" to "aussi",
            "toujours" to "toujours",
            "beaucoup" to "beaucoup",
            "soeur" to "sœur",
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
            listOf(TestData.swipe("etre", g, 0, 400)), emptyList(),
        )
        assertTrue(
            "'être' missing from ${result.map { it.word }}",
            result.map { it.word }.contains("être"),
        )
    }

    @Test
    fun accentRestoredThroughTapAutocorrect() {
        // "realite" reaches the "réalité" node but is not itself a French
        // spelling, so exact taps restore both accents.
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        assertFalse(predictor.isWord("realite"))
        val tokens = "realite".mapIndexed { i, c -> TestData.tap(c, g, i * 100L) }
        val result = predictor.decode(tokens, emptyList())
        val target = predictor.autocorrectTarget(
            "realite", result, KineticaConstants.AUTOCORRECT_CONF_NORMAL,
        )
        assertNotNull("autocorrect did not fire on ${result.map { it.word }}", target)
        assertEquals("réalité", target?.word)
    }

    @Test
    fun oeLigatureFoldsToTwoLetters() {
        // œ is the second digraph in the fold map after ß, added for French.
        // "sœur" and the tolerated "soeur" therefore share one node, and a
        // swipe through s-o-e-u-r reaches the ligature spelling.
        assertEquals("soeur", AccentFolder.fold("sœur"))
        assertEquals(-1, AccentFolder.accentedLetterCode("œ"))
        val dict = loadDict()
        val node = dict.trie.nodeFor("soeur")
        assertTrue("sœur node missing", node != -1)
        val shown = dict.forms[node]?.map { it.display } ?: emptyList()
        assertTrue("sœur absent from $shown", shown.contains("sœur"))
    }

    @Test
    fun frenchDecodeLatencyIsBounded() {
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        val tokens = listOf(TestData.swipe("toujours", g, 0, 600))
        predictor.decode(tokens, emptyList()) // warmup
        val t0 = System.nanoTime()
        repeat(20) { predictor.decode(tokens, emptyList()) }
        val perDecodeMs = (System.nanoTime() - t0) / 20 / 1_000_000.0
        assertTrue("fr decode took $perDecodeMs ms", perDecodeMs < 100.0)
    }
}
