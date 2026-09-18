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
 * German real-asset goldens (ADDING_A_LANGUAGE.md §6). Common words run on
 * both the base QWERTY geometry and the Y/Z swap, since QWERTZ is what a
 * German user will actually select. Eszett folds to two letters, so "grosse"
 * and "große" share one node and the forms table decides which is shown.
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
        for (w in listOf("danke", "bitte", "heute", "möglich")) {
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
        // 780k Tatoeba sentences fill the generator's 100k pair cap.
        assertTrue("bigram count ${table.size}", table.size > 95_000)
        assertTrue("table bytes ${table.sizeBytes()}", table.sizeBytes() < 4 * 1024 * 1024)
        val boost = table.multiplier(dict.trie.nodeFor("ich"), dict.trie.nodeFor("bin"))
        // Assert the asset's share of the available boost, independent of engine tuning.
        val share = (boost - 1f) / KineticaConstants.BIGRAM_BOOST_MAX
        assertTrue("ich->bin boost $boost, share $share of the cap", share > 0.5f)
    }

    @Test
    fun commonGermanQwertySwipesDecodeTop1() =
        assertCommonGermanSwipesDecodeTop1(TestData.qwertyGeometry())

    @Test
    fun commonGermanQwertzSwipesDecodeTop1() =
        assertCommonGermanSwipesDecodeTop1(TestData.qwertzGeometry())


    private fun assertCommonGermanSwipesDecodeTop1(g: KeyboardGeometry) {
        val dict = loadDict()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        val words = mapOf(
            "danke" to "danke",
            "bitte" to "bitte",
            "guten" to "guten",
            "heute" to "heute",
            "nicht" to "nicht",
            "moglich" to "möglich",
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
            listOf(TestData.swipe("fur", g, 0, 300)), emptyList(),
        )
        assertTrue(
            "'für' missing from ${result.map { it.word }}",
            result.map { it.word }.contains("für"),
        )
    }

    @Test
    fun accentRestoredThroughTapAutocorrect() {
        // "moglich" reaches the "möglich" node but is not itself a German
        // spelling, so exact taps restore the umlaut.
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        assertFalse(predictor.isWord("moglich"))
        val tokens = "moglich".mapIndexed { i, c -> TestData.tap(c, g, i * 100L) }
        val result = predictor.decode(tokens, emptyList())
        val target = predictor.autocorrectTarget(
            "moglich", result, KineticaConstants.AUTOCORRECT_CONF_NORMAL,
        )
        assertNotNull("autocorrect did not fire on ${result.map { it.word }}", target)
        assertEquals("möglich", target?.word)
    }

    @Test
    fun nounsCommitWithTheirCapital() {
        // The point of the case pass, end to end on the real asset: German
        // capitalizes every noun, FrequencyWords is lowercased, and the capital
        // rides back as a display form on the lowercase trie key.
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        // Measured, not assumed: every pair below leads at all four overshoot
        // values. "mann" is deliberately absent - it loses to "man", which is a
        // real German word, on the doubled n, and that is the double-letter
        // problem rather than anything to do with case.
        for ((folded, expected) in listOf(
            "haus" to "Haus", "zeit" to "Zeit", "arbeit" to "Arbeit",
            "kind" to "Kind", "welt" to "Welt", "vater" to "Vater",
        )) {
            val result = predictor.decode(
                listOf(TestData.swipe(folded, g, 0, 100L * folded.length)), emptyList(),
            )
            assertTrue("'$expected' produced no candidates", result.isNotEmpty())
            assertEquals(
                "'$expected' lost top-1 to ${result[0].word}", expected, result[0].word,
            )
        }
        // "morgen" is the adverb "tomorrow" more often than the noun
        // "Morgen", ratio 0.32, so the lowercase reading must lead. This is
        // the two-spelling path picking the corpus's own order.
        val morgen = predictor.decode(
            listOf(TestData.swipe("morgen", g, 0, 600)), emptyList(),
        )
        assertTrue("morgen produced no candidates", morgen.isNotEmpty())
        assertEquals("morgen", morgen[0].word)
        // A function word must NOT have been capitalized.
        assertTrue("nicht was capitalized", dict.trie.contains("nicht"))
        val nicht = dict.forms[dict.trie.nodeFor("nicht")]?.map { it.display }
        assertTrue("nicht carries a capital: $nicht", nicht == null || nicht.contains("nicht"))
    }

    @Test
    fun aWordThatIsBothANounAndAVerbKeepsBothSpellings() {
        // "Leben" is life and "leben" is to live, so a single display form
        // cannot serve. Every variant is offered as its own candidate, so both
        // ship with the frequency the corpus gives them.
        val dict = loadDict()
        val node = dict.trie.nodeFor("leben")
        assertTrue("leben node missing", node != -1)
        val shown = dict.forms[node]?.map { it.display } ?: emptyList()
        assertTrue("Leben absent from $shown", shown.contains("Leben"))
        assertTrue("leben absent from $shown", shown.contains("leben"))
        // The corpus writes the noun about 72% of the time, so it leads.
        assertEquals("Leben", shown.first())
        // And where the lowercase reading dominates, it leads instead: "recht"
        // as an adverb outnumbers the noun "Recht".
        val recht = dict.forms[dict.trie.nodeFor("recht")]?.map { it.display } ?: emptyList()
        assertEquals("recht", recht.first())
    }

    @Test
    fun eszettFoldsToTwoLettersAndShareSitsOnOneNode() {
        // ß folds to "ss", so "große" and the Swiss "grosse" are one trie key.
        // Asserted because it is the only fold in the map that changes a word's
        // LENGTH, which the swipe path has to agree with.
        assertEquals("grosse", AccentFolder.fold("große"))
        val dict = loadDict()
        val node = dict.trie.nodeFor("grosse")
        assertTrue("große node missing", node != -1)
        val shown = dict.forms[node]?.map { it.display } ?: emptyList()
        assertTrue("große absent from $shown", shown.contains("große"))
    }

    @Test
    fun germanDecodeLatencyIsBounded() {
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        val tokens = listOf(TestData.swipe("glucklich", g, 0, 600))
        predictor.decode(tokens, emptyList()) // warmup
        val t0 = System.nanoTime()
        repeat(20) { predictor.decode(tokens, emptyList()) }
        val perDecodeMs = (System.nanoTime() - t0) / 20 / 1_000_000.0
        assertTrue("de decode took $perDecodeMs ms", perDecodeMs < 100.0)
    }
}
