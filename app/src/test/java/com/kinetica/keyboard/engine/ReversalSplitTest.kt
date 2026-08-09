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
 * Regression suite for the reversal-split bug: a swipe that reverses direction
 * (u->i->n->i) is interrupted mid-reversal by the other thumb's tap. The
 * tap-split cuts the swipe at the raw sample nearest the tap - mid-travel,
 * between keys - so the FIRST half used to end more than R_ENDPOINT_KW from
 * its real last letter and failed the matcher's isEnd gate: "quindi" pruned
 * in the only representable interleave, empty decode, literal taps left as
 * "QD". Mirror image of the resume bug, which treated only the second half's START;
 * fixed by the symmetric softEnd relaxation on split first halves.
 */
class ReversalSplitTest {

    private val g = TestData.qwertyGeometry()

    /** Reported word, reversal analogues, and rivals that survive the same
     *  patterns so ranking is a contest, not a forced choice. */
    private fun dict(): Trie = Trie.build(
        listOf(
            "quindi" to 5000,
            "quando" to 6000, // higher-frequency rival; must die on geometry (no a pass)
            "quinto" to 2000,
            "quinti" to 1500, // rival for quinto's second half (i sits 1.0 kw from o)
            "punto" to 4000,
            "punti" to 3800, // rival for punto's second half
            "punta" to 2000,
            "qui" to 8000,
            "quindici" to 1200,
            "the" to 12000, "and" to 15000, "a" to 20000,
        ),
    )

    private fun decode(tokens: List<InputToken>): List<String> =
        WordPredictor(dict(), BigramTable.EMPTY, g).decode(tokens, emptyList()).map { it.word }

    // ---- timelines -----------------------------------------------------------
    // TestData.swipe timestamps are linear over sample INDEX (10 per leg), so
    // the tap time selects the cut sample: for "uini" (3 legs, 31 samples over
    // 600 ms starting at t0=200) sample k sits at 200 + 20*k and the reversal
    // leg n->i is samples 20..30. Cut fraction along the leg = (k - 20) / 10.

    /** Left taps Q; right swipes u->i->n and reverses toward i; left taps D
     *  mid-reversal (tapT selects the cut depth); right finishes at i. */
    private fun quindi(tapT: Long) = listOf(
        TestData.tap('q', g, 0, StreamId.LEFT),
        TestData.swipe("uini", g, t0 = 200, durMs = 600, stream = StreamId.RIGHT),
        TestData.tap('d', g, tapT, StreamId.LEFT),
    )

    /** Same shape with an n->o final leg: left taps Q, right swipes
     *  u->i->n->o, left taps T while the right thumb travels n->o. */
    private fun quinto(tapT: Long) = listOf(
        TestData.tap('q', g, 0, StreamId.LEFT),
        TestData.swipe("uino", g, t0 = 200, durMs = 600, stream = StreamId.RIGHT),
        TestData.tap('t', g, tapT, StreamId.LEFT),
    )

    /** No leading tap: right swipes p->u->n->o, left taps T mid n->o travel. */
    private fun punto(tapT: Long) = listOf(
        TestData.swipe("puno", g, t0 = 0, durMs = 600, stream = StreamId.RIGHT),
        TestData.tap('t', g, tapT, StreamId.LEFT),
    )

    /** Sloppy fixture: overshoot vertices make the reversal leg samples 40..50
     *  of 51 over 1200 ms starting at 200; tapT=1304 cuts at 0.6 of the leg,
     *  ~1.7 kw from N - the same isEnd kill with realistic geometry. */
    private fun quindiSloppy(tapT: Long) = listOf(
        TestData.tap('q', g, 0, StreamId.LEFT),
        TestData.sloppySwipe("uini", g, t0 = 200, durMs = 1200, overshootKw = 0.25f, stream = StreamId.RIGHT),
        TestData.tap('d', g, tapT, StreamId.LEFT),
    )

    // ---- tests ---------------------------------------------------------------

    @Test
    fun quindiMidReversalTapDecodesTop1() {
        // Cut fractions 0.5 and 0.6 of the n->i reversal leg: the cut point is
        // 1.5-1.8 kw from N, so isEnd('n') was false and pre-fix decode empty.
        for (tapT in listOf(700L, 719L)) {
            val words = decode(quindi(tapT))
            assertTrue("quindi (tapT=$tapT) top-1 was ${words.take(3)}", words.firstOrNull() == "quindi")
        }
    }

    @Test
    fun quindiLateReversalTapDecodesTop1() {
        // The late-cut regime the softEnd fix did NOT cover and real typing
        // still hits: a reaction-timed D tap lands
        // LATE in the reversal (thumb almost back to I). tapT 740/760/780 =
        // cut fractions 0.7/0.8/0.9 of the n->i leg. Two gates conspire:
        //   - sequences() never fires the split (tap within SPLIT_MARGIN_MS of
        //     the swipe's end: 740 >= tEnd 800 - 80),
        //   - even if it did, splitSwipe's head trim leaves the remaining leg
        //     under MIN_SPLIT_HALF_ARC_KW so the second half is rejected.
        // Result: empty decode, literal "QD". Must now decode "quindi" top-1.
        for (tapT in listOf(740L, 760L, 780L)) {
            val words = decode(quindi(tapT))
            assertTrue("quindi (late tapT=$tapT) top-1 was ${words.take(3)}", words.firstOrNull() == "quindi")
        }
    }

    @Test
    fun quindiPostLiftTapDecodes() {
        // The D tap lands at or just after the right thumb lifts (tEnd 800):
        // human timing routinely places it there. The nearest cut sample is the
        // swipe's last, so splitSwipe used to bail on the cut-out-of-bounds
        // guard and no interleave could represent q|uin|d|i at all. "quindi"
        // must at least survive into the candidate list.
        for (tapT in listOf(810L, 850L)) {
            val words = decode(quindi(tapT))
            assertTrue("quindi (post-lift tapT=$tapT) missing from ${words.take(5)}", words.take(3).contains("quindi"))
        }
    }

    @Test
    fun quindiEarlyTapDecodesTop1() {
        // The early-tap regime (live-trace-confirmed): the
        // reaction-timed D tap lands at ~52-54% of the swipe INTERVAL - on the
        // forward i->n leg or in the apex dwell, 270+ ms before the swipe
        // ends. tapT 480/520/560 = cut samples 14/16/18 (40/60/80% of the
        // forward leg). The V1 split resumes half2 at the apex distance peak
        // (N), so half2 spans the whole ~3 kw reversal leg: minLetters=2
        // (arcLen >= MIN_SWIPE_ARC_KW at Matcher.buildSegment) and the
        // close-side length band both forbid the single trailing letter "i",
        // q|uin|d|i is unrepresentable, decode is empty, literal "QD" stays.
        // The endpoint-trimmed (V2) and apex-snapped (V3) split variants must
        // make it decode; at tapT=480 the cut sits ~1.8 kw before N, so half1
        // loses even the 'n' pass (R_INNER) and only V3 can save it.
        for (tapT in listOf(480L, 520L, 560L)) {
            val words = decode(quindi(tapT))
            assertTrue("quindi (early tapT=$tapT) top-1 was ${words.take(3)}", words.firstOrNull() == "quindi")
        }
    }

    @Test
    fun sloppyEarlyTapSurvives() {
        // Sloppy fixture, cut at 40% of the forward over(i)->n leg (sample 24
        // of 51, t=776): the cut sits ~1.86 kw from N, killing both the isEnd
        // mark and the R_INNER 'n' pass for half1 - the V3 apex-snapped cut
        // (the apex is the overshoot vertex 0.25 kw past N, still well inside
        // R_ENDPOINT) is the only rescue with realistic geometry.
        val words = decode(quindiSloppy(776L))
        assertTrue("sloppy early quindi top-1 was ${words.take(3)}", words.firstOrNull() == "quindi")
    }

    @Test
    fun sloppyApexDwellTapSurvives() {
        // Tap during the apex dwell itself (sample 35, t=1040, between N and
        // its overshoot vertex): half1 ends essentially ON n (real isEnd), but
        // the leftover reversal leg is ~2.6 kw even after the fixed head trim,
        // so V1's half2 still demands two letters. V2's endpoint-trimmed tail
        // is what admits the single trailing "i" here (no interior distance
        // peak follows the cut, so V3 does not exist for this timing).
        val words = decode(quindiSloppy(1040L))
        assertTrue("sloppy apex-dwell quindi top-1 was ${words.take(3)}", words.firstOrNull() == "quindi")
    }

    @Test
    fun sloppyLateReversalSurvives() {
        // Sloppy fixture, deep cut: reversal leg is samples 40..50 of 51 over
        // 1200 ms from t0=200, so tapT 1352 cuts at ~0.8 of the leg - past both
        // the old generator gate (tEnd 1400 - 80 = 1320) and the head-trim floor.
        val words = decode(quindiSloppy(1352L))
        assertTrue("sloppy late quindi top-1 was ${words.take(3)}", words.firstOrNull() == "quindi")
    }

    @Test
    fun shallowReversalCutStillDecodes() {
        // Cut at 0.4 of the leg (1.2 kw from N, inside R_ENDPOINT): decoded
        // correctly even pre-fix; locks that the fix leaves it undisturbed.
        val words = decode(quindi(680L))
        assertTrue("quindi (shallow cut) top-1 was ${words.take(3)}", words.firstOrNull() == "quindi")
    }

    @Test
    fun reversalSplitGeneralizesBeyondReportedWord() {
        val cases = listOf(
            Triple("quinto", ::quinto, 700L),
            Triple("punto", ::punto, 500L),
        )
        for ((expected, build, tapT) in cases) {
            val words = decode(build(tapT))
            assertTrue("$expected (tapT=$tapT) top-1 was ${words.take(3)}", words.firstOrNull() == expected)
        }
    }

    @Test
    fun sloppyReversalSurvives() {
        val words = decode(quindiSloppy(1304L))
        assertTrue("sloppy quindi top-1 was ${words.take(3)}", words.firstOrNull() == "quindi")
    }

    @Test
    fun realItalianDictionaryReversalDecode() {
        val trie = loadItalian() ?: return
        val predictor = WordPredictor(trie, BigramTable.EMPTY, g)

        val q = predictor.decode(quindi(700L), emptyList()).map { it.word }
        assertTrue("quindi missing from real-dict decode ${q.take(5)}", q.contains("quindi"))
        assertTrue("quindi not top-1 in $q", q.firstOrNull() == "quindi")

        // Early-tap regime against the full dictionary: the V2/V3 variants
        // must not only make quindi representable but keep it top-1 over
        // every real rival admitted by the softened splits.
        val qe = predictor.decode(quindi(520L), emptyList()).map { it.word }
        assertTrue("early-tap quindi not top-1 in ${qe.take(5)}", qe.firstOrNull() == "quindi")

        val qu = predictor.decode(quinto(700L), emptyList()).map { it.word }
        assertTrue("quinto not top-3 in $qu", qu.take(3).contains("quinto"))

        val p = predictor.decode(punto(500L), emptyList()).map { it.word }
        assertTrue("punto not top-3 in $p", p.take(3).contains("punto"))
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
