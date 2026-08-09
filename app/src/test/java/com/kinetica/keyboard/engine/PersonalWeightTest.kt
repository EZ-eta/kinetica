package com.kinetica.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Adaptive personal weighting: final_score gains a
 * (1 + PERSONAL_BOOST * ln(1 + personalCount)) factor.
 */
class PersonalWeightTest {

    private val g = TestData.qwertyGeometry()

    // "you" dwarfs "thou" in corpus frequency; both decode from a y/t-adjacent
    // tap pattern only via their own exact taps, so compare via swipes on the
    // same node set instead: use two words with a shared tap pattern through
    // fuzzy anchors. Simpler and deterministic: exact taps for each word, then
    // compare candidate scores directly.
    private fun dict() = Trie.build(
        listOf("you" to 5_000_000, "thou" to 10_000, "the" to 6_000_000),
    )

    private fun scoreOf(p: WordPredictor, word: String): Float {
        val tokens = word.mapIndexed { i, c -> TestData.tap(c, g, i * 100L) }
        val result = p.decode(tokens, emptyList())
        return result.first { it.word == word }.score
    }

    @Test
    fun unreinforcedRankingFollowsCorpusFrequency() {
        val p = WordPredictor(dict(), BigramTable.EMPTY, g)
        val you = scoreOf(p, "you")
        val thou = scoreOf(p, "thou")
        // Same geometry quality (exact taps), higher corpus frequency wins.
        org.junit.Assert.assertTrue("you=$you thou=$thou", you > thou)
    }

    @Test
    fun twentyCommitsFlipTheRanking() {
        val counts = mapOf("thou" to 20)
        val p = WordPredictor(dict(), BigramTable.EMPTY, g, emptyMap(), counts)
        val you = scoreOf(p, "you")
        val thou = scoreOf(p, "thou")
        org.junit.Assert.assertTrue(
            "personal boost must flip ranking: you=$you thou=$thou",
            thou > you,
        )
    }

    @Test
    fun personalCountReadsThroughPredictor() {
        val p = WordPredictor(dict(), BigramTable.EMPTY, g, emptyMap(), mapOf("thou" to 7))
        assertEquals(7, p.personalCount("Thou"))
        assertEquals(0, p.personalCount("you"))
    }

    @Test
    fun badgeTiersDoublePerLevel() {
        assertEquals(0, KineticaConstants.personalTier(0))
        assertEquals(1, KineticaConstants.personalTier(1))
        assertEquals(2, KineticaConstants.personalTier(2))
        assertEquals(2, KineticaConstants.personalTier(3))
        assertEquals(3, KineticaConstants.personalTier(4))
        assertEquals(4, KineticaConstants.personalTier(8))
        assertEquals(5, KineticaConstants.personalTier(16))
        assertEquals(6, KineticaConstants.personalTier(32))
        assertEquals(7, KineticaConstants.personalTier(64))
        // Tier 7 is the ceiling no matter how heavy the reinforcement.
        assertEquals(7, KineticaConstants.personalTier(100_000))
    }

    @Test
    fun tierNeverGoesBelowZeroOnDowngrade() {
        // Slide-to-de-reinforce applies negative deltas; every
        // writer clamps counts at zero (IME map, DAO MAX(0,...)), and the tier
        // function itself must treat any non-positive residue as badge-less.
        assertEquals(0, KineticaConstants.personalTier(-1))
        assertEquals(0, KineticaConstants.personalTier(-100))
    }

    @Test
    fun reinforcedCompletionClimbs() {
        // Personal boost applies to COMPLETION candidates through the
        // same emit path as every other source. "then" (5000) edges out
        // "they" (4500) on corpus frequency; 20 commits of "they" must flip
        // the completion ranking for the t,h prefix.
        val g = TestData.qwertyGeometry()
        val tokens = listOf(TestData.tap('t', g, 0), TestData.tap('h', g, 100))
        val virgin = WordPredictor(TestData.smallDictionary(), BigramTable.EMPTY, g)
            .decode(tokens, emptyList()).map { it.word }
        val reinforced = WordPredictor(
            TestData.smallDictionary(), BigramTable.EMPTY, g,
            emptyMap(), mapOf("they" to 20),
        ).decode(tokens, emptyList()).map { it.word }
        org.junit.Assert.assertTrue(
            "corpus order expected: $virgin",
            virgin.indexOf("then") < virgin.indexOf("they"),
        )
        org.junit.Assert.assertTrue(
            "reinforced 'they' must climb past 'then': $reinforced",
            reinforced.indexOf("they") < reinforced.indexOf("then"),
        )
    }

    @Test
    fun downgradedToZeroScoresAsNeverCommitted() {
        // A word fully de-reinforced back to a clamped count of 0 must rank
        // exactly like a word with no personal history at all.
        val virgin = WordPredictor(dict(), BigramTable.EMPTY, g)
        val downgraded = WordPredictor(dict(), BigramTable.EMPTY, g, emptyMap(), mapOf("thou" to 0))
        assertEquals(scoreOf(virgin, "thou"), scoreOf(downgraded, "thou"), 1e-6f)
        org.junit.Assert.assertTrue(scoreOf(downgraded, "you") > scoreOf(downgraded, "thou"))
    }
}
