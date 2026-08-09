package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.WordCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordPredictorTest {

    private val g = TestData.qwertyGeometry()
    private val predictor = WordPredictor(TestData.smallDictionary(), BigramTable.EMPTY, g)

    private fun words(candidates: List<WordCandidate>): List<String> =
        candidates.map { it.word }

    @Test
    fun singleSwipeDecodesWord() {
        val tokens = listOf(TestData.swipe("something", g, t0 = 0, durMs = 600))
        val result = predictor.decode(tokens, emptyList())
        assertTrue(result.isNotEmpty())
        assertEquals("something", result[0].word)
        assertEquals(WordCandidate.Source.SWIPE, result[0].source)
    }

    @Test
    fun swipeDisambiguatesByFrequency() {
        // "the" ideal path passes near "then"/"them" territory; the clean
        // t-h-e path must rank "the" (highest frequency, exact endpoints) first.
        val tokens = listOf(TestData.swipe("the", g, 0, 300))
        val result = predictor.decode(tokens, emptyList())
        assertEquals("the", result[0].word)
    }

    @Test
    fun allTapWordIsExactCandidate() {
        val tokens = listOf(
            TestData.tap('t', g, 0),
            TestData.tap('h', g, 100),
            TestData.tap('e', g, 200),
        )
        val result = predictor.decode(tokens, emptyList())
        assertEquals("the", result[0].word)
        assertEquals(WordCandidate.Source.EXACT_TAP, result[0].source)
        assertEquals(0f, result[0].dtwDistance, 1e-5f)
    }

    @Test
    fun mixedTapSwipeMergesSequentially() {
        // Single-finger sequence: tap S, swipe O->M, tap E, swipe T->H->I->N->G.
        // All same stream, non-overlapping: exercises the full merge machinery.
        val tokens = listOf(
            TestData.tap('s', g, 0, StreamId.LEFT),
            TestData.swipe("om", g, 200, 200, StreamId.LEFT),
            TestData.tap('e', g, 500, StreamId.LEFT),
            TestData.swipe("thing", g, 700, 500, StreamId.LEFT),
        )
        val result = predictor.decode(tokens, emptyList())
        assertTrue(result.isNotEmpty())
        assertEquals("something", result[0].word)
        assertEquals(WordCandidate.Source.MERGED, result[0].source)
    }

    @Test
    fun dualStreamSomethingTimeline() {
        // The spec walkthrough: left thumb taps S and E while the right thumb
        // swipes OM and THING, overlapping in time.
        val tokens = listOf(
            TestData.tap('s', g, 0, StreamId.LEFT),
            TestData.swipe("om", g, 60, 220, StreamId.RIGHT),
            TestData.tap('e', g, 310, StreamId.LEFT),
            TestData.swipe("thing", g, 360, 440, StreamId.RIGHT),
        )
        val result = predictor.decode(tokens, emptyList())
        assertTrue(result.isNotEmpty())
        assertEquals("something", result[0].word)
    }

    @Test
    fun orderSwapRescuesNearSimultaneousStarts() {
        // E tap starts 50ms AFTER the THING swipe: the primary tStart order is
        // wrong (s, om, thing, e) and only the swap alternative finds the word.
        val tokens = listOf(
            TestData.tap('s', g, 0, StreamId.LEFT),
            TestData.swipe("om", g, 60, 220, StreamId.RIGHT),
            TestData.swipe("thing", g, 360, 440, StreamId.RIGHT),
            TestData.tap('e', g, 410, StreamId.LEFT),
        )
        val result = predictor.decode(tokens, emptyList())
        assertTrue(words(result).contains("something"))
    }

    @Test
    fun doubleLetterViaPathDedup() {
        // Swiping h-e-l-o must find "hello": the doubled l adds no path length.
        val tokens = listOf(TestData.swipe("helo", g, 0, 400))
        val result = predictor.decode(tokens, emptyList())
        assertTrue(words(result).contains("hello"))
    }

    @Test
    fun apostropheWordReachableFromPlainSwipe() {
        val tokens = listOf(TestData.swipe("dont", g, 0, 400))
        val result = predictor.decode(tokens, emptyList())
        assertTrue(words(result).contains("don't"))
    }

    @Test
    fun fuzzyTapFallbackFindsAdjacentTypo() {
        // t-h-r: r is adjacent to e, "thr" is not a word -> fuzzy pass
        // substitutes e and surfaces "the".
        val tokens = listOf(
            TestData.tap('t', g, 0),
            TestData.tap('h', g, 100),
            TestData.tap('r', g, 200),
        )
        val result = predictor.decode(tokens, emptyList())
        assertTrue(words(result).contains("the"))
        val the = result.first { it.word == "the" }
        assertEquals(WordCandidate.Source.FUZZY_TAP, the.source)
        assertTrue(the.dtwDistance > 0f)
    }

    @Test
    fun transpositionFallbackFindsSwappedTaps() {
        // h-t-e -> "the" via adjacent transposition.
        val tokens = listOf(
            TestData.tap('h', g, 0),
            TestData.tap('t', g, 100),
            TestData.tap('e', g, 200),
        )
        val result = predictor.decode(tokens, emptyList())
        assertTrue(words(result).contains("the"))
    }

    @Test
    fun autocorrectFiresOnConfidentTypo() {
        val tokens = listOf(
            TestData.tap('t', g, 0),
            TestData.tap('h', g, 100),
            TestData.tap('r', g, 200),
        )
        val result = predictor.decode(tokens, emptyList())
        val target = predictor.autocorrectTarget(
            "thr", result, KineticaConstants.AUTOCORRECT_CONF_NORMAL,
        )
        assertNotNull(target)
        assertEquals("the", target?.word)
    }

    @Test
    fun autocorrectRespectsRealWords() {
        val tokens = listOf(
            TestData.tap('s', g, 0),
            TestData.tap('o', g, 100),
        )
        val result = predictor.decode(tokens, emptyList())
        // "so" is a word: never auto-replaced.
        assertNull(predictor.autocorrectTarget("so", result, 0.5f))
    }

    @Test
    fun bigramContextBoostsLikelyNextWord() {
        val trie = TestData.smallDictionary()
        val bigrams = BigramTable.build(
            listOf(
                Triple(trie.nodeFor("so"), trie.nodeFor("something"), 1000L),
            ),
        )
        val p = WordPredictor(trie, bigrams, g)
        val tokens = listOf(TestData.swipe("something", g, 0, 600))
        val boosted = p.decode(tokens, listOf("so"))
        val plain = p.decode(tokens, emptyList())
        val b = boosted.first { it.word == "something" }
        val n = plain.first { it.word == "something" }
        assertTrue(b.bigramMultiplier > 1f)
        assertEquals(1f, n.bigramMultiplier, 1e-5f)
        assertTrue(b.score > n.score)
    }

    @Test
    fun returnsAtMostTopK() {
        val tokens = listOf(TestData.swipe("the", g, 0, 300))
        val result = predictor.decode(tokens, emptyList())
        assertTrue(result.size <= KineticaConstants.TOP_K)
        // Scores must be non-increasing.
        for (i in 1 until result.size) {
            assertTrue(result[i - 1].score >= result[i].score)
        }
    }
}
