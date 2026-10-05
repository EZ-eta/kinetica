package com.kinetica.keyboard.engine

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Next-word predictions for the idle bar: what usually follows the last word, from the
 * bundled pairs and the user's learned ones, before any gesture.
 */
class NextWordTest {

    private val g = TestData.qwertyGeometry()

    private val trie = Trie.build(
        listOf("of" to 900, "the" to 1_000, "course" to 50, "a" to 800, "piece" to 40, "cake" to 30),
    )

    private fun table(vararg pairs: Pair<Pair<String, String>, Long>): BigramTable =
        BigramTable.build(pairs.map { (p, c) -> Triple(trie.nodeFor(p.first), trie.nodeFor(p.second), c) })

    private fun key(prev: String, next: String) = "$prev\u0000$next"

    @Test
    fun theStrongestBundledPairLeads() {
        val t = table(("of" to "the") to 500L, ("of" to "course") to 90L, ("of" to "cake") to 3L)
        assertEquals(
            listOf("the", "course", "cake"),
            WordPredictor(trie, t, g).nextWords("of", 5, personal = false).map { it.word },
        )
        assertEquals(listOf("the"), WordPredictor(trie, t, g).nextWords("of", 1, personal = false).map { it.word })
    }

    @Test
    fun aLearnedPairLiftsTheWordThatFollowed() {
        // The user writes "of cake"; the bundled table says "of the". Their own habit counts.
        val t = table(("of" to "the") to 500L, ("of" to "cake") to 400L)
        val plain = WordPredictor(trie, t, g).nextWords("of", 5, personal = true).map { it.word }
        val learned = WordPredictor(trie, t, g, personalBigrams = mapOf(key("of", "cake") to 30))
            .nextWords("of", 5, personal = true).map { it.word }
        assertEquals("the", plain.first())
        assertEquals("cake", learned.first())
    }

    @Test
    fun learnedPairsStayOutWhenThePrivacySwitchSaysSo() {
        val t = table(("of" to "the") to 500L)
        val p = WordPredictor(trie, t, g, personalBigrams = mapOf(key("of", "cake") to 30))
        assertEquals(listOf("the"), p.nextWords("of", 5, personal = false).map { it.word })
    }

    @Test
    fun aLearnedWordTheDictionaryNoLongerHoldsIsSkipped() {
        // A blocked word leaves no trie node, so it must not come back through a pair.
        val p = WordPredictor(trie, BigramTable.EMPTY, g, personalBigrams = mapOf(key("of", "gone") to 30))
        assertTrue(p.nextWords("of", 5, personal = true).isEmpty())
    }

    @Test
    fun anUnknownOrEmptyPreviousWordPredictsNothing() {
        val t = table(("of" to "the") to 500L)
        assertTrue(WordPredictor(trie, t, g).nextWords("zzz", 5, personal = true).isEmpty())
        assertTrue(WordPredictor(trie, t, g).nextWords("", 5, personal = true).isEmpty())
    }

    @Test
    fun theDisplaySpellingIsShownNotTheFoldedKey() {
        val words = "perché\t500\nnon\t400\n".reader().buffered().use { DictionaryLoader.load(it) }
        val t = BigramTable.build(listOf(Triple(words.trie.nodeFor("non"), words.trie.nodeFor("perche"), 50L)))
        val p = WordPredictor(words.trie, t, g, words.forms)
        assertEquals(listOf("perché"), p.nextWords("non", 3, personal = false).map { it.word })
    }

    @Test
    fun theBundledItalianTableGivesSensibleContinuations() {
        val w = listOf(Paths.get("src/main/assets/dictionaries/it_wordlist.txt"), Paths.get("app/src/main/assets/dictionaries/it_wordlist.txt"))
            .firstOrNull { Files.exists(it) }
        val b = listOf(Paths.get("src/main/assets/dictionaries/it_bigrams.txt"), Paths.get("app/src/main/assets/dictionaries/it_bigrams.txt"))
            .firstOrNull { Files.exists(it) }
        assumeTrue(w != null && b != null)
        val d = Files.newBufferedReader(w!!).use { DictionaryLoader.load(it) }
        val t = Files.newBufferedReader(b!!).use { DictionaryLoader.loadBigrams(it, d.trie) }
        val next = WordPredictor(d.trie, t, g, d.forms).nextWords("grazie", 5, personal = false).map { it.word }
        assertTrue("grazie -> $next", next.size == 5 && "mille" in next)
    }

    @Test
    fun aLearnedPairWithAccentsIsFound() {
        // The pair store was keyed unfolded while every lookup folds: `perché` never matched.
        assertEquals("perche\u0000citta", WordPredictor.pairKey("Perché", "Città"))
        val words = "perché\t500\ncittà\t400\ncasa\t300\n".reader().buffered().use { DictionaryLoader.load(it) }
        val p = WordPredictor(
            words.trie, BigramTable.EMPTY, g, words.forms,
            personalBigrams = mapOf(WordPredictor.pairKey("perché", "città") to 30),
        )
        assertEquals(listOf("città"), p.nextWords("perché", 3, personal = true).map { it.word })
    }
}
