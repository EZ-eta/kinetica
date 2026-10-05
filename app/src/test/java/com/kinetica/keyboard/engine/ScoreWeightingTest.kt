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
 * A frequent, context-boosted or personally reinforced word with mediocre shape
 * must not outrank a near-perfect geometric fit. Under `1/(1+d)` it did, because
 * that term varies only 1.0 -> 0.4 across the useful distance range while
 * `fw * bm * pb` spans ~3x.
 *
 * Every case is a real device contest, rebuilt from the `keys=` contact letters
 * as a polyline through those key centres (as in LanguageDetectGoldenTest). Two
 * things make these fixtures faithful where a plain swipe is not:
 *
 *  - the captured `ctx=` is replayed, so the bigram multiplier that decided the
 *    contest is present (the quindi->state boost, she->the, he->her);
 *  - the personal commit counts are synthesised from the boost the trace
 *    implies (count = exp((pb-1)/PERSONAL_BOOST) - 1, where
 *    pb = s*(1+d)/(fw*bm)). Three rows were decided by personal reinforcement
 *    the JVM has no other way to see: "come" at pb 1.59 and "sei" at 1.49 beat
 *    their rivals on that alone.
 *
 * The counts are approximate (the trace prints 2 decimals, and pb is
 * exponential in the count), chosen to reproduce the observed order; they are
 * not claims about any real dictionary.
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
     * Five of the six cannot be carried by the polyline reconstruction used
     * elsewhere in this class: a path through exact key centres is cleaner than
     * the gesture and compresses the distance gap under test. "the"/"there" is
     * the plainest example, 0.56 vs 0.22 on the device and 0.39 vs 0.28 rebuilt,
     * so the rebuilt contest is barely half as decisive and "there" stays at
     * rank 2. Only siete/due survives rebuilding intact, and it has its own
     * end-to-end test below.
     *
     * So the contests are locked on their real numbers, dictionary-free, as
     * LanguagePreferenceTest pins its tuples.
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
     * The raw personal boost behind a captured row: the trace's own `s`,
     * inverted through the formula that produced it (older captures print no
     * `pb`, and newer ones print the applied value; see [Row.rawPb] for the rows
     * where that is not enough).
     */
    private fun rawPb(r: Row): Float = r.rawPb ?: if (r.sat) {
        r.s / (r.fw * r.bm * KineticaConstants.geometricTerm(r.d))
    } else {
        r.s * (1f + r.d) / (r.fw * r.bm)
    }

    /**
     * The shipped score, re-derived from a captured row: recover the raw boosts,
     * then re-score through the current formula, saturation and the fit-weighted
     * boosts included.
     *
     * The fit weight uses `d` directly: every row here is a single swipe, so
     * dTotal carries no tap penalty and is the geometric mean.
     */
    private fun shippedScore(r: Row): Float =
        // `r.bm` is the table value every capture printed (all predate the fit
        // condition), so it inverts out of `s` raw and is re-applied through it.
        r.fw * KineticaConstants.geometricTerm(r.d) *
            KineticaConstants.appliedBoost(r.bm, r.d) *
            KineticaConstants.appliedBoost(rawPb(r), r.d)

    @Test
    fun sieteBeatsTheMoreFrequentDue() {
        // A device row: "due" (fw 0.85) at d=0.579 beat "siete" (fw 0.80) at 0.33
        // on frequency alone, both at bm=1.0.
        val c = decode(IT, swipe(CASE_C), listOf("sudare", "sergei"), mapOf("due" to 11, "siete" to 1, "dire" to 6, "sue" to 2))
        assertBeats(c, "siete", "due", "siete/due")
    }

    @Test
    fun herStillWinsWhenItGenuinelyFitsBetter() {
        // The control for the here/her pair: on this gesture "her" is the closer
        // fit (0.275 vs 0.407 rebuilt), so it must keep the word. Shape is
        // promoted, not the longer word.
        val c = decode(EN, swipe("hgfrer", StreamId.RIGHT), emptyList())
        assertBeats(c, "her", "here", "here/her control")
    }

    // ---- the constraints a re-weighting must not break ---------------------

    @Test
    fun sareiStillBeatsSergeiWhenItsOwnFitIsPoor() {
        // The binding constraint, from a device row: "sarei" is the bad fit (d=1.06)
        // and must still win on Italian frequency against "sergei" at 0.59. It forbids
        // a plain sharpening: on the device numbers any 1/(1+d)^g needs g < 1.06 here
        // while the sudare row above needs g >= 1.44, and the saturation satisfies both.
        // Asserted on the formula, like the DEVICE_ROWS contests: a `sertre` replay
        // with the tap under SPLIT_MARGIN_MS produced the two words only by accident of
        // its clock, and with early taps admitted `sergei` leaves the list.
        // Both distances are past GEO_SATURATION_KW, so the geometric term is the same
        // for them (a poor fit stops being punished for how poor it is) and the ranking
        // falls to frequency, which the shipped asset must order the right way round.
        val geoSarei = KineticaConstants.geometricTerm(1.06f)
        val geoSergei = KineticaConstants.geometricTerm(0.59f)
        assertEquals(
            "the saturation is the whole mechanism: two poor fits must score the same shape",
            geoSergei,
            geoSarei,
            1e-6f,
        )
        val (dict, _) = IT
        val fwSarei = freqWeight(dict, "sarei")
        val fwSergei = freqWeight(dict, "sergei")
        assertTrue(
            "sergei is not in the shipped Italian asset, so this contest has moved",
            fwSergei > 0f,
        )
        assertTrue(
            "frequency must decide it: sarei $fwSarei vs sergei $fwSergei",
            fwSarei * geoSarei > fwSergei * geoSergei,
        )
    }

    /** The score's frequency term for [word], read from the shipped asset. */
    private fun freqWeight(dict: LoadedDictionary, word: String): Float {
        val node = dict.trie.nodeFor(word)
        if (node < 0 || !dict.trie.isWord(node)) return 0f
        return KineticaConstants.FREQ_WEIGHT_FLOOR +
            (1f - KineticaConstants.FREQ_WEIGHT_FLOOR) * dict.trie.frequency(node) / 255f
    }

    @Test
    fun aBetterFitIsNoLongerOvertakenByAMaxedBigram() {
        // A device row, ctx [how, held]: `here` was swiped, `her` committed, and
        // h-e-r-e was then typed letter by letter, so the intent is known, not
        // inferred.
        // `here` fits at 0.31 against `her`'s 0.54 and lost anyway, to a bigram near
        // the top of the table. The fade cannot reach it: the shipped width is the
        // unique optimum, and every width that wins this row loses two `sempre` reps
        // and `sarei`. The cap reaches it.
        // The bigram is carried as its byte share and the table value derived from
        // BIGRAM_BOOST_MAX, because that value is 1 + cap * byte/255. A literal here
        // would pass at every cap and pin nothing.
        val herShare = 0.792f
        assertTrue(
            "the better fit must lead at the shipped cap",
            rowScore(0.90f, 0.31f, 0f, 1.20f, KineticaConstants.BIGRAM_BOOST_MAX) >
                rowScore(0.88f, 0.54f, herShare, 1.21f, KineticaConstants.BIGRAM_BOOST_MAX),
        )
        // ...and at a cap of 1.5 the row goes the other way, so a revert must fail
        // here.
        assertTrue(
            "at the old cap this row must still go to the worse fit",
            rowScore(0.88f, 0.54f, herShare, 1.21f, 1.5f) >
                rowScore(0.90f, 0.31f, 0f, 1.20f, 1.5f),
        )
    }

    /**
     * A candidate's score from the parts a capture prints, with the bigram's table
     * value rebuilt at [cap]. [appliedPb] is the personal boost as printed, i.e.
     * already conditioned, so it is not passed through appliedBoost a second time.
     */
    private fun rowScore(fw: Float, d: Float, bmShare: Float, appliedPb: Float, cap: Float): Float =
        fw * KineticaConstants.geometricTerm(d) *
            KineticaConstants.appliedBoost(1f + bmShare * cap, d) * appliedPb

    @Test
    fun sempreKeepsItsBigramJustPastTheCap() {
        // The bigram rule's binding constraint, the mirror of the sarei row one
        // term to the right: a word whose own fit has saturated but which the
        // context is right about. "sempre" at d=0.55 must keep the top slot
        // against "stremo" at 0.45, which it does only because a saturated bigram
        // keeps part of its strength.
        val ranked = SEMPRE_ROW.entries.sortedByDescending { shippedScore(it.value) }.map { it.key }
        assertEquals("sempre lost its own row: ${ranked.take(3)}", "sempre", ranked.first())

        // ...and dropping both boosts outright past the cap, a hard gate, loses
        // this row. A re-tune to a threshold of any kind must go red here.
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
        // the same path, an empty interval. What must never happen is the failure
        // a sharper term invites: "connie", a proper noun and the best geometric
        // fit at d=0.46, taking the word.
        // Pinned on the trace's own numbers. On the reconstruction "computer"
        // lands past GEO_SATURATION_KW, so bounding its boost (count 1, worth
        // 1.10x) drops it out of the ten-wide window, while "connie" at d=0.42 is
        // inside the cap and unboosted. On the device row "computer" stays above
        // "connie" by 3.2%, and this asserts that. The reconstruction still
        // carries the half that matters, asserted below: the proper noun must not
        // lead.
        val row = DEVICE_GUARD_ROW
        val computer = shippedScore(row.getValue("computer"))
        val connie = shippedScore(row.getValue("connie"))
        assertTrue("connie $connie must never outrank computer $computer", computer > connie)

        val w = words(decode(IT, swipe("cvghjiokjnkiuytyuytrer"), listOf("weekend"), mapOf("come" to 50, "computer" to 1, "vuole" to 4, "volte" to 3)))
        assertTrue("connie must not lead: $w", w.firstOrNull() != "connie")
    }

    @Test
    fun cleanPathsKeepDecodingTheirOwnWord() {
        // A sharper geometric term can only help these; they are the cheap proof
        // that nothing inverted: each word on its own centre-to-centre path must
        // still be top-1 against the full dictionary.
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
        // term (maxDTotalForScore). If the two disagree the prune silently drops
        // candidates that would have won, and only on some dictionaries. Assert
        // the round trip directly.
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
        // The admissibility invariant, over the shared rule. WordPredictor.emit
        // computes its DTW abandon budget from the raw boosts while scoring uses
        // the applied ones, which is admissible only while applied <= raw. If the
        // two cross, the prune silently drops candidates that would have won (see
        // maxDTotalForScore). The boost must also never fall below 1.0, or a
        // context hit or a personal count would punish a candidate.
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
        // The safety argument: inside GEO_SATURATION_KW the weight is exactly 1,
        // so a candidate whose own geometry still discriminates scores as if
        // there were no condition. An all-tap decode has geoFit = 0f and is
        // covered by the same clause, which leaves autocorrect alone.
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
        // Why this shape: a hard gate (raw to 1.0) or a flat retention (raw to
        // 1+(raw-1)*0.2186) jumps here. "sempre" led at d=0.37/0.47 carrying
        // pb=1.54 and lost at d=0.50/0.60 carrying pb=1.0 in one capture, so a
        // jump decides real rows.
        val cap = KineticaConstants.GEO_SATURATION_KW
        for (raw in listOf(1.2f, 1.54f, 1.96f, 2.5f)) {
            for (eps in listOf(1e-2f, 1e-3f, 1e-4f)) {
                val below = KineticaConstants.appliedBoost(raw, cap - eps)
                val above = KineticaConstants.appliedBoost(raw, cap + eps)
                // Continuity means the gap shrinks with eps, not to a fixed
                // fraction of the boost: 10 * (raw-1) * eps is a generous
                // Lipschitz bound (the fade's slope at the cap is ~4.8), and a
                // gate or a flat retention blows through it at every eps.
                assertTrue(
                    "a step of ${below - above} survives at the cap for raw=$raw, eps=$eps",
                    below - above < 10f * (raw - 1f) * eps,
                )
            }
            // ...and it is gone by one whole key, so an all-saturated row falls
            // through to fw.
            for (d in listOf(2f * cap, 2f * cap + 0.01f, 1.4f, 3f)) {
                assertEquals(
                    "a boost past one key hop must be gone entirely at d=$d",
                    1.0,
                    KineticaConstants.appliedBoost(raw, d).toDouble(),
                    1e-6,
                )
            }
        }
        // Monotone non-increasing throughout; every guarantee below rests on it.
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
        // The property that bounds the scoring regression surface. "Never moves
        // a contest away from the better fit" is false for any fit-weighted
        // boost: if the better-fitting candidate is the boosted one, attenuating
        // its boost narrows its lead whatever the worse one does.
        // What holds is that the applied boost is non-decreasing in the raw value
        // and non-increasing in the candidate's own distance. The four
        // corollaries are asserted here.
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
                            // 1. Both in-cap: nothing changes, which leaves in-cap
                            // context prediction, every scoring contest and every
                            // all-tap decode untouched.
                            da < cap && db < cap ->
                                assertEquals("in-cap pairs must be untouched", was.toDouble(), now.toDouble(), 1e-5)
                            // 2. Both a whole key out: both boosts are gone, so
                            // the row falls through to fw ("past the cap,
                            // frequency decides"). This wins the "nosotros" row,
                            // which a flat retention could not.
                            da >= 2f * cap && db >= 2f * cap ->
                                assertEquals("past one key hop the boosts must both be gone", 1.0, now.toDouble(), 1e-5)
                            // 3. Same distance: compressed toward 1, never past it.
                            da == db -> {
                                if (was > 1f) assertTrue("boost edge grew: $now vs $was", now in 1f..was + 1e-5f)
                                if (was < 1f) assertTrue("boost deficit grew: $now vs $was", now in (was - 1e-5f)..1f)
                            }
                            // 4. Same raw boost, different fits: the better fit
                            // must end up with at least as much of it, the strict
                            // form of "never favours the worse fit".
                            a == b ->
                                assertTrue("the better fit kept less: $now vs $was", if (da < db) now >= 1f else now <= 1f)
                        }
                        // ...and monotonicity in each argument separately, which
                        // the four corollaries above follow from.
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
        // mirrored formula: a real decode must apply the condition and publish
        // the applied value on the candidate, so a device capture is readable (a
        // bm strictly between 1.0 and the table value is the condition firing).
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
     * (dtwDistance, fw, bm, score) as the trace printed them. [sat] is true when
     * the row's `s` came from the saturating term, not `1/(1+d)`, which changes
     * how `pb` inverts out of it.
     */
    private data class Row(
        val d: Float,
        val fw: Float,
        val bm: Float,
        val s: Float,
        val sat: Boolean = false,
        /**
         * The raw personal boost, when the row's own `s` cannot yield it.
         *
         * Inverting `s` recovers the `pb` the formula applied: the raw value for
         * an ungated capture, but 1.0 on the very candidates a step-edge row is
         * about. Those rows carry the raw value printed elsewhere in the same
         * capture ("sempre" prints pb=1.54 at d=0.37/0.47 and 1.0 at 0.50/0.60),
         * stated here, not fitted.
         */
        val rawPb: Float? = null,
    )

    private companion object {
        /** Contact letters of the failing left-thumb swipe, verbatim from the trace. */
        const val CASE_C = "sdftyuytrertre"

        /**
         * label -> (intended word, candidates). Every number is copied from a
         * `decode out:` line; nothing here is modelled or fitted.
         *
         * Absent, because no function of d can rank them:
         *  - computer/come: computer must beat come, needing g > 4.1, while
         *    computer must not lose to connie on the same path, capping g < 1.5.
         *    The guard half is asserted separately.
         *  - sudare/stare: "siate" has both a lower distance (0.42 vs 0.43) and a
         *    higher numerator, so it dominates outright.
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
            // A third device instance of the unbounded personal boost. "keyboard"
            // fits at 0.318 against "leonard" at 1.172 and already leads on
            // fw*geo (0.1456 vs 0.1399); the whole deficit was three commits of
            // "leonard" against one of "keyboard". These scores are already
            // saturated. Not an asset defect (fw 0.41): the arithmetic says
            // personal boost. Red if the boost is left unbounded.
            "keyboard/leonard" to ("keyboard" to mapOf(
                "leonard" to Row(1.17f, 0.64f, 1.0f, 0.170f, sat = true),
                "keyboard" to Row(0.31f, 0.41f, 1.0f, 0.163f, sat = true),
                "lewis" to Row(1.52f, 0.64f, 1.0f, 0.141f, sat = true),
                "klaus" to Row(1.95f, 0.63f, 1.0f, 0.138f, sat = true),
                "jenkins" to Row(1.42f, 0.59f, 1.0f, 0.130f, sat = true),
            )),
            // A row the merged ranking cannot fix: Spanish "mujer" fits 2.2x
            // better than Italian "me", which wins anyway on ~59 commits (pb
            // 1.605). The contest is decided on score before WordComposer merges
            // the languages, so it ranks here like any other row. Red if the boost
            // is left unbounded.
            "mujer/me, unbounded-boost row" to ("mujer" to mapOf(
                "me" to Row(0.88f, 0.89f, 1.0f, 0.760f),
                "mujer" to Row(0.40f, 0.81f, 1.0f, 0.578f),
                "ne" to Row(1.13f, 0.87f, 1.0f, 0.607f),
                "mie" to Row(1.10f, 0.78f, 1.0f, 0.471f),
                "mike" to Row(0.89f, 0.72f, 1.0f, 0.384f),
            )),
            // The three rows the bigram condition owns. The first two were
            // captured with the personal-boost condition, so their `pb` inverts
            // back out: 1.0 on "me" (its ~59 commits dropped by the fit condition)
            // and 1.16/1.20 on "mujer". What decides them is `bm = 1.96` on a
            // candidate at d=0.9, nearly twice GEO_SATURATION_KW: a context boost
            // at full strength on an explanation the geometry has rejected. Red if
            // the bigram is applied raw.
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
            // On this path "computer" fits at d=0.30, inside the cap, and lost to
            // whatever the preceding word boosts; the bigram condition wins it.
            // It does not touch the proofs above: `computer` > `come` is
            // unreachable by any function of d and by any bounded personal boost,
            // and on that row bm = 1.0 for both, so the bigram condition misses it
            // too.
            "computer/vuole" to ("computer" to mapOf(
                "vuole" to Row(0.94f, 0.82f, 1.65f, 0.297f, sat = true),
                "computer" to Row(0.30f, 0.72f, 1.0f, 0.268f, sat = true),
                "volete" to Row(1.04f, 0.75f, 1.60f, 0.265f, sat = true),
                "vivere" to Row(1.69f, 0.76f, 1.51f, 0.251f, sat = true),
                "chiudere" to Row(1.59f, 0.70f, 1.41f, 0.216f, sat = true),
            )),
            // The sarei/sergei constraint on its own device numbers: "sarei" is
            // the poor fit (1.06 against sergei's 0.59) and must still win. Both
            // distances land past GEO_SATURATION_KW, so the term is flat across
            // them and Italian frequency decides. A saturating shape satisfies
            // this row and the sarei/sei row above at once; no unbounded g can.
            "sarei/sergei, poor fit wins on frequency" to ("sarei" to mapOf(
                "sarei" to Row(1.06f, 0.75f, 1.0f, 0.364f),
                "sergei" to Row(0.59f, 0.57f, 1.0f, 0.358f),
                "aerei" to Row(0.85f, 0.64f, 1.0f, 0.344f),
                "seri" to Row(0.84f, 0.62f, 1.0f, 0.338f),
                "atei" to Row(0.59f, 0.48f, 1.0f, 0.305f),
            )),
            // ---- the step edge ---------------------------------------------
            // Three rows from one device capture, all decided by a discontinuity,
            // not a factor: `bm = 1.0` on every candidate involved, so the bigram
            // rule cannot move them. "sempre" led at d=0.37 and 0.47 carrying
            // pb=1.54 and lost at 0.50 and 0.60 carrying pb=1.0, so the attribution
            // is arithmetic, not inferred. Red under a hard gate; the first is red
            // at exactly the cap under a `<` condition.
            "sempre/stremo, at the cap" to ("sempre" to mapOf(
                "stremo" to Row(0.47f, 0.65f, 1.0f, 0.195f, sat = true),
                // pb=1.54, printed by the same capture at d=0.37 and d=0.47,
                // where a gate still let it through.
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
            // The same shape in Spanish, and the row that shows the fade must
            // start at the cap, not past it: "mujer" sits 0.01 kw outside and
            // loses its whole 1.31 to a gate, and then both candidates clamp to the
            // same geometric term and fw alone decides (0.93 against 0.81). "me"
            // carries pb = 1.0 here because personal counts are per-language Room
            // rows: it is reinforced in `it` (1.614), not in `es`.
            "mujer/me, es active" to ("mujer" to mapOf(
                // pb=1.0 measured: another capture prints the es row ungated and
                // it inverts to 1.008. The 1.614 is the `it` row of the same
                // gesture.
                // The value drifts with use (a personal-dictionary export puts `me`
                // at count 2 in `es`, raw 1.165), and it does not matter: `mujer`
                // holds this row until `me` passes 200 Spanish commits, because
                // past the cap the weight is 0.21 and `me` has to buy a 12.8%
                // deficit through it.
                "me" to Row(0.89f, 0.93f, 1.0f, 0.205f, sat = true, rawPb = 1.0f),
                // pb=1.31, printed by the same capture at d=0.33 and 0.44.
                "mujer" to Row(0.51f, 0.81f, 1.0f, 0.178f, sat = true, rawPb = 1.31f),
                "muerte" to Row(1.25f, 0.78f, 1.0f, 0.171f, sat = true),
                "mire" to Row(1.23f, 0.74f, 1.0f, 0.162f, sat = true),
                "mike" to Row(0.97f, 0.73f, 1.0f, 0.160f, sat = true),
            )),
            // The all-saturated row. An affine retention compresses the ratio
            // between two saturated candidates instead of erasing it, so it
            // cannot fix this. Every rival sits a whole key out (1.03-1.39)
            // carrying a bigram of 1.33-1.60, while the intended word is the best
            // fit and has the highest fw. With a boost past one key hop worth
            // nothing, the row falls through to fw. Red under both a gate and a
            // flat retention, which rank "necesita" first.
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
         * A device row verbatim, the one that chose the bigram rule's shape.
         * Meant as "sempre", ctx [sempre, saremo], the word lands at d=0.55:
         * 0.05 kw past GEO_SATURATION_KW, carried entirely by its own bigram.
         * Dropping the boost outright there commits "stremo" instead, a
         * reinforced word losing its rescue on a resume-family flagship.
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
