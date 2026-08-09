package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.WordCandidate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * `personalBoost` used to be an unbounded multiplier applied whatever the
 * candidate's shape, so a word committed dozens of times could take a gesture
 * it plainly did not fit.
 * It now applies only while the candidate's GEOMETRIC fit is inside
 * `GEO_SATURATION_KW` - see PERSONAL_BOOST's rationale for the measurement.
 *
 * Three device instances drove it and only two are reachable; the third is
 * pinned below as impossible so nobody re-tunes for it. Both reachable ones are
 * end-to-end here rather than pinned as trace tuples, because unusually for
 * this project the polyline-through-contacts reconstruction DOES carry them:
 * the deciding distances are 0.32 against 1.17 and 0.38 against 0.97, gaps wide
 * enough to survive the measured reconstruction compression. The rows that
 * must NOT move are asserted here too, with a personal count present, since a
 * bound that quietly disabled the feature would otherwise pass everything.
 */
class PersonalBoostBoundTest {

    private val g = TestData.qwertyGeometry()
    private val direct = Executor { it.run() }

    private object NoCallbacks : WordComposer.Callbacks {
        override fun onCandidates(
            candidates: List<WordCandidate>,
            tentative: WordCandidate?,
            literal: String,
            generation: Int,
        ) = Unit
    }

    private fun predictor(
        lang: Pair<LoadedDictionary, BigramTable>,
        counts: Map<String, Int> = emptyMap(),
        code: String = "",
    ): WordPredictor {
        val (dict, bigrams) = lang
        return WordPredictor(dict.trie, bigrams, g, dict.forms, counts, code)
    }

    private fun swipe(keys: String, stream: StreamId = StreamId.RIGHT): List<InputToken> =
        listOf(TestData.swipe(keys, g, 0, 100L * keys.length, stream))

    // ---- the two reachable device instances --------------------------------

    @Test
    fun mujerSurvivesAPersonallyBoostedMe() {
        // A device row. Spanish "mujer" fits at d=0.384, Italian "me" at 0.969 - a 2.5x
        // geometric edge to the intended word - but "me" carries pb=1.614 from
        // ~59 commits and 0.89*0.219*1.614 beat 0.81*0.296, so the merge saw an
        // Italian head and returned native-lead. The merged ranking could not fix it:
        // the merge rules decide WHICH candidate may lead, never the magnitude
        // of a score.
        val active = predictor(IT, mapOf("me" to 59), "it")
        val other = predictor(ES, emptyMap(), "es")
        val tokens = swipe(MUJER_CONTACTS)
        val ctx = listOf("cuando", "nosotros")
        val merged = WordComposer(active, direct, direct, NoCallbacks)
            .merge(active.decode(tokens, ctx), other.decode(tokens, ctx))
        assertEquals(
            "top-1 was ${merged.candidates.take(3).map { it.word }} (${merged.reason})",
            "mujer",
            merged.tentative?.word,
        )
        assertEquals("es", merged.tentative?.language)
    }

    @Test
    fun theKeyboardGesturePutsKeyboardInsideTheCap() {
        // A third device instance. The RANKING half is
        // pinned on the trace's own numbers in ScoreWeightingTest's DEVICE_ROWS,
        // because the polyline reconstruction does not carry that contest at
        // all: rebuilt, "keyboard" already leads and "leonard" is not even in
        // the window (d 1.64 for "lewis" gives the scale). Treat
        // reconstructions as reachability fixtures, not ranking fixtures - that
        // bias is measured elsewhere and holds here too.
        //
        // What the reconstruction CAN establish is the premise the device row
        // turns on, and it is the one that would silently rot if the cap moved:
        // on this gesture "keyboard" is inside GEO_SATURATION_KW and therefore
        // keeps its boost, while "leonard" at d=1.17 on device is far outside
        // and loses its own.
        val p = predictor(EN, mapOf("leonard" to 3, "keyboard" to 1))
        val hit = p.decode(swipe(KEYBOARD_CONTACTS), listOf("keyboard", "leonard"))
            .first { it.word == "keyboard" }
        assertTrue(
            "keyboard must stay inside the cap on its own gesture: d=${hit.dtwDistance}",
            hit.dtwDistance < KineticaConstants.GEO_SATURATION_KW,
        )
        assertTrue("and must therefore keep its boost: pb=${hit.personalBoost}", hit.personalBoost > 1f)
        assertEquals(
            "leonard's device fit is past the cap, so its boost is dropped",
            1f,
            KineticaConstants.appliedBoost(1.215f, 1.172f),
            1e-6f,
        )
    }

    // ---- the mechanism, stated directly ------------------------------------

    @Test
    fun theBoostFadesExactlyAsTheFitStopsCarryingInformation() {
        // Same word, same commit count, two paths: one the word's own, one a
        // different word's. Inside the cap the boost lifts it in full; a whole
        // key out it is gone. This is the rule with no dictionary or device row
        // in the way, so it fails loudly if the weight is ever moved to dTotal
        // or given a constant of its own.
        val counts = mapOf("vedere" to 40)
        val p = predictor(IT, counts)
        val own = p.decode(swipe("vedere", StreamId.LEFT), emptyList())
            .first { it.word == "vedere" }
        assertTrue("its own path must stay inside the cap: d=${own.dtwDistance}", own.dtwDistance < KineticaConstants.GEO_SATURATION_KW)

        val boosted = own.score
        val unboosted = predictor(IT).decode(swipe("vedere", StreamId.LEFT), emptyList())
            .first { it.word == "vedere" }.score
        assertTrue("a good fit must keep the full boost: $boosted vs $unboosted", boosted > unboosted * 1.4f)

        // A path this word does not explain. Whatever distance it lands at, the
        // 40 commits buy it strictly less than on its own path, and nothing at
        // all once it is a whole key out.
        val far = p.decode(swipe("parlare", StreamId.LEFT), emptyList())
            .firstOrNull { it.word == "vedere" && it.dtwDistance >= KineticaConstants.GEO_SATURATION_KW }
        if (far != null) {
            val farUnboosted = predictor(IT).decode(swipe("parlare", StreamId.LEFT), emptyList())
                .first { it.word == "vedere" }.score
            assertTrue(
                "a fit past the cap must keep less than its full boost: ${far.score} vs $farUnboosted",
                far.score < farUnboosted * own.personalBoost,
            )
            if (far.dtwDistance >= 2f * KineticaConstants.GEO_SATURATION_KW) {
                assertEquals(
                    "a fit a whole key out must score as if never committed",
                    farUnboosted, far.score, 1e-6f,
                )
            }
        }
    }

    @Test
    fun aBoostSurvivesTheCapAndDiesOneKeyLater() {
        // The step edge, stated on the numbers that
        // produced it. "sempre" carries pb=1.54 and was observed BOTH boosted
        // (d=0.37, 0.47) and stripped (d=0.50, 0.60) in a single capture, so the
        // gate's discontinuity - not any factor - decided four rows. What the
        // fade guarantees is that 0.03 kw of fit can no longer invert a 1.22x
        // boost advantage.
        val cap = KineticaConstants.GEO_SATURATION_KW
        val raw = 1.54f
        val justInside = KineticaConstants.appliedBoost(raw, cap - 0.01f)
        val justOutside = KineticaConstants.appliedBoost(raw, cap + 0.01f)
        assertEquals("inside the cap the boost is whole", raw, justInside, 1e-6f)
        assertTrue(
            "crossing the cap must cost a few percent, not the whole boost: $justOutside",
            justOutside > raw - 0.05f,
        )
        // The device rows themselves: at d=0.50 "sempre" (fw 0.85) must beat
        // "stremo" (fw 0.65, pb 1.26 and inside the cap), and again at 0.60.
        for (d in listOf(0.50f, 0.60f)) {
            val sempre = 0.85f * KineticaConstants.geometricTerm(d) *
                KineticaConstants.appliedBoost(raw, d)
            val stremo = 0.65f * KineticaConstants.geometricTerm(0.47f) *
                KineticaConstants.appliedBoost(1.26f, 0.47f)
            assertTrue("sempre at d=$d lost to stremo: $sempre vs $stremo", sempre > stremo)
        }
        // ...and a whole key out the same boost buys nothing, which is what lets
        // an all-saturated row fall through to fw.
        assertEquals("a boost a key out must be gone", 1f, KineticaConstants.appliedBoost(raw, 2f * cap), 1e-6f)
    }

    @Test
    fun comeStillBeatsComputerUnderEveryBoundedBoost() {
        // A device row, and the reason two of the three instances are fixed
        // rather than three. Past GEO_SATURATION_KW both candidates clamp to the
        // same geometric term, so the contest reduces to fw: come 0.91 against
        // computer 0.71 at d=0.988 and 0.710. "come" wins by 1.28x even with NO
        // boost on either word, which means no bound on the personal term can
        // reach this row - just as no function of d can. Both levers are closed
        // on it; measured and rejected, do not re-derive.
        val geo = KineticaConstants.geometricTerm(KineticaConstants.GEO_SATURATION_KW)
        val come = 0.91f * KineticaConstants.geometricTerm(0.988f)
        val computer = 0.71f * KineticaConstants.geometricTerm(0.710f)
        assertEquals("both must be saturated for the proof to hold", geo.toDouble(), KineticaConstants.geometricTerm(0.710f).toDouble(), 1e-6)
        assertTrue("come must win unboosted: $come vs $computer", come > computer)
    }

    // ---- what must not move ------------------------------------------------

    @Test
    fun aWellFittingPersonalWordKeepsItsFullBoost() {
        // The bound must not become a general weakening of the feature. These
        // are the device rows where the boost is doing its job: a flagship word
        // with a large count and a fit inside the cap. Measured across the eight
        // captures, all 54 such rows keep their top-1, and
        // these three are the resume family's own.
        for ((word, count) in listOf("quindi" to 45, "sempre" to 40, "interessante" to 35)) {
            val p = predictor(IT, mapOf(word to count))
            val c = p.decode(swipe(word, StreamId.LEFT), emptyList())
            val hit = c.first { it.word == word }
            assertTrue("$word left the cap on its own path: d=${hit.dtwDistance}", hit.dtwDistance < KineticaConstants.GEO_SATURATION_KW)
            assertEquals("$word lost its own path", word, c.first().word)
            val plain = predictor(IT).decode(swipe(word, StreamId.LEFT), emptyList())
                .first { it.word == word }.score
            assertTrue("$word must still be boosted: ${hit.score} vs $plain", hit.score > plain * 1.3f)
        }
    }

    @Test
    fun tapAndCompletionCandidatesAreNeverAttenuated() {
        // The condition reads the geometric mean, not dTotal, so a completion's
        // per-letter penalty and a fuzzy anchor's substitution charge cannot
        // switch the boost off. "they" from the t,h prefix sits at dTotal
        // exactly 0.50 - GEO_SATURATION_KW - so a dTotal-keyed rule would decide
        // PersonalWeightTest.reinforcedCompletionClimbs on a float comparison.
        val tokens = listOf(TestData.tap('t', g, 0), TestData.tap('h', g, 100))
        val c = WordPredictor(TestData.smallDictionary(), BigramTable.EMPTY, g, emptyMap(), mapOf("they" to 20))
            .decode(tokens, emptyList())
        val they = c.first { it.word == "they" }
        assertEquals(
            "the fixture must still sit on the boundary this test exists for",
            KineticaConstants.GEO_SATURATION_KW.toDouble(),
            they.dtwDistance.toDouble(),
            1e-6,
        )
        val plain = WordPredictor(TestData.smallDictionary(), BigramTable.EMPTY, g)
            .decode(tokens, emptyList()).first { it.word == "they" }.score
        assertTrue("a completion must keep its boost: ${they.score} vs $plain", they.score > plain * 1.4f)
    }

    private companion object {
        /** Contact letters of the device swipes, verbatim from the traces. */
        const val MUJER_CONTACTS = "mkjuhgfrer"
        const val KEYBOARD_CONTACTS = "kjhgfrertyhbjkioiuytfdsaserfd"

        private fun assetPath(name: String): Path {
            val direct = Paths.get("src/main/assets/dictionaries/$name")
            if (Files.exists(direct)) return direct
            return Paths.get("app/src/main/assets/dictionaries/$name")
        }

        private fun load(lang: String): Pair<LoadedDictionary, BigramTable> {
            val w = assetPath("${lang}_wordlist.txt")
            val b = assetPath("${lang}_bigrams.txt")
            assumeTrue("$lang assets not found", Files.exists(w) && Files.exists(b))
            val dict = Files.newBufferedReader(w).use { DictionaryLoader.load(it) }
            val bigrams = Files.newBufferedReader(b).use { DictionaryLoader.loadBigrams(it, dict.trie) }
            return dict to bigrams
        }

        val IT by lazy { load("it") }
        val ES by lazy { load("es") }
        val EN by lazy { load("en") }
    }
}
