package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The personal pair boost: what a word earns for having followed this one before.
 *
 * The store is for the transitions the bundled tables do not hold: on one capture only 35% of
 * prose pairs were in the shipped assets, which cover 7 600 distinct preceding words in
 * English. So the fixtures are pairs no corpus would carry.
 *
 * The commit side has no JVM reach, so this pins the arithmetic and the two invariants the
 * boost must not break.
 */
class PersonalBigramTest {

    private val g = TestData.qwertyGeometry()

    private fun predictor(pairs: Map<String, Int>): WordPredictor =
        WordPredictor(
            TestData.smallDictionary(), BigramTable.EMPTY, g,
            personalBigrams = pairs,
        )

    /** The production key shape, spelled out: a raw separator byte in source is unreadable. */
    private fun key(prev: String, next: String) = "$prev\u0000$next"

    private fun taps(word: String): List<InputToken> =
        word.mapIndexed { i, c -> TestData.tap(c, g, 100L * i) }

    @Test
    fun aPairBoostedWordIsScoredBeforeTheHeapCanAbandonIt() {
        // The DTW abandon bound is the best score a node can still reach, so it must carry
        // every boost that can raise it. Without the pair boost, `cab` after `taxi`, rare on
        // its own, was abandoned against a full heap of common `ca` completions it outscores
        // once the pair counts.
        val trie = Trie.build(
            listOf(
                "can" to 10_000, "car" to 9_000, "cat" to 8_000, "cap" to 7_000, "cane" to 6_000,
                "card" to 5_000, "care" to 4_000, "case" to 3_000, "cast" to 2_500,
                "cash" to 2_000, "cab" to 2,
            ),
        )
        fun decode(pairs: Map<String, Int>) =
            WordPredictor(trie, BigramTable.EMPTY, g, personalBigrams = pairs)
                .decode(taps("ca"), listOf("taxi")).map { it.word }
        assertTrue("without the pair, `cab` stays out of a full list", "cab" !in decode(emptyMap()))
        assertTrue("with the pair it must be scored and kept", "cab" in decode(mapOf(key("taxi", "cab") to 30)))
    }

    @Test
    fun aPairNeverSeenChangesNothing() {
        val plain = predictor(emptyMap()).decode(taps("the"), listOf("of"))
        val withStore = predictor(mapOf(key("zzz", "qqq") to 9)).decode(taps("the"), listOf("of"))
        assertEquals(
            "an unrelated pair must not move a single score",
            plain.map { it.word to it.score },
            withStore.map { it.word to it.score },
        )
    }

    @Test
    fun anEmptyContextChangesNothing() {
        // The field just opened, or the composer was reset: there is no predecessor, so the
        // store has nothing to say and must say nothing.
        val pairs = mapOf(key("of", "the") to 20)
        assertEquals(
            predictor(emptyMap()).decode(taps("the"), emptyList()).map { it.word to it.score },
            predictor(pairs).decode(taps("the"), emptyList()).map { it.word to it.score },
        )
    }

    @Test
    fun aLearnedPairRaisesTheWordThatFollowed() {
        val word = "the"
        val plain = predictor(emptyMap()).decode(taps(word), listOf("of"))
            .first { it.word == word }
        val boosted = predictor(mapOf(key("of", word) to 20)).decode(taps(word), listOf("of"))
            .first { it.word == word }
        assertTrue(
            "a pair seen 20 times must raise its own word: ${plain.score} -> ${boosted.score}",
            boosted.score > plain.score,
        )
        assertTrue("the applied boost must be recorded", boosted.personalBigram > 1f)
        assertEquals("and not on the unboosted run", 1f, plain.personalBigram, 1e-6f)
    }

    @Test
    fun theBoostGrowsWithTheCountAndSaturatesSlowly() {
        // Same log shape as the single-word boost, for the same reason: the twentieth time
        // the user writes a phrase is worth less than the second.
        val one = KineticaConstants.PERSONAL_BIGRAM_BOOST * kotlin.math.ln(2f)
        val twenty = KineticaConstants.PERSONAL_BIGRAM_BOOST * kotlin.math.ln(21f)
        assertTrue("more evidence must weigh more", twenty > one)
        assertTrue("but not proportionally: log, not linear", twenty < 5f * one)
    }

    @Test
    fun theContextWordIsMatchedFoldedAndLowercased() {
        // decode folds and lowercases the predecessor before anything looks it up, so a
        // sentence-initial or accented predecessor still finds its pair.
        val pairs = mapOf(key("of", "the") to 20)
        val plain = predictor(emptyMap()).decode(taps("the"), listOf("Of"))
            .first { it.word == "the" }
        val boosted = predictor(pairs).decode(taps("the"), listOf("Of"))
            .first { it.word == "the" }
        assertTrue("a capitalised predecessor must still match", boosted.score > plain.score)
    }

    @Test
    fun aPairIsNotAllowedToRescueAHopelessFit() {
        // The invariant every boost in this engine shares: past one key width the gesture is
        // somewhere else, and no context is evidence about it. appliedBoost enforces it; this
        // pins that the pair boost goes through it like the others.
        for (raw in listOf(1.1f, 1.5f, 2.0f)) {
            assertEquals(
                "a pair boost must be gone by one key hop",
                1.0,
                KineticaConstants.appliedBoost(raw, 2f * KineticaConstants.GEO_SATURATION_KW).toDouble(),
                1e-6,
            )
        }
    }

    // ------------------------------------------------- the two-sighting floor
    //
    // A commit is not proof the decode was right: "i don't oboe know" was typed with the
    // wrong word left in, so `don't -> oboe` was learned from one commit. The retype button
    // takes a pair back out only for an error the user retypes.

    @Test
    fun aPairSeenOnceDoesNotBoost() {
        val plain = predictor(emptyMap()).decode(taps("the"), listOf("of"))
        val once = predictor(mapOf(key("of", "the") to 1)).decode(taps("the"), listOf("of"))
        assertEquals(
            "one commit is one accident, and must move no score",
            plain.map { it.word to it.score },
            once.map { it.word to it.score },
        )
    }

    @Test
    fun aPairSeenTwiceBoosts() {
        val plain = predictor(emptyMap()).decode(taps("the"), listOf("of"))
        val twice = predictor(mapOf(key("of", "the") to 2)).decode(taps("the"), listOf("of"))
        val before = plain.first { it.word == "the" }.score
        val after = twice.first { it.word == "the" }.score
        assertTrue("the floor is a floor, not a ban: $before -> $after", after > before)
    }

    @Test
    fun theFloorMatchesTheOneSingleWordsUse() {
        // Same evidence, same shape, same reason: if one moves, both should be looked at.
        assertEquals(
            KineticaConstants.PERSONAL_MERGE_MIN_COUNT,
            KineticaConstants.PERSONAL_PAIR_MIN_COUNT,
        )
    }
}
