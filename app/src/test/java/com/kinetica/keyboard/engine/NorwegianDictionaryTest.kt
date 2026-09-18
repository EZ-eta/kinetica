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
 * Norwegian real-asset goldens (ADDING_A_LANGUAGE.md §6). Norwegian is the
 * first language whose own alphabet letters fold onto other letters that are
 * themselves common words: æ and å fold to "a", ø folds to "o", so "være"
 * shares a node with "vare" and "før" with "for". The collision tests below
 * pin which spelling the forms table shows, because that is the accepted cost
 * of the 27-symbol alphabet and it should fail loudly if it ever changes.
 */
class NorwegianDictionaryTest {

    private fun assetPath(name: String): Path {
        val direct = Paths.get("src/main/assets/dictionaries/$name")
        if (Files.exists(direct)) return direct
        return Paths.get("app/src/main/assets/dictionaries/$name")
    }

    private fun loadDict(): LoadedDictionary {
        val p = assetPath("no_wordlist.txt")
        assumeTrue("no wordlist asset not found", Files.exists(p))
        return Files.newBufferedReader(p).use { DictionaryLoader.load(it) }
    }

    @Test
    fun norwegianDictionaryLoadsWithinMemoryBudget() {
        val dict = loadDict()
        assertTrue("word count ${dict.trie.wordCount}", dict.trie.wordCount >= 30_000)
        assertTrue("trie bytes ${dict.trie.sizeBytes()}", dict.trie.sizeBytes() < 4 * 1024 * 1024)
        for (w in listOf("takk", "ikke", "være", "støtte")) {
            assertTrue("missing $w", dict.trie.contains(AccentFolder.fold(w)))
        }
        assertTrue("forms table empty", dict.forms.isNotEmpty())
    }

    @Test
    fun norwegianBigramsLoadAndBoost() {
        val dict = loadDict()
        val p = assetPath("no_bigrams.txt")
        assumeTrue("no bigram asset not found", Files.exists(p))
        val table = Files.newBufferedReader(p).use { DictionaryLoader.loadBigrams(it, dict.trie) }
        // Bokmål has only 18.1k Tatoeba sentences against Czech's 89.8k, so the
        // table is roughly a ninth the usual size. Documented, not a defect:
        // an absent pair leaves the boost at the neutral 1.0.
        assertTrue("bigram count ${table.size}", table.size > 10_000)
        assertTrue("table bytes ${table.sizeBytes()}", table.sizeBytes() < 4 * 1024 * 1024)
        val boost = table.multiplier(dict.trie.nodeFor("jeg"), dict.trie.nodeFor("er"))
        // Assert the asset's share of the available boost, independent of engine tuning.
        val share = (boost - 1f) / KineticaConstants.BIGRAM_BOOST_MAX
        assertTrue("jeg->er boost $boost, share $share of the cap", share > 0.5f)
    }

    @Test
    fun commonNorwegianQwertySwipesDecodeTop1() =
        assertCommonNorwegianSwipesDecodeTop1(TestData.qwertyGeometry())

    @Test
    fun commonNorwegianQwertzSwipesDecodeTop1() =
        assertCommonNorwegianSwipesDecodeTop1(TestData.qwertzGeometry())


    private fun assertCommonNorwegianSwipesDecodeTop1(g: KeyboardGeometry) {
        val dict = loadDict()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        val words = mapOf(
            "takk" to "takk",
            "ikke" to "ikke",
            "hvordan" to "hvordan",
            // The three native letters, each folded onto the key it is drawn
            // on: æ in "være" and "lære", ø and å together in "spørsmål".
            "vare" to "være",
            "sporsmal" to "spørsmål",
            "lare" to "lære",
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
            listOf(TestData.swipe("vare", g, 0, 400)), emptyList(),
        )
        assertTrue(
            "'være' missing from ${result.map { it.word }}",
            result.map { it.word }.contains("være"),
        )
    }

    @Test
    fun accentRestoredThroughTapAutocorrect() {
        // "sarlig" reaches the "særlig" node but is not itself a Norwegian
        // spelling, so exact taps restore the æ.
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        assertFalse(predictor.isWord("sarlig"))
        val tokens = "sarlig".mapIndexed { i, c -> TestData.tap(c, g, i * 100L) }
        val result = predictor.decode(tokens, emptyList())
        val target = predictor.autocorrectTarget(
            "sarlig", result, KineticaConstants.AUTOCORRECT_CONF_NORMAL,
        )
        assertNotNull("autocorrect did not fire on ${result.map { it.word }}", target)
        assertEquals("særlig", target?.word)
    }

    @Test
    fun nativeLettersFoldOntoTheKeyTheySitOn() {
        // å and æ are alternates on the "a" key and ø is one on "o", so each
        // must fold to that key or a swipe could never reach the word.
        assertEquals("a", AccentFolder.fold("å"))
        assertEquals("a", AccentFolder.fold("æ"))
        assertEquals("o", AccentFolder.fold("ø"))
        // Single-letter folds, so all three stay insertable from a long-press
        // popup mid-word, unlike ß and œ.
        assertEquals(0, AccentFolder.accentedLetterCode("å"))
        assertEquals(0, AccentFolder.accentedLetterCode("æ"))
        assertEquals(14, AccentFolder.accentedLetterCode("ø"))
    }

    @Test
    fun foldingCollisionsResolveToTheMoreFrequentSpelling() {
        // The accepted cost of æ/ø/å folding onto a-z, measured rather than
        // assumed: 630 of 49 123 Norwegian keys carry more than one spelling.
        // In every pair below the frequency order is also the one a reader
        // wants, so the forms table shows the right word first.
        val dict = loadDict()
        fun shown(folded: String): List<String> =
            dict.forms[dict.trie.nodeFor(folded)]?.map { it.display } ?: emptyList()
        // "være" (to be) outranks the noun "vare"; "for" outranks "før".
        assertEquals("være", shown("vare").first())
        assertEquals("for", shown("for").first())
        assertEquals("så", shown("sa").first())
        assertEquals("måte", shown("mate").first())
        // A lone "a" shows "å", the infinitive marker, which is what
        // StandaloneLetters treats as a one-letter Norwegian word.
        assertEquals("å", shown("a").first())
    }

    @Test
    fun norwegianDecodeLatencyIsBounded() {
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        val tokens = listOf(TestData.swipe("hvordan", g, 0, 600))
        predictor.decode(tokens, emptyList()) // warmup
        val t0 = System.nanoTime()
        repeat(20) { predictor.decode(tokens, emptyList()) }
        val perDecodeMs = (System.nanoTime() - t0) / 20 / 1_000_000.0
        assertTrue("no decode took $perDecodeMs ms", perDecodeMs < 100.0)
    }
}
