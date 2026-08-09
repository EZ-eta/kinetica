package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Regression suite for resume-after-interruption: a stream pauses mid-word while
 * the other stream acts, then resumes. Two failure modes, one family:
 *
 *  - Interrupted by a TAP ("interessante" -> "Inn"): the tap-split interleave
 *    IS generated but the resumed second half started at the rest position, so
 *    its first letter failed the matcher's start-letter gate and the word was
 *    pruned. Fixed by the distance-peak head trim (resume at the first real
 *    letter the finger turns away from) plus the soft-first-letter fallback
 *    for split halves.
 *  - Interrupted by a SWIPE ("sempre" -> "saremo"): a swipe interrupted by the
 *    other thumb's swipe was structurally ungenerable (the split generator was
 *    tap-only). Fixed by the swipe-around-swipe generator in MergeAlternatives.
 *
 * Timelines are built from [TestData.dwellSwipe] (a single swipe with a mid-path
 * rest) crossed with a tap or swipe on the other stream during the rest, run at
 * a clean and a sloppy overshoot so the fixtures are not perfect-center.
 */
class ResumeAfterInterruptionTest {

    private val g = TestData.qwertyGeometry()

    /** Hand-weighted dictionary: the reported words, the losing rival, and
     *  distractors so ranking is a real contest rather than a forced choice. */
    private fun dict(): Trie = Trie.build(
        listOf(
            "interessante" to 5000,
            "sempre" to 8000,
            "saremo" to 3000, // the wrong word this bug used to pick
            "wanted" to 6000,
            "hunter" to 4000,
            "winter" to 7000,
            // distractors
            "water" to 4500, "winner" to 3500, "hunted" to 3000,
            "wander" to 2500, "sender" to 2000, "waned" to 800,
            "in" to 9000, "inn" to 1500,
            "the" to 12000, "and" to 15000, "a" to 20000,
        ),
    )

    private fun decode(tokens: List<InputToken>): List<String> =
        WordPredictor(dict(), BigramTable.EMPTY, g).decode(tokens, emptyList()).map { it.word }

    // ---- token timelines (one per reported/analogue word) --------------------

    /** Right I->N swipe; left T-E-R-E-S-A swipe resting on A; right N tap during
     *  the rest; left resumes A->T->E. Merged: [in][teressa][n][te]. */
    private fun interessante(overshoot: Float) = listOf(
        TestData.swipe("in", g, t0 = 0, durMs = 150, stream = StreamId.RIGHT),
        TestData.dwellSwipe("teresa", "te", g, 0, 300, 400, 200, overshoot, StreamId.LEFT),
        TestData.tap('n', g, 500, StreamId.RIGHT),
    )

    /** Left S->E swipe holding on E; right M->P swipe during the hold; left
     *  resumes E->R->E. Merged: [se][mp][re]. */
    private fun sempre(overshoot: Float) = listOf(
        TestData.dwellSwipe("se", "re", g, 0, 200, 400, 200, overshoot, StreamId.LEFT),
        TestData.swipe("mp", g, t0 = 300, durMs = 200, stream = StreamId.RIGHT),
    )

    /** Left W-A-N swipe resting on N; right T tap; left resumes E->D. */
    private fun wanted(overshoot: Float) = listOf(
        TestData.dwellSwipe("wan", "ed", g, 0, 300, 400, 200, overshoot, StreamId.LEFT),
        TestData.tap('t', g, 450, StreamId.RIGHT),
    )

    /** Left H-U-N swipe resting on N; right T tap; left resumes E->R. */
    private fun hunter(overshoot: Float) = listOf(
        TestData.dwellSwipe("hun", "er", g, 0, 300, 400, 200, overshoot, StreamId.LEFT),
        TestData.tap('t', g, 450, StreamId.RIGHT),
    )

    /** Left W-I swipe holding on I; right N->T swipe during the hold; left
     *  resumes E->R. Merged: [wi][nt][er]. */
    private fun winter(overshoot: Float) = listOf(
        TestData.dwellSwipe("wi", "er", g, 0, 200, 400, 200, overshoot, StreamId.LEFT),
        TestData.swipe("nt", g, t0 = 300, durMs = 200, stream = StreamId.RIGHT),
    )

    // ---- tests ---------------------------------------------------------------

    @Test
    fun interessanteResumeDecodesTop1() {
        for (overshoot in listOf(0f, 0.25f)) {
            val words = decode(interessante(overshoot))
            assertTrue(
                "interessante (overshoot $overshoot) top-1 was ${words.take(3)}",
                words.firstOrNull() == "interessante",
            )
        }
    }

    @Test
    fun sempreResumeBeatsSaremo() {
        for (overshoot in listOf(0f, 0.25f)) {
            val words = decode(sempre(overshoot))
            val si = words.indexOf("sempre")
            val ri = words.indexOf("saremo")
            assertTrue("sempre missing (overshoot $overshoot) in $words", si >= 0)
            assertTrue("sempre must be top-1 (overshoot $overshoot) in $words", si == 0)
            assertTrue("sempre must rank above saremo (overshoot $overshoot) in $words", ri < 0 || si < ri)
        }
    }

    @Test
    fun resumeSplitGeneralizesBeyondReportedWords() {
        // Three analogue words - two tap-interrupted, one swipe-interrupted -
        // to prove the fix is the mechanism, not a patch for two examples.
        val cases = listOf(
            "wanted" to ::wanted,
            "hunter" to ::hunter,
            "winter" to ::winter,
        )
        for ((expected, build) in cases) {
            for (overshoot in listOf(0f, 0.25f)) {
                val words = decode(build(overshoot))
                assertTrue(
                    "$expected (overshoot $overshoot) top-1 was ${words.take(3)}",
                    words.firstOrNull() == expected,
                )
            }
        }
    }

    @Test
    fun realItalianDictionaryResumeDecode() {
        val trie = loadItalian() ?: return
        val predictor = WordPredictor(trie, BigramTable.EMPTY, g)

        val inter = predictor.decode(interessante(0.25f), emptyList()).map { it.word }
        assertTrue("interessante missing from real-dict decode ${inter.take(5)}", inter.contains("interessante"))
        assertTrue("interessante not top-3 in $inter", inter.take(3).contains("interessante"))

        val semp = predictor.decode(sempre(0.25f), emptyList()).map { it.word }
        val si = semp.indexOf("sempre")
        val ri = semp.indexOf("saremo")
        assertTrue("sempre missing from real-dict decode $semp", si >= 0)
        assertTrue("sempre must rank above saremo in $semp", ri < 0 || si < ri)
    }

    private fun loadItalian(): Trie? {
        val direct = Paths.get("src/main/assets/dictionaries/it_wordlist.txt")
        val nested: Path = Paths.get("app/src/main/assets/dictionaries/it_wordlist.txt")
        val p = when {
            Files.exists(direct) -> direct
            Files.exists(nested) -> nested
            else -> null
        }
        assumeTrue("it_wordlist asset not found", p != null)
        return Files.newBufferedReader(p!!).use { DictionaryLoader.loadWordlist(it) }
    }
}
