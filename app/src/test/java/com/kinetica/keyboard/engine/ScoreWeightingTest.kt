package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.WordCandidate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A frequent, context-boosted or personally reinforced word with mediocre
 * shape used to outrank a near-perfect geometric
 * fit, because `1/(1+d)` varies only 1.0 -> 0.4 across the useful distance
 * range while `fw * bm * pb` spans ~3x.
 *
 * Every case here is a real device contest from the DecodeTrace captures,
 * rebuilt from the `keys=` contact letters as a polyline through those key
 * centres - the reconstruction LanguageDetectGoldenTest introduced. Two things
 * make these fixtures faithful that a plain swipe fixture is not:
 *
 *  - the captured `ctx=` is replayed, so the bigram multiplier that decided the
 *    contest is present (the quindi->state boost, she->the, he->her);
 *  - the personal commit counts are SYNTHESISED from the boost the trace
 *    implies (count = exp((pb-1)/PERSONAL_BOOST) - 1, where
 *    pb = s*(1+d)/(fw*bm)). Three of these rows were decided by personal
 *    reinforcement the JVM has no other way to see - "come" at pb 1.59 and
 *    "sei" at 1.49 beat their rivals on that alone.
 *
 * The counts are approximate by construction (the trace prints 2 decimals, and
 * pb is exponential in the count), so they are chosen to reproduce the observed
 * ORDER, which they do; they are not claims about any real dictionary.
 */
class ScoreWeightingTest {

    private val g = TestData.qwertyGeometry()

    private fun decode(
        lang: Pair<LoadedDictionary, BigramTable>,
        tokens: List<InputToken>,
        ctx: List<String>,
        counts: Map<String, Int> = emptyMap(),
    ): List<WordCandidate> {
        val (dict, bigrams) = lang
        return WordPredictor(dict.trie, bigrams, g, dict.forms, counts).decode(tokens, ctx)
    }

    private fun words(c: List<WordCandidate>) = c.map { it.word }

    private fun swipe(keys: String, stream: StreamId = StreamId.LEFT) =
        listOf<InputToken>(TestData.swipe(keys, g, 0, 100L * keys.length, stream))

    /** Asserts [want] is top-1, naming the rival it had to beat. */
    private fun assertBeats(c: List<WordCandidate>, want: String, rival: String, label: String) {
        val w = words(c)
        assertTrue("$label: '$rival' missing, so this is no longer a contest: $w", w.contains(rival))
        assertEquals("$label: top-1 was ${w.take(3)}", want, w.firstOrNull())
    }

    // ---- the six device contests ------------------------------------------

    /**
     * The device contests, pinned as the (d, fw, bm, s) tuples the traces
     * printed and re-scored through the shipped formula.
     *
     * Five of the six CANNOT be carried by the polyline reconstruction used
     * elsewhere in this class, and that is a measured property of the
     * reconstruction, not a gap in the fix: a path through exact key centres is
     * cleaner than the gesture it stands for and compresses the very distance
     * gap under test. "the"/"there" is the plainest example - 0.56 vs 0.22 on
     * the device, 0.39 vs 0.28 rebuilt, so the rebuilt contest is barely half as
     * decisive and the fix moves "there" only from rank 2 to rank 2. ("cede"
     * does climb, 6th to 3rd.) Only siete/due survives rebuilding intact, and it
     * has its own end-to-end test below.
     *
     * So the contests are locked here on their real numbers, dictionary-free,
     * exactly as LanguagePreferenceTest pins the langdetect tuples - with the
     * end-to-end tests carrying what reconstruction can honestly carry.
     */
    @Test
    fun deviceContestsRankCorrectlyUnderTheSaturatingTerm() {
        for ((label, row) in DEVICE_ROWS) {
            val (want, cands) = row
            val ranked = cands.entries.sortedByDescending { shippedScore(it.value) }.map { it.key }
            assertEquals("$label: top-1 was ${ranked.take(3)}", want, ranked.first())
        }
    }

    /**
     * The RAW personal boost behind a captured row: the trace's own `s`,
     * inverted through whichever formula shipped when the capture was taken
     * (`pb` became a trace field only later, and even then it prints the
     * APPLIED value - see [Row.rawPb] for the rows where that is not enough).
     */
    private fun rawPb(r: Row): Float = r.rawPb ?: if (r.sat) {
        r.s / (r.fw * r.bm * KineticaConstants.geometricTerm(r.d))
    } else {
        r.s * (1f + r.d) / (r.fw * r.bm)
    }

    /**
     * The shipped score, re-derived from a captured row: recover the raw boosts,
     * then re-score through the current formula - saturation and the fit-weighted
     * boosts included.
     *
     * The fit weight uses `d` directly: every row here is a single swipe, so
     * dTotal carries no tap penalty and therefore IS the geometric mean.
     */
    private fun shippedScore(r: Row): Float =
        // `r.bm` is the TABLE value every capture printed - all ten predate the
        // fit condition - so it inverts out of `s` raw and is re-applied through it.
        r.fw * KineticaConstants.geometricTerm(r.d) *
            KineticaConstants.appliedBoost(r.bm, r.d) *
            KineticaConstants.appliedBoost(rawPb(r), r.d)

    @Test
    fun sieteBeatsTheMoreFrequentDue() {
        // A device row: "due" (fw 0.85) at d=0.579 beat "siete" (fw 0.80) at 0.33
        // on frequency alone - both bm=1.0 - and the wrong pick then lost the
        // language too (the gate compared due's distance against Spanish).
        val c = decode(IT, swipe(CASE_C), listOf("sudare", "sergei"), mapOf("due" to 11, "siete" to 1, "dire" to 6, "sue" to 2))
        assertBeats(c, "siete", "due", "siete/due")
    }

    @Test
    fun herStillWinsWhenItGenuinelyFitsBetter() {
        // The control for the here/her pair: on THAT gesture
        // "her" really is the closer fit (0.275 vs 0.407 rebuilt), so it must
        // keep the word. The fix must promote shape, not the longer word.
        val c = decode(EN, swipe("hgfrer", StreamId.RIGHT), emptyList())
        assertBeats(c, "her", "here", "here/her control")
    }

    // ---- the constraints a re-weighting must not break ---------------------

    @Test
    fun sareiStillBeatsSergeiWhenItsOwnFitIsPoor() {
        // The non-negotiable constraint, from a device row.
        // Here "sarei" is the BAD fit (d=1.06 on device, 0.80 on this
        // reconstruction) and must still win on Italian frequency against
        // "sergei" at 0.59. This is what forbids a plain sharpening: on the
        // device numbers any 1/(1+d)^g needs g < 1.06 here while the sudare row
        // above needs g >= 1.44. The saturation is what satisfies both: past
        // GEO_SATURATION_KW the term is flat, so a poor fit stops being
        // punished for exactly how poor it is and frequency decides.
        val c = decode(
            IT,
            listOf(
                TestData.swipe("sertre", g, 0, 700, StreamId.LEFT),
                TestData.tap('i', g, 78, StreamId.RIGHT),
            ),
            listOf("ayudarte", "sudare"),
        )
        assertBeats(c, "sarei", "sergei", "sarei/sergei, the poor-fit constraint")
        // atei (d=0.377) is the best geometric fit on this path; a "promote the
        // best shape" rule would commit it, which is the trap this row exists
        // to catch.
        assertEquals("atei must not win on shape: ${words(c).take(3)}", "sarei", words(c).first())
    }

    @Test
    fun sempreKeepsItsBigramJustPastTheCap() {
        // The bigram rule's binding constraint, and the mirror of the sarei row
        // one term to the right: a word whose own fit has saturated but which the CONTEXT is
        // right about. "sempre" at d=0.55 must keep the top slot against
        // "stremo" at 0.45, which it does only because a saturated bigram
        // retains part of its strength rather than none of it.
        val ranked = SEMPRE_ROW.entries.sortedByDescending { shippedScore(it.value) }.map { it.key }
        assertEquals("sempre lost its own row: ${ranked.take(3)}", "sempre", ranked.first())

        // ...and the alternative is load-bearing, not hypothetical: dropping
        // BOTH boosts outright past the cap - the hard gate this fade replaced -
        // loses this row. A future re-tune to a threshold of any kind must go
        // red here.
        val hardGated = SEMPRE_ROW.entries.sortedByDescending {
            val r = it.value
            val gate = if (r.d >= KineticaConstants.GEO_SATURATION_KW) 1f else r.bm * rawPb(r)
            r.fw * KineticaConstants.geometricTerm(r.d) * gate
        }.map { it.key }
        assertEquals(
            "a hard gate must lose this row, else it is not what chose the shape",
            "stremo",
            hardGated.first(),
        )
    }

    @Test
    fun connieNeverOutranksComputer() {
        // A device row, the 22-contact "computer" path. "come" wins it on a
        // personal boost of ~1.59 and no re-weighting of d can change that:
        // computer > come needs g > 4.1 while computer > connie caps g < 1.5 on
        // the SAME path, an empty interval. What must
        // never happen is the failure mode a sharper term invites - "connie",
        // a proper noun and the best geometric fit at d=0.46, taking the word.
        //
        // Pinned on the trace's own numbers. On the polyline
        // reconstruction this contest stopped existing: "computer" lands past
        // GEO_SATURATION_KW there, so bounding the boost (its count is 1, worth
        // 1.10x) drops it out of the ten-wide window entirely, while "connie" at
        // d=0.42 is inside the cap and unboosted. That is the reconstruction
        // being cleaner than the gesture again - on the DEVICE row "computer"
        // keeps its rank above "connie" by 3.2%, which is what this asserts. The
        // reconstruction still carries the half that matters for the product,
        // asserted below: the proper noun must not lead.
        val row = DEVICE_GUARD_ROW
        val computer = shippedScore(row.getValue("computer"))
        val connie = shippedScore(row.getValue("connie"))
        assertTrue("connie $connie must never outrank computer $computer", computer > connie)

        val w = words(decode(IT, swipe("cvghjiokjnkiuytyuytrer"), listOf("weekend"), mapOf("come" to 50, "computer" to 1, "vuole" to 4, "volte" to 3)))
        assertTrue("connie must not lead: $w", w.firstOrNull() != "connie")
    }

    @Test
    fun cleanPathsKeepDecodingTheirOwnWord() {
        // A sharper geometric term can only help these, but they are the cheap
        // proof that nothing inverted: each word on its own centre-to-centre
        // path must still be top-1 against the full dictionary.
        for (w in listOf("sarei", "sudare", "siete", "computer", "vedere", "parlare", "quando")) {
            assertEquals("$w lost its own clean path", w, words(decode(IT, swipe(w), emptyList())).firstOrNull())
        }
        for (w in listOf("there", "here", "something", "keyboard")) {
            assertEquals("$w lost its own clean path", w, words(decode(EN, swipe(w), emptyList())).firstOrNull())
        }
    }

    // ---- the score/prune pair ---------------------------------------------

    @Test
    fun theAbandonBoundIsTheExactInverseOfTheScore() {
        // WordPredictor.emit derives its DTW budget by inverting the geometric
        // term (maxDTotalForScore). If the two ever disagree the prune drops
        // candidates that would have won - silently, and only on some
        // dictionaries. Assert the round trip directly.
        for (num in listOf(0.3f, 0.5f, 0.9f, 1.4f, 2.8f)) {
            for (minScore in listOf(0.05f, 0.2f, 0.4f, 0.7f, 1.2f)) {
                val dMax = KineticaConstants.maxDTotalForScore(num, minScore)
                if (dMax == Float.POSITIVE_INFINITY) {
                    // Claim: even a fully saturated fit clears the bar.
                    assertTrue(
                        "infinite budget claimed but saturated score $num loses to $minScore",
                        num * KineticaConstants.geometricTerm(KineticaConstants.GEO_SATURATION_KW) >= minScore,
                    )
                    continue
                }
                if (dMax <= 0f) {
                    assertTrue(
                        "zero budget claimed but a perfect fit would have won",
                        num * KineticaConstants.geometricTerm(0f) <= minScore,
                    )
                    continue
                }
                // At the bound the scores agree; just inside it the candidate wins.
                assertEquals(
                    "bound is not the inverse at num=$num minScore=$minScore",
                    minScore.toDouble(),
                    (num * KineticaConstants.geometricTerm(dMax)).toDouble(),
                    1e-4,
                )
                assertTrue(
                    "a candidate just inside the bound must beat the heap minimum",
                    num * KineticaConstants.geometricTerm(dMax * 0.98f) > minScore,
                )
            }
        }
    }

    @Test
    fun theAppliedBoostNeverExceedsTheBoostItIsDerivedFrom() {
        // The admissibility invariant, over the one shared rule.
        // WordPredictor.emit computes its DTW abandon budget from the RAW boosts
        // while scoring uses the applied ones, and that is only admissible while
        // applied <= raw. If the two ever cross, the prune silently drops
        // candidates that would have won - the failure mode maxDTotalForScore's
        // own comment warns about, invisible except on some dictionaries. The
        // boost must also never fall below 1.0, or a context hit or a personal
        // count would PUNISH a candidate.
        for (raw in listOf(1.0f, 1.10f, 1.29f, 1.46f, 1.61f, 1.83f, 1.96f, 2.5f)) {
            var d = 0f
            while (d <= 3f) {
                val applied = KineticaConstants.appliedBoost(raw, d)
                assertTrue("applied $applied exceeds raw $raw at d=$d", applied <= raw)
                assertTrue("applied boost must never be below 1.0", applied >= 1f)
                d += 0.01f
            }
        }
    }

    @Test
    fun theBoostIsUntouchedInsideTheCap() {
        // The safety argument, and the reason no scoring contest, no tap decode
        // and no PersonalWeightTest fixture had to be re-tuned: inside
        // GEO_SATURATION_KW the weight is exactly 1, so a candidate whose own
        // geometry still discriminates scores bit-identically to before. An
        // all-tap decode has geoFit = 0f and is covered by the same clause,
        // which is what leaves autocorrect alone.
        for (raw in listOf(1.0f, 1.29f, 1.46f, 1.96f, 2.5f)) {
            var d = 0f
            while (d < KineticaConstants.GEO_SATURATION_KW) {
                assertEquals(
                    "the boost must be untouched at d=$d",
                    raw.toDouble(),
                    KineticaConstants.appliedBoost(raw, d).toDouble(),
                    1e-6,
                )
                d += 0.01f
            }
        }
        assertEquals("weight must be exactly 1 at the cap", 1.0,
            KineticaConstants.boostWeight(KineticaConstants.GEO_SATURATION_KW).toDouble(), 1e-6)
    }

    @Test
    fun theBoostWeightIsContinuousAtTheCapAndGoneOneKeyLater() {
        // The whole point. Both shapes this replaces jumped here: the hard gate
        // from raw to 1.0, the flat retention from raw to 1+(raw-1)*0.2186.
        // "sempre" led at d=0.37/0.47 carrying pb=1.54 and lost at d=0.50/0.60
        // carrying pb=1.0 in one capture, so the jump was deciding real rows.
        val cap = KineticaConstants.GEO_SATURATION_KW
        for (raw in listOf(1.2f, 1.54f, 1.96f, 2.5f)) {
            for (eps in listOf(1e-2f, 1e-3f, 1e-4f)) {
                val below = KineticaConstants.appliedBoost(raw, cap - eps)
                val above = KineticaConstants.appliedBoost(raw, cap + eps)
                // Continuity means the gap shrinks WITH eps rather than to a
                // fixed fraction of the boost: 10 * (raw-1) * eps is a generous
                // Lipschitz bound (the fade's slope at the cap is ~4.8), and a
                // gate or a flat retention blows through it at every eps.
                assertTrue(
                    "a step of ${below - above} survives at the cap for raw=$raw, eps=$eps",
                    below - above < 10f * (raw - 1f) * eps,
                )
            }
            // ...and it is gone by one whole key, which is what lets an
            // all-saturated row fall through to fw.
            for (d in listOf(2f * cap, 2f * cap + 0.01f, 1.4f, 3f)) {
                assertEquals(
                    "a boost past one key hop must be gone entirely at d=$d",
                    1.0,
                    KineticaConstants.appliedBoost(raw, d).toDouble(),
                    1e-6,
                )
            }
        }
        // Monotone non-increasing throughout - every guarantee below rests on it.
        var prev = 1f
        var d = 0f
        while (d <= 3f) {
            val w = KineticaConstants.boostWeight(d)
            assertTrue("weight rose at d=$d: $w after $prev", w <= prev + 1e-6f)
            assertTrue("weight left [0,1] at d=$d: $w", w in 0f..1f)
            prev = w
            d += 0.01f
        }
    }

    @Test
    fun theBoostConditionNeverFavoursTheWorseFit() {
        // The property that bounds the scoring regression surface, over the one
        // shared rule - and stated more carefully than it once was, because
        // measurement showed the loose phrasing was not what any version of the
        // rule ever guaranteed. "Never moves a contest away from the better fit"
        // is FALSE in general for any fit-weighted boost: if the better-fitting
        // candidate is the boosted one, attenuating its boost necessarily
        // narrows its lead, whatever the worse one is doing. The older "both
        // outside" branch was already about compression rather than fit and hid
        // that.
        //
        // What is true, and is what actually bounds the surface, is that the
        // applied boost is non-decreasing in the raw value and non-increasing in
        // the candidate's own distance. The four corollaries are asserted here.
        val inside = listOf(0.0f, 0.12f, 0.3f, 0.49f)
        val fading = listOf(0.5f, 0.62f, 0.75f, 0.99f)
        val gone = listOf(1.0f, 1.1f, 2.4f)
        val all = inside + fading + gone
        val boosts = listOf(1.0f, 1.4f, 1.96f, 2.5f)
        val cap = KineticaConstants.GEO_SATURATION_KW
        for (a in boosts) {
            for (b in boosts) {
                for (da in all) {
                    for (db in all) {
                        val was = a / b
                        val now = KineticaConstants.appliedBoost(a, da) /
                            KineticaConstants.appliedBoost(b, db)
                        when {
                            // 1. Both in-cap: nothing changes at all. This is what
                            // leaves in-cap context prediction, every scoring contest
                            // and every all-tap decode untouched.
                            da < cap && db < cap ->
                                assertEquals("in-cap pairs must be untouched", was.toDouble(), now.toDouble(), 1e-5)
                            // 2. Both a whole key out: both boosts are gone, so
                            // the row falls through to fw. That is "past the cap,
                            // frequency decides" finally reaching the
                            // all-saturated rows, and it is what wins the
                            // "nosotros" row where a flat retention left a
                            // documented residual.
                            da >= 2f * cap && db >= 2f * cap ->
                                assertEquals("past one key hop the boosts must both be gone", 1.0, now.toDouble(), 1e-5)
                            // 3. Same distance: compressed toward 1, never past it.
                            da == db -> {
                                if (was > 1f) assertTrue("boost edge grew: $now vs $was", now in 1f..was + 1e-5f)
                                if (was < 1f) assertTrue("boost deficit grew: $now vs $was", now in (was - 1e-5f)..1f)
                            }
                            // 4. Same raw boost, different fits: the better fit
                            // must end up with at least as much of it. This is
                            // the honest form of "never favours the worse fit".
                            a == b ->
                                assertTrue("the better fit kept less: $now vs $was", if (da < db) now >= 1f else now <= 1f)
                        }
                        // ...and monotonicity in each argument separately, which
                        // is what the four corollaries above follow from.
                        if (a > b) {
                            assertTrue(
                                "a larger raw boost applied smaller at d=$da",
                                KineticaConstants.appliedBoost(a, da) >= KineticaConstants.appliedBoost(b, da),
                            )
                        }
                        if (da < db) {
                            assertTrue(
                                "a better fit kept less of raw=$a",
                                KineticaConstants.appliedBoost(a, da) >= KineticaConstants.appliedBoost(a, db),
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun theEngineReportsTheAttenuatedBigramNotTheTableValue() {
        // End-to-end through WordPredictor, because everything above is the
        // mirrored formula: a real decode must apply the condition and must
        // publish the applied value on the candidate, which is what makes a
        // device capture readable (a bm strictly between 1.0 and the table value
        // is the condition firing).
        val trie = TestData.smallDictionary()
        val table = BigramTable.build(
            listOf(Triple(trie.nodeFor("so"), trie.nodeFor("something"), 1000L)),
        )
        val raw = table.multiplier(trie.nodeFor("so"), trie.nodeFor("something"))
        assertTrue("fixture must carry a real boost", raw > 1f)
        val p = WordPredictor(trie, table, g)

        // A clean path keeps the whole boost; a badly sloppy one cannot.
        val clean = p.decode(listOf(TestData.swipe("something", g, 0, 600)), listOf("so"))
            .first { it.word == "something" }
        assertTrue("a good fit must be inside the cap: ${clean.dtwDistance}",
            clean.dtwDistance < KineticaConstants.GEO_SATURATION_KW)
        assertEquals("a good fit keeps the table value", raw.toDouble(), clean.bigramMultiplier.toDouble(), 1e-5)

        val sloppy = p.decode(listOf(TestData.sloppySwipe("something", g, 0, 600, 1.1f)), listOf("so"))
            .firstOrNull { it.word == "something" }
        assumeTrue("sloppy fixture must still reach the word", sloppy != null)
        assumeTrue(
            "sloppy fixture must land past the cap to prove anything",
            sloppy!!.dtwDistance >= KineticaConstants.GEO_SATURATION_KW,
        )
        assertTrue(
            "a saturated fit must report an attenuated bigram: ${sloppy.bigramMultiplier} vs $raw",
            sloppy.bigramMultiplier < raw && sloppy.bigramMultiplier > 1f,
        )
        assertEquals(
            "and it must be exactly appliedBoost",
            KineticaConstants.appliedBoost(raw, sloppy.dtwDistance).toDouble(),
            sloppy.bigramMultiplier.toDouble(),
            1e-5,
        )
    }

    @Test
    fun theGeometricTermSaturatesAndIsMonotone() {
        assertEquals("a perfect fit must score 1.0", 1.0, KineticaConstants.geometricTerm(0f).toDouble(), 1e-6)
        var prev = 1f
        var d = 0.05f
        while (d < KineticaConstants.GEO_SATURATION_KW) {
            val v = KineticaConstants.geometricTerm(d)
            assertTrue("term must decrease up to saturation", v < prev)
            prev = v
            d += 0.05f
        }
        val at = KineticaConstants.geometricTerm(KineticaConstants.GEO_SATURATION_KW)
        for (beyond in listOf(0.6f, 1.0f, 2.0f, 8.0f)) {
            assertEquals("term must be flat past saturation", at.toDouble(), KineticaConstants.geometricTerm(beyond).toDouble(), 1e-6)
        }
    }

    /**
     * (dtwDistance, fw, bm, score) exactly as the trace printed them. [sat] is
     * true when the capture postdates the saturating term, i.e. its `s` was produced by the
     * saturating term rather than by `1/(1+d)` - which changes how `pb` inverts
     * out of it.
     */
    private data class Row(
        val d: Float,
        val fw: Float,
        val bm: Float,
        val s: Float,
        val sat: Boolean = false,
        /**
         * The RAW personal boost, when the row's own `s` cannot yield it.
         *
         * Inverting `s` recovers whatever `pb` the shipping formula APPLIED,
         * which for a pre-condition capture is the raw value but for a gated one is
         * 1.0 on exactly the candidates a step-edge row is about. Those rows
         * carry the raw value measured elsewhere in the SAME capture - "sempre"
         * prints pb=1.54 at d=0.37/0.47 and 1.0 at 0.50/0.60 - which is what
         * made the step attributable in the first place. Stated explicitly here
         * rather than fitted, and the source line is named at each use.
         */
        val rawPb: Float? = null,
    )

    private companion object {
        /** Contact letters of the failing LEFT swipes, verbatim from the traces. */
        const val CASE_C = "sdftyuytrertre"

        /**
         * label -> (intended word, candidates). Every number is copied from a
         * `decode out:` line; nothing here is modelled or fitted.
         *
         * Deliberately absent, because no function of d can rank them and the
         * arithmetic says so:
         *  - computer/come: computer must beat come, needing
         *    g > 4.1, while computer must not lose to connie on the same path,
         *    capping g < 1.5. The guard half is asserted separately.
         *  - sudare/stare: "siate" has BOTH a lower distance
         *    (0.42 vs 0.43) and a higher numerator, so it dominates outright.
         */
        val DEVICE_ROWS = listOf(
            "sudare/state" to ("sudare" to mapOf(
                "state" to Row(0.79f, 0.78f, 1.54f, 0.744f),
                "sudare" to Row(0.18f, 0.62f, 1.0f, 0.619f),
                "stare" to Row(0.68f, 0.80f, 1.0f, 0.592f),
                "siate" to Row(0.41f, 0.70f, 1.0f, 0.576f),
                "due" to Row(1.29f, 0.85f, 1.0f, 0.510f),
            )),
            "sarei/sei" to ("sarei" to mapOf(
                "sei" to Row(0.47f, 0.90f, 1.0f, 0.911f),
                "si" to Row(0.99f, 0.93f, 1.0f, 0.742f),
                "di" to Row(1.42f, 0.98f, 1.0f, 0.727f),
                "dei" to Row(0.67f, 0.86f, 1.0f, 0.713f),
                "sarei" to Row(0.20f, 0.75f, 1.0f, 0.691f),
            )),
            "siete/due" to ("siete" to mapOf(
                "due" to Row(0.57f, 0.85f, 1.0f, 0.741f),
                "dire" to Row(0.68f, 0.85f, 1.0f, 0.641f),
                "siete" to Row(0.33f, 0.80f, 1.0f, 0.603f),
                "sue" to Row(0.57f, 0.77f, 1.0f, 0.545f),
            )),
            "there/the" to ("there" to mapOf(
                "the" to Row(0.56f, 0.98f, 1.77f, 1.785f),
                "there" to Row(0.22f, 0.90f, 1.55f, 1.594f),
                "threw" to Row(0.48f, 0.69f, 1.71f, 0.805f),
                "these" to Row(0.57f, 0.83f, 1.0f, 0.659f),
                "three" to Row(0.54f, 0.80f, 1.0f, 0.524f),
            )),
            "here/her" to ("here" to mapOf(
                "her" to Row(0.39f, 0.88f, 1.53f, 1.213f),
                "be" to Row(0.95f, 0.91f, 1.98f, 1.175f),
                "here" to Row(0.26f, 0.90f, 1.59f, 1.133f),
                "he" to Row(0.50f, 0.92f, 1.45f, 0.993f),
                "grew" to Row(0.78f, 0.68f, 1.74f, 0.670f),
            )),
            "cede/vede" to ("cede" to mapOf(
                "vede" to Row(0.41f, 0.75f, 1.0f, 0.682f),
                "ce" to Row(0.56f, 0.82f, 1.0f, 0.574f),
                "crede" to Row(0.35f, 0.73f, 1.0f, 0.540f),
                "cede" to Row(0.15f, 0.53f, 1.0f, 0.514f),
                "verde" to Row(0.52f, 0.70f, 1.0f, 0.454f),
            )),
            // The case-B constraint on its own device numbers: "sarei" is the
            // POOR fit here (1.06 against sergei's 0.59) and must still win.
            // Both distances land past GEO_SATURATION_KW, so the term is flat
            // across them and Italian frequency decides - the whole reason a
            // saturating shape can satisfy this row and the sarei/sei row above
            // at the same time, which no unbounded g can.
            // A third device instance of the unbounded personal boost. "keyboard"
            // fits at 0.318 against "leonard" at 1.172 and already leads on
            // fw*geo (0.1456 vs 0.1399); the whole deficit was three commits of
            // "leonard" against one of "keyboard". The capture postdates the
            // saturating term, so these scores are already saturated. This was
            // first read as an asset defect (fw 0.41); the arithmetic says
            // personal boost. Red if the boost is left unbounded.
            "keyboard/leonard" to ("keyboard" to mapOf(
                "leonard" to Row(1.17f, 0.64f, 1.0f, 0.170f, sat = true),
                "keyboard" to Row(0.31f, 0.41f, 1.0f, 0.163f, sat = true),
                "lewis" to Row(1.52f, 0.64f, 1.0f, 0.141f, sat = true),
                "klaus" to Row(1.95f, 0.63f, 1.0f, 0.138f, sat = true),
                "jenkins" to Row(1.42f, 0.59f, 1.0f, 0.130f, sat = true),
            )),
            // The row the merged ranking could not fix, and said so in advance:
            // Spanish "mujer" fits 2.2x better than
            // Italian "me", which wins anyway on ~59 commits (pb 1.605). The
            // languages are merged by WordComposer, but the contest is decided
            // before that - "me" simply outscores it - so it ranks here like any
            // other row. Red if the boost is left unbounded.
            "mujer/me, unbounded-boost row" to ("mujer" to mapOf(
                "me" to Row(0.88f, 0.89f, 1.0f, 0.760f),
                "mujer" to Row(0.40f, 0.81f, 1.0f, 0.578f),
                "ne" to Row(1.13f, 0.87f, 1.0f, 0.607f),
                "mie" to Row(1.10f, 0.78f, 1.0f, 0.471f),
                "mike" to Row(0.89f, 0.72f, 1.0f, 0.384f),
            )),
            // The three rows the bigram lever owns. Both `mujer` rows postdate
            // the personal-boost condition, so their `pb` was printed
            // and inverts back out: 1.0 on "me" (its ~59 commits are already
            // dropped by the fit condition) and 1.16/1.20 on "mujer". What
            // decides them is `bm = 1.96` on a candidate at d=0.9 - nearly twice
            // GEO_SATURATION_KW - i.e. a context boost at full strength on an
            // explanation the geometry has already rejected. Red if the bigram
            // is applied raw.
            "mujer/me, bigram row 1" to ("mujer" to mapOf(
                "me" to Row(0.90f, 0.93f, 1.96f, 0.402f, sat = true),
                "mujer" to Row(0.40f, 0.81f, 1.0f, 0.268f, sat = true),
                "muerte" to Row(0.94f, 0.78f, 1.0f, 0.171f, sat = true),
                "mire" to Row(0.95f, 0.74f, 1.0f, 0.162f, sat = true),
                "mike" to Row(0.91f, 0.73f, 1.0f, 0.160f, sat = true),
            )),
            "mujer/me, bigram row 2" to ("mujer" to mapOf(
                "me" to Row(0.84f, 0.93f, 1.96f, 0.402f, sat = true),
                "mujer" to Row(0.30f, 0.81f, 1.0f, 0.362f, sat = true),
                "muerte" to Row(0.98f, 0.78f, 1.0f, 0.171f, sat = true),
                "mire" to Row(0.93f, 0.74f, 1.0f, 0.162f, sat = true),
                "mike" to Row(0.80f, 0.73f, 1.0f, 0.160f, sat = true),
            )),
            "mujer/me, bigram row 3" to ("mujer" to mapOf(
                "me" to Row(0.90f, 0.93f, 1.42f, 0.699f),
                "mujer" to Row(0.34f, 0.81f, 1.0f, 0.605f),
                "mude" to Row(0.64f, 0.59f, 1.49f, 0.542f),
                "mire" to Row(0.89f, 0.74f, 1.34f, 0.527f),
                "mudo" to Row(0.64f, 0.56f, 1.49f, 0.515f),
            )),
            // The other half of that finding: on this path "computer" fits at
            // d=0.30 - INSIDE the cap - and still loses, to whatever the
            // preceding word happens to boost. The bigram condition is what
            // records this contest today.
            // It does NOT touch the proofs above: `computer` > `come` is
            // unreachable by any function of d and by any bounded personal boost,
            // and on that row bm = 1.0 for both, so the third lever misses it too.
            "computer/vuole" to ("computer" to mapOf(
                "vuole" to Row(0.94f, 0.82f, 1.65f, 0.297f, sat = true),
                "computer" to Row(0.30f, 0.72f, 1.0f, 0.268f, sat = true),
                "volete" to Row(1.04f, 0.75f, 1.60f, 0.265f, sat = true),
                "vivere" to Row(1.69f, 0.76f, 1.51f, 0.251f, sat = true),
                "chiudere" to Row(1.59f, 0.70f, 1.41f, 0.216f, sat = true),
            )),
            "sarei/sergei, poor fit wins on frequency" to ("sarei" to mapOf(
                "sarei" to Row(1.06f, 0.75f, 1.0f, 0.364f),
                "sergei" to Row(0.59f, 0.57f, 1.0f, 0.358f),
                "aerei" to Row(0.85f, 0.64f, 1.0f, 0.344f),
                "seri" to Row(0.84f, 0.62f, 1.0f, 0.338f),
                "atei" to Row(0.59f, 0.48f, 1.0f, 0.305f),
            )),
            // ---- the step edge ---------------------------------------------
            // Three rows from one device capture, all decided by the
            // discontinuity rather than by any factor: `bm = 1.0` on every
            // candidate involved, so the bigram rule
            // could not have moved them. "sempre" is 4 for 4 on the threshold in
            // that one capture - it LED at d=0.37 and 0.47 carrying pb=1.54 and
            // LOST at 0.50 and 0.60 carrying pb=1.0 - which is what makes the
            // attribution arithmetic rather than inferred. Red under a hard
            // gate, and the first of them is red at EXACTLY the cap,
            // since that condition was `<`.
            "sempre/stremo, at the cap" to ("sempre" to mapOf(
                "stremo" to Row(0.47f, 0.65f, 1.0f, 0.195f, sat = true),
                // pb=1.54, printed by the same capture at d=0.37 and
                // d=0.47, where the gate still let it through.
                "sempre" to Row(0.50f, 0.85f, 1.0f, 0.185f, sat = true, rawPb = 1.54f),
                "saremo" to Row(0.60f, 0.74f, 1.0f, 0.162f, sat = true),
                "saremmo" to Row(0.60f, 0.69f, 1.0f, 0.151f, sat = true),
                "daremo" to Row(0.69f, 0.64f, 1.0f, 0.140f, sat = true),
            )),
            "sempre/stremo, past the cap" to ("sempre" to mapOf(
                "stremo" to Row(0.44f, 0.65f, 1.0f, 0.208f, sat = true),
                "sempre" to Row(0.60f, 0.85f, 1.0f, 0.185f, sat = true, rawPb = 1.54f),
                "saremo" to Row(0.70f, 0.74f, 1.0f, 0.162f, sat = true),
                "saremmo" to Row(0.70f, 0.69f, 1.0f, 0.151f, sat = true),
                "daremo" to Row(0.75f, 0.64f, 1.0f, 0.140f, sat = true),
            )),
            // The same shape in Spanish, and the one that shows the fade must
            // start AT the cap rather than past it: "mujer" sits 0.01 kw outside
            // and loses its whole 1.31 to the gate, at which point both
            // candidates clamp to the same geometric term and fw alone decides
            // (0.93 against 0.81). Note "me" carries pb = 1.0 here for a reason
            // that had to be measured rather than assumed - it is reinforced in
            // `it` (1.614) and not in `es`, and personal counts are per-language
            // Room rows. Another capture prints the es row ungated and it
            // inverts to 1.008.
            "mujer/me, es active" to ("mujer" to mapOf(
                // pb=1.0 MEASURED, not assumed: another capture prints the
                // es row ungated and it inverts to 1.008. The 1.614 recorded
                // elsewhere is the `it` row of the same gesture.
                //
                // A personal-dictionary export puts `me` at count 2
                // in `es` (raw 1.165) and 69 in `it` (1.637), so this row's own
                // value drifts with use. It does not matter here and that is
                // measured rather than hoped: `mujer` holds this row until `me`
                // passes **200** Spanish commits, because past the cap the weight
                // is 0.21 and `me` has to buy a 12.8% deficit through it.
                "me" to Row(0.89f, 0.93f, 1.0f, 0.205f, sat = true, rawPb = 1.0f),
                // pb=1.31, printed at d=0.33 (:11, :18) and 0.44 (:25).
                "mujer" to Row(0.51f, 0.81f, 1.0f, 0.178f, sat = true, rawPb = 1.31f),
                "muerte" to Row(1.25f, 0.78f, 1.0f, 0.171f, sat = true),
                "mire" to Row(1.23f, 0.74f, 1.0f, 0.162f, sat = true),
                "mike" to Row(0.97f, 0.73f, 1.0f, 0.160f, sat = true),
            )),
            // The all-saturated row, and the one a flat retention could NOT
            // fix because an affine retention COMPRESSES the
            // ratio between two saturated candidates instead of erasing it. Every
            // rival here sits a whole key out (1.03-1.39) carrying a bigram of
            // 1.33-1.60, while the intended word is the best fit AND has the
            // highest fw. Once a boost past one key hop is worth nothing, the row
            // falls through to fw exactly as it ought to. Red under both the
            // gate and the flat retention, which rank "necesita" first.
            "nosotros, all-saturated row" to ("nosotros" to mapOf(
                "nosotros" to Row(0.61f, 0.82f, 1.0f, 0.512f),
                "nuestros" to Row(1.03f, 0.77f, 1.33f, 0.511f),
                "necesita" to Row(1.32f, 0.77f, 1.43f, 0.478f),
                "maría" to Row(1.39f, 0.70f, 1.60f, 0.470f),
                "maria" to Row(1.39f, 0.64f, 1.60f, 0.431f),
            )),
        )

        /**
         * A device row verbatim. Not a DEVICE_ROWS entry because its intended
         * word cannot win it (see the exclusion note above); it exists for the
         * ordering guard alone.
         */
        val DEVICE_GUARD_ROW = mapOf(
            "come" to Row(0.98f, 0.91f, 1.0f, 0.728f),
            "vuole" to Row(1.01f, 0.82f, 1.0f, 0.527f),
            "volte" to Row(0.83f, 0.79f, 1.0f, 0.480f),
            "computer" to Row(0.71f, 0.71f, 1.0f, 0.462f),
            "connie" to Row(0.46f, 0.62f, 1.0f, 0.428f),
        )

        /**
         * A device row verbatim - the one that chose the bigram rule's shape,
         * and the reason a hard gate could not be reused. Intending "sempre",
         * ctx [sempre, saremo], the word lands at d=0.55: 0.05 kw past
         * GEO_SATURATION_KW, carried entirely by its own bigram. Dropping the
         * boost outright there commits "stremo" instead - the documented cost
         * ("a word you reinforced because the decoder kept missing it loses its
         * rescue") landing on a resume-family flagship, on real thumbs.
         */
        val SEMPRE_ROW = mapOf(
            "sempre" to Row(0.55f, 0.84f, 1.91f, 1.552f),
            "saremo" to Row(0.62f, 0.74f, 1.0f, 0.626f),
            "stremo" to Row(0.45f, 0.65f, 1.0f, 0.571f),
            "saremmo" to Row(0.62f, 0.69f, 1.0f, 0.427f),
            "sereno" to Row(0.52f, 0.57f, 1.0f, 0.376f),
        )

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
        val EN by lazy { load("en") }
    }
}
