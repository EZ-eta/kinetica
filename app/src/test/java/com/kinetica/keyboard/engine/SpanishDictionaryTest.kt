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
 * Spanish real-asset goldens (ADDING_A_LANGUAGE.md §6): load within the
 * shared memory budget, common-word swipe decodes, accent restoration through
 * both the forms path and tap-autocorrect, and a latency bound against the
 * new dictionary's fan-out. qwerty_es copies the qwerty_it letter grid, so
 * the shared TestData geometry is the correct decode surface.
 */
class SpanishDictionaryTest {

    private fun assetPath(name: String): Path {
        val direct = Paths.get("src/main/assets/dictionaries/$name")
        if (Files.exists(direct)) return direct
        return Paths.get("app/src/main/assets/dictionaries/$name")
    }

    private fun loadDict(): LoadedDictionary {
        val p = assetPath("es_wordlist.txt")
        assumeTrue("es wordlist asset not found", Files.exists(p))
        return Files.newBufferedReader(p).use { DictionaryLoader.load(it) }
    }

    @Test
    fun spanishDictionaryLoadsWithinMemoryBudget() {
        val dict = loadDict()
        assertTrue("word count ${dict.trie.wordCount}", dict.trie.wordCount >= 30_000)
        assertTrue("trie bytes ${dict.trie.sizeBytes()}", dict.trie.sizeBytes() < 4 * 1024 * 1024)
        for (w in listOf("que", "gracias", "hola", "tiempo")) {
            assertTrue("missing $w", dict.trie.contains(w))
        }
        // Accent folding at load: the accented display lives in forms, keyed
        // by its folded trie node.
        assertTrue("forms table empty", dict.forms.isNotEmpty())
    }

    @Test
    fun spanishBigramsLoadAndBoost() {
        val dict = loadDict()
        val p = assetPath("es_bigrams.txt")
        assumeTrue("es bigram asset not found", Files.exists(p))
        val table = Files.newBufferedReader(p).use { DictionaryLoader.loadBigrams(it, dict.trie) }
        assertTrue("bigram count ${table.size}", table.size > 50_000)
        assertTrue("table bytes ${table.sizeBytes()}", table.sizeBytes() < 4 * 1024 * 1024)
        val boost = table.multiplier(dict.trie.nodeFor("de"), dict.trie.nodeFor("la"))
        assertTrue("de->la boost $boost", boost > 1.5f)
    }

    @Test
    fun commonSpanishSwipesDecodeTop1() {
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        // "siempre" deliberately absent from THIS loop: it IS in the wordlist
        // (rank ~125, freq 424363 - an earlier comment claiming otherwise was
        // stale), but its clean synthetic path has a documented endpoint
        // ambiguity against "dormir" (d starts within R_ENDPOINT_KW of s, r
        // ends within reach of e). Its sloppy-path behavior is locked in
        // siempreSurvivesSloppySwipes below.
        for (word in listOf("hola", "gracias", "tiempo", "cuando", "trabajo", "casa")) {
            val result = predictor.decode(
                listOf(TestData.swipe(word, g, 0, 100L * word.length)), emptyList(),
            )
            assertTrue("'$word' produced no candidates", result.isNotEmpty())
            assertEquals("'$word' lost top-1 to ${result[0].word}", word, result[0].word)
        }
    }

    @Test
    fun cuandoOutranksPersonallyReinforcedCunado() {
        // Live-poisoning regression, from a device capture: the misfire
        // loop had learned "cuñado" to personal count 6 in the es dictionary.
        // Even with that state merged in (freq +6000, personal count 6), a
        // sloppy single-swipe c-u-a-n-d-o must keep "cuando" on top - fw still
        // favors cuando ~1.5x and geometry must not close the gap through the
        // a<->n multi-pass slack.
        //
        // Measured after the boost was fit-conditioned: on this path "cuñado" does not
        // reach the ten-wide window at all, so what the golden rests on is
        // frequency and the merge floor, NOT the boost - the earlier comment
        // claiming a 1.29x boost had to be out-argued was describing a
        // candidate that is not in the contest. The boost half of the
        // acceptance set is carried by intendedCunadoStillDecodable below,
        // where the word is on its own path and its boost must survive.
        val p = assetPath("es_wordlist.txt")
        assumeTrue("es wordlist asset not found", Files.exists(p))
        val extra = DictionaryLoader.userWordsForMerge(listOf("cuñado" to 6))
        val dict = Files.newBufferedReader(p).use { DictionaryLoader.load(it, extra) }
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(
            dict.trie, BigramTable.EMPTY, g, dict.forms, mapOf("cuñado" to 6),
        )
        for (overshoot in listOf(0.3f, 0.4f, 0.5f)) {
            val result = predictor.decode(
                listOf(TestData.sloppySwipe("cuando", g, 0, 600, overshoot)), emptyList(),
            )
            assertTrue("no candidates at overshoot $overshoot", result.isNotEmpty())
            assertEquals(
                "cuando lost top-1 to ${result[0].word} at overshoot $overshoot",
                "cuando", result[0].word,
            )
        }
    }

    @Test
    fun intendedCunadoStillDecodable() {
        // Control for the poisoning golden: a c-u-n-a-d-o-ordered swipe (ñ
        // folds to n) must still surface "cuñado" near the top, unpoisoned.
        //
        // It carries a second job since the boost was fit-conditioned (which asks
        // in as many words for acceptance rows "where the boost is doing its
        // intended job"): with the SAME personal count the poisoning golden
        // feeds in, the word on its OWN path fits at d=0.243 - inside
        // GEO_SATURATION_KW - so the boost must survive in full. The pair is
        // the whole rule in two tests: reinforcement counts where the shape
        // agrees and nowhere else.
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        val result = predictor.decode(
            listOf(TestData.sloppySwipe("cunado", g, 0, 600, 0.4f)), emptyList(),
        )
        assertTrue(
            "cuñado missing from top-3 ${result.take(3).map { it.word }}",
            result.take(3).map { it.word }.contains("cuñado"),
        )

        val reinforced = WordPredictor(
            dict.trie, BigramTable.EMPTY, g, dict.forms, mapOf("cuñado" to 6),
        ).decode(listOf(TestData.sloppySwipe("cunado", g, 0, 600, 0.4f)), emptyList())
            .first { it.word == "cuñado" }
        assertTrue(
            "its own path must stay inside the cap: d=${reinforced.dtwDistance}",
            reinforced.dtwDistance < KineticaConstants.GEO_SATURATION_KW,
        )
        assertEquals(
            "a well-fitting reinforced word must keep the full boost",
            (1f + KineticaConstants.PERSONAL_BOOST * kotlin.math.ln(7f)).toDouble(),
            reinforced.personalBoost.toDouble(),
            1e-5,
        )
    }

    @Test
    fun siempreSurvivesSloppySwipes() {
        // Companion to the poisoning goldens: realistic siempre paths must
        // keep the word reachable. Top-1 is deliberately not asserted on the
        // clean path (documented dormir endpoint ambiguity above); sloppy
        // paths must at minimum surface it in the strip.
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        for (overshoot in listOf(0.3f, 0.4f, 0.5f)) {
            val result = predictor.decode(
                listOf(TestData.sloppySwipe("siempre", g, 0, 700, overshoot)), emptyList(),
            )
            assertTrue(
                "siempre missing at overshoot $overshoot: ${result.take(5).map { it.word }}",
                result.take(5).map { it.word }.contains("siempre"),
            )
        }
    }

    @Test
    fun accentRestoredThroughDecodeForms() {
        // The swipe travels the folded t-a-m-b-i-e-n path; the accented
        // display must surface from the forms table with its own frequency.
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        val result = predictor.decode(
            listOf(TestData.swipe("tambien", g, 0, 700)), emptyList(),
        )
        assertTrue(
            "'también' missing from ${result.map { it.word }}",
            result.map { it.word }.contains("también"),
        )
    }

    @Test
    fun accentRestoredThroughTapAutocorrect() {
        // "senal" reaches the "señal" node but is not one of its spellings
        // (the OpenSubtitles list has no unaccented twin), so isWord is false
        // and the exact-tap decode autocorrects the accent back in.
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        assertFalse(predictor.isWord("senal"))
        val tokens = "senal".mapIndexed { i, c -> TestData.tap(c, g, i * 100L) }
        val result = predictor.decode(tokens, emptyList())
        val target = predictor.autocorrectTarget(
            "senal", result, KineticaConstants.AUTOCORRECT_CONF_NORMAL,
        )
        assertNotNull("autocorrect did not fire on ${result.map { it.word }}", target)
        assertEquals("señal", target?.word)
    }

    @Test
    fun spanishDecodeLatencyIsBounded() {
        // Same 100 ms budget as the English latency goldens: a new dictionary
        // shape (49.5k words, different prefix fan-out) must not weaken the
        // pruning that keeps decode real-time.
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        val tokens = listOf(TestData.swipe("gracias", g, 0, 600))
        predictor.decode(tokens, emptyList()) // warmup
        val t0 = System.nanoTime()
        repeat(20) { predictor.decode(tokens, emptyList()) }
        val perDecodeMs = (System.nanoTime() - t0) / 20 / 1_000_000.0
        assertTrue("es decode took $perDecodeMs ms", perDecodeMs < 100.0)
    }
}
