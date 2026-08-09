package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Regression suite for dual-thumb
 * words where the two streams genuinely interleave, which the single-cut merge
 * generators could not represent.
 *
 * The fixtures' TIMINGS are transcribed from a device capture, so these are
 * the real failures, not
 * invented ones. Path GEOMETRY is synthetic: the trace records token intervals
 * but no coordinates, so the swipe letters are inferred from the target word
 * minus the tapped letters, in order - the only reconstruction the data admits.
 *
 * Two mechanisms are covered:
 *  - "cuando": one swipe needing cuts at TWO interior cross-stream taps. The
 *    tap-split generator inserted one tap per sequence, so the representable
 *    orders were cuno|a|d, cu|a|no|d and cun|a|d|o - and that last one spells
 *    c,u,n,a,d,o, which is exactly the word that won on device ("cuñado",
 *    d=0.59). c,u,a,n,d,o was never a candidate.
 *  - partial overlap: two swipes where the second STARTS inside the first but
 *    ENDS after it - what two thumbs moving at once actually produce. The
 *    swipe-around-swipe generator required strict containment on both sides.
 */
class DualSimultaneousSwipeTest {

    private val g = TestData.qwertyGeometry()
    private val dtw = DtwMatcher()

    /**
     * Folded spellings only (Trie.build drops anything Alphabet.encode rejects,
     * so "cuñado" must appear as its folded key "cunado"). Frequencies follow
     * real Spanish: "cuando" is a top-100 word, "cunado" is far rarer - so the
     * contest is decided by frequency over geometry, exactly as on device.
     */
    private fun spanishish(): Trie = Trie.build(
        listOf(
            "cuando" to 400_000,
            "cunado" to 20_000,   // the wrong word the single-cut orders produce
            "cuanto" to 200_000,  // near-neighbour that must not be admitted here
            "cuna" to 8_000, "cuno" to 300, "culpado" to 3_000, "copado" to 2_000,
            "que" to 900_000, "de" to 1_000_000, "la" to 800_000,
        ),
    )

    /**
     * Analogue words for the generalization test, plus their losing rivals.
     * Frequencies follow real English: "planted" is roughly ten times commoner
     * than "plated", which is the word the tail-trimmed interior variant
     * produces from the same gesture - so that pairing is a real contest.
     */
    private fun analogues(): Trie = Trie.build(
        listOf(
            "wanted" to 9_000, "wander" to 6_000, "warder" to 400,
            "planted" to 20_000, "plated" to 800, "planned" to 4_000,
            "winter" to 7_000, "winner" to 3_500, "wither" to 900,
            "the" to 12_000, "and" to 15_000, "a" to 20_000,
        ),
    )

    /**
     * "praticamente" and the words the wrong readings of its gesture produce.
     * Frequencies are the shipped it_wordlist's own (praticamente 13926,
     * pratica 10900), so the contest is the device's.
     */
    private fun italianish(): Trie = Trie.build(
        listOf(
            "praticamente" to 13_926, "pratica" to 10_900, "pratico" to 1_420,
            "praticante" to 246, "precisa" to 1_939, "preferiva" to 442,
            "parlare" to 166_324, "primo" to 30_000, "di" to 900_000,
        ),
    )

    private fun decode(trie: Trie, tokens: List<InputToken>): List<String> =
        WordPredictor(trie, BigramTable.EMPTY, g).decode(tokens, emptyList()).map { it.word }

    // ---- timelines -----------------------------------------------------------

    /**
     * Device capture 12:40:38, es active. One LEFT swipe 9006679..9007614
     * (935 ms) with cross-stream taps at +320 ms and +582 ms. The swipe supplies
     * c,u then n then o; the taps supply a and d. Needs [cu][a][n][d][o].
     */
    private fun cuando(overshoot: Float = 0f) = listOf(
        if (overshoot == 0f) {
            TestData.swipe("cuno", g, t0 = 0, durMs = 935, stream = StreamId.LEFT)
        } else {
            TestData.sloppySwipe("cuno", g, 0, 935, overshoot, StreamId.LEFT)
        },
        TestData.tap('a', g, 320, StreamId.RIGHT),
        TestData.tap('d', g, 582, StreamId.RIGHT),
    )

    /**
     * Piece shape 2-1-1 like cuando: swipe w,a then t then d; taps n and e.
     * The interior piece is a single letter, so only the tail-trimmed interior
     * variant can close it. Cuts land on the leg joints - 31 samples over
     * 900 ms puts sample 10 at 300 ms and sample 20 at 600 ms.
     *
     * ("wander" was tried first and rejected as a fixture: it is already
     * reachable pre-fix because 'r' sits exactly FUZZY_TAP_RADIUS_KW from the
     * tapped 'e', so the fuzzy-anchor pass substitutes it and the case proves
     * nothing about multi-cut. 'd' is 1.58 kw from 'e', outside that radius.)
     */
    private fun wanted(overshoot: Float = 0f) = listOf(
        if (overshoot == 0f) {
            TestData.swipe("watd", g, t0 = 0, durMs = 900, stream = StreamId.LEFT)
        } else {
            TestData.sloppySwipe("watd", g, 0, 900, overshoot, StreamId.LEFT)
        },
        TestData.tap('n', g, 300, StreamId.RIGHT),
        TestData.tap('e', g, 600, StreamId.RIGHT),
    )

    /**
     * Piece shape 2-2-1: swipe p,l then n,t then d; taps a and e. The interior
     * piece carries TWO letters, whose ideal path (keyDist(n,t) = 3.9 kw) is
     * far longer than the 1.2 kw tail trim admits, so the untrimmed interior
     * variant is the only one that can close it. Together with wanted this
     * proves both interior variants are load-bearing rather than one being
     * redundant.
     */
    private fun planted() = listOf(
        TestData.swipe("plntd", g, t0 = 0, durMs = 1200, stream = StreamId.LEFT),
        TestData.tap('a', g, 300, StreamId.RIGHT),
        TestData.tap('e', g, 900, StreamId.RIGHT),
    )

    /**
     * From a device capture, Italian active. "praticamente" written the way it
     * naturally falls out for two thumbs: the RIGHT thumb taps
     * the right-hand letters p(0) i(4) m(7) n(9) while the LEFT thumb swipes the
     * left-hand ones in four legs, r-a-t | c-a | e | t-e. One continuous LEFT
     * swipe 23795021..23796663 (1642 ms) with all three taps inside it, so the
     * intended reading is [p][rat][i][ca][m][e][n][te] - three interior cuts.
     *
     * Measurement showed why it decoded EMPTY on device: the 4-piece interleave IS
     * generated, and all three of its trim modes reject that reading. The
     * untrimmed ones fail on `minLetters=2 (arc=2.46)` for the one-letter "e"
     * leg; the trimmed one cuts the "ca" piece down past its own 'c', which then
     * has no pass at all. Timings are the device's; geometry is reconstructed
     * from the trace's `keys=` contacts, so this is a reachability fixture.
     */
    private fun praticamenteOneSwipe(overshoot: Float = 0.25f) = listOf(
        TestData.tap('p', g, 0, StreamId.RIGHT),
        TestData.sloppySwipe("ratcaete", g, 78, 1642, overshoot, StreamId.LEFT),
        TestData.tap('i', g, 575, StreamId.RIGHT),
        TestData.tap('m', g, 985, StreamId.RIGHT),
        TestData.tap('n', g, 1253, StreamId.RIGHT),
    )

    /**
     * The same word from the same session (L234), one attempt earlier: the LEFT
     * thumb lifted after "ca" and re-swiped for "ete", so the word needs swipe1
     * cut at tap i AND swipe2 cut at tap n - cuts in two DIFFERENT swipes in one
     * sequence. Every generator substitutes at exactly one swipe index, so the
     * 8-element reading was in the language of none of them and the capture
     * topped out at three segments.
     */
    private fun praticamenteTwoSwipes(overshoot: Float = 0.25f) = listOf(
        TestData.tap('p', g, 0, StreamId.RIGHT),
        TestData.sloppySwipe("ratca", g, 79, 884, overshoot, StreamId.LEFT),
        TestData.tap('i', g, 511, StreamId.RIGHT),
        TestData.tap('m', g, 959, StreamId.RIGHT),
        TestData.sloppySwipe("ete", g, 1088, 578, overshoot, StreamId.LEFT),
        TestData.tap('n', g, 1249, StreamId.RIGHT),
    )

    /**
     * Device capture 12:40:46, the siempre shape: the LEFT swipe starts inside
     * the RIGHT swipe's interval (9015251 > 9014754) but ends after it
     * (9015518 > 9015343), so `inner.tEnd >= outer.tEnd - SPLIT_MARGIN_MS`
     * rejected the interleave. Rebuilt here as w-i-e-r crossed by n-t: the same
     * partial-overlap timing relationship, on geometry that spells "winter".
     */
    private fun partialOverlapWinter() = listOf(
        TestData.swipe("wier", g, t0 = 0, durMs = 600, stream = StreamId.LEFT),
        TestData.swipe("nt", g, t0 = 400, durMs = 300, stream = StreamId.RIGHT),
    )

    // ---- tests ---------------------------------------------------------------

    @Test
    fun cuandoNeedsTwoInteriorCuts() {
        // Ranking is asserted on the realistic path only. On the perfect
        // centre-to-centre path this fixture's own geometry favours the WRONG
        // word - "cunado" fits it at d=0.302 against "cuando"'s 0.447 - and
        // the saturating geometric term (KineticaConstants.GEO_EXPONENT)
        // now lets that show. Both distances sit under GEO_SATURATION_KW, so
        // the cap cannot separate them and only the 1.21x frequency edge did.
        // A clean path is the unrepresentative case here, exactly as pass
        // merging found: a real finger
        // overshoots, and at 0.25 kw of overshoot "cuando" wins outright.
        val words = decode(spanishish(), cuando(0.25f))
        assertTrue("cuando (overshoot 0.25) top-1 was ${words.take(3)}", words.firstOrNull() == "cuando")
        // What this fixture was written to prove is REACHABILITY - before the
        // multi-anchor interleave existed "cuando" was absent from
        // the clean list entirely, with "cunado" alone on top. That property is
        // asserted here so narrowing the ranking leg above cannot hide its loss.
        val clean = decode(spanishish(), cuando(0f))
        assertTrue("cuando unreachable on the clean path: $clean", clean.contains("cuando"))
    }

    @Test
    fun cuandoBeatsTheSingleCutRival() {
        // The precise device failure: "cunado" is what [cun][a][d][o] spells and
        // it must now lose. Locking the rival by name keeps this a contest - if a
        // future change makes cuando unreachable again, cunado reappears on top
        // rather than the test merely going quiet.
        val words = decode(spanishish(), cuando(0.25f))
        val ci = words.indexOf("cuando")
        val ri = words.indexOf("cunado")
        assertTrue("cuando missing from $words", ci >= 0)
        assertTrue("cuando must outrank cunado in $words", ri < 0 || ci < ri)
    }

    @Test
    fun multiAnchorSplitGeneralizes() {
        // Two different piece shapes and a second language, so the fix is the
        // mechanism rather than a patch for one word: 2-1-1 needs the
        // tail-trimmed interior variant, 2-2-1 needs the untrimmed one.
        val trie = analogues()
        for (overshoot in listOf(0f, 0.25f)) {
            val words = decode(trie, wanted(overshoot))
            assertTrue(
                "wanted (overshoot $overshoot) top-1 was ${words.take(3)}",
                words.firstOrNull() == "wanted",
            )
        }
        val pl = decode(trie, planted())
        assertTrue("planted top-1 was ${pl.take(3)}", pl.firstOrNull() == "planted")
    }

    @Test
    fun partialOverlapSwipesInterleave() {
        // Pre-fix: only the two concatenations exist, so "winter" is absent.
        val words = decode(analogues(), partialOverlapWinter())
        assertTrue("winter missing from ${words.take(5)}", words.contains("winter"))
    }

    @Test
    fun partialOverlapGeneratesAThreePieceSequence() {
        // Mechanism-level: sequences() must now emit the [A1][B][A2] interleave
        // for a partially overlapping pair, which strict containment rejected.
        val seqs = MergeAlternatives.sequences(partialOverlapWinter(), dtw)
        assertTrue(
            "no 3-token interleave in sizes ${seqs.map { it.size }}",
            seqs.any { it.size == 3 },
        )
    }

    @Test
    fun singleAnchorBufferKeepsItsExistingSequenceSet() {
        // k = 1 stays with splitVariants so the mid/late/early-tap regimes decode
        // byte-identically: a one-tap buffer must never produce a 3-piece split
        // of its swipe (which would be 4 tokens for this 2-token buffer).
        val tokens = listOf(
            TestData.swipe("helo", g, 0, 400, StreamId.RIGHT),
            TestData.tap('l', g, 260, StreamId.LEFT),
        )
        val seqs = MergeAlternatives.sequences(tokens, dtw)
        assertTrue(
            "single-anchor buffer must stay <= 3 tokens per sequence, got ${seqs.map { it.size }}",
            seqs.all { it.size <= 3 },
        )
        // And the double-letter decode it exists for is unchanged.
        val words = decode(TestData.smallDictionary(), tokens)
        assertEquals("hello", words.firstOrNull())
    }

    @Test
    fun dwellBoundariesNeedCrossStreamActivity() {
        // The measurement's discriminator: all three false-positive
        // dwells sat on single-thumb swipes with no cross-stream activity, while
        // the one true boundary sat on a swipe with a cross-stream tap inside it.
        // So a dwell contributes a cut only when the other stream is active in
        // this gesture - duration alone cannot tell hesitation from boundary
        // (159 ms true vs 158/164/213 ms false).
        val lone = listOf(
            TestData.dwellSwipe("teresa", "te", g, 0, 300, 400, 200, 0f, StreamId.LEFT, markDwell = true),
        )
        assertEquals(
            "a lone dwelling swipe must not gain merge alternatives",
            1, MergeAlternatives.sequences(lone, dtw).size,
        )

        // Same swipe with the other thumb active inside it: the dwell is now an
        // admissible boundary, and interessante must still decode top-1.
        val crossed = listOf(
            TestData.swipe("in", g, t0 = 0, durMs = 150, stream = StreamId.RIGHT),
            TestData.dwellSwipe("teresa", "te", g, 0, 300, 400, 200, 0.25f, StreamId.LEFT, markDwell = true),
            TestData.tap('n', g, 500, StreamId.RIGHT),
        )
        assertTrue(
            "cross-stream buffer must gain alternatives",
            MergeAlternatives.sequences(crossed, dtw).size > 1,
        )
        val trie = Trie.build(
            listOf(
                "interessante" to 5_000, "in" to 9_000, "inn" to 1_500,
                "the" to 12_000, "and" to 15_000,
            ),
        )
        val words = decode(trie, crossed)
        assertTrue("interessante top-1 was ${words.take(3)}", words.firstOrNull() == "interessante")
    }

    @Test
    fun aResumedPieceMayHoldOneLetter() {
        // The phase-1 mechanism at its own level: arc is evidence of letter
        // count only for the part of a piece that is not lead-in travel. A whole
        // gesture over MIN_SWIPE_ARC_KW must still spell two letters; the same
        // arc as a resumed piece may spell one, because where its first letter
        // sits inside it is exactly what the cut does not know.
        val whole = TestData.swipe("ea", g, 0, 200, StreamId.LEFT)
        assertEquals(2, Matcher.buildSegment(whole, g).minLetters)
        val resumed = MergeAlternatives.sequences(
            listOf(
                TestData.swipe("ratcaete", g, 0, 1600, StreamId.LEFT),
                TestData.tap('i', g, 560, StreamId.RIGHT),
                TestData.tap('m', g, 980, StreamId.RIGHT),
                TestData.tap('n', g, 1250, StreamId.RIGHT),
            ),
            dtw,
        ).asSequence()
            .flatMap { it.asSequence() }
            .filterIsInstance<com.kinetica.keyboard.engine.models.SwipeToken>()
            .filter { it.softStart && it.arcLen >= KineticaConstants.MIN_SWIPE_ARC_KW }
            .map { Matcher.buildSegment(it, g) }
            .toList()
        assertTrue("no long resumed piece in the interleave", resumed.isNotEmpty())
        assertTrue(
            "a resumed piece whose arc is mostly lead-in must accept one letter: " +
                resumed.map { "arc=%.2f letterArc=%.2f min=%d".format(it.arcLen, it.letterArcLen, it.minLetters) },
            resumed.any { it.minLetters == 1 },
        )
    }

    @Test
    fun praticamenteNeedsThreeInteriorCuts() {
        // Pre-fix this decoded EMPTY - not one candidate, on device and here.
        val words = decode(italianish(), praticamenteOneSwipe())
        assertTrue("praticamente missing from ${words.take(5)}", words.contains("praticamente"))
        assertEquals("praticamente not top-1 in ${words.take(5)}", "praticamente", words.firstOrNull())
    }

    @Test
    fun praticamenteAcrossTwoLeftSwipes() {
        // The same word when the swiping thumb lifted mid-word: two swipes, one
        // interior tap each. Needs a sequence that cuts BOTH.
        val words = decode(italianish(), praticamenteTwoSwipes())
        assertTrue("praticamente missing from ${words.take(5)}", words.contains("praticamente"))
    }

    @Test
    fun everySwipeCarryingABoundaryIsCut() {
        // Mechanism-level, so a future change cannot regress this to "no
        // candidate happened to need it": the buffer has 4 taps and 2 swipes,
        // and the reading that spells the word is 8 elements (each swipe in two
        // pieces). Nothing shorter can put tap n between the "e" and "te" legs.
        val seqs = MergeAlternatives.sequences(praticamenteTwoSwipes(), dtw)
        assertTrue(
            "no sequence cuts both swipes, sizes ${seqs.map { it.size }}",
            seqs.any { it.size == 8 },
        )
        // And the tail boundary is recognized as one: tap m lands 4 ms before
        // swipe 1's lift, so it follows the whole piece rather than cutting it -
        // treating it as an interior cut is what nulled the interleave on device.
        assertTrue(
            "a tail boundary must not split its swipe into three, sizes ${seqs.map { it.size }}",
            seqs.none { it.size > 8 },
        )
    }

    @Test
    fun praticamenteReachesTheRealItalianDictionary() {
        val p = assetPath("it_wordlist.txt")
        assumeTrue("it wordlist asset not found", Files.exists(p))
        val dict = Files.newBufferedReader(p).use { DictionaryLoader.load(it) }
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        for ((name, tokens) in listOf(
            "one swipe" to praticamenteOneSwipe(),
            "two swipes" to praticamenteTwoSwipes(),
        )) {
            val words = predictor.decode(tokens, emptyList()).map { it.word }
            assertTrue(
                "praticamente unreachable against the full it dictionary ($name): ${words.take(8)}",
                words.contains("praticamente"),
            )
            assertEquals(
                "praticamente not top-1 ($name) in ${words.take(5)}",
                "praticamente", words.firstOrNull(),
            )
        }
    }

    @Test
    fun oneSwipeWithOneBoundaryKeepsItsSequenceSet() {
        // The cross-swipe generator must stay out of every buffer the shipped
        // generators already express: it fires only when two swipes each carry
        // an interior boundary. A single swipe with one tap inside it, and two
        // swipes where only one is interrupted, must both be untouched.
        val oneSwipeOneTap = listOf(
            TestData.swipe("helo", g, 0, 400, StreamId.RIGHT),
            TestData.tap('l', g, 260, StreamId.LEFT),
        )
        assertEquals("hello", decode(TestData.smallDictionary(), oneSwipeOneTap).firstOrNull())
        val onlyOneInterrupted = listOf(
            TestData.swipe("ratca", g, 0, 900, StreamId.LEFT),
            TestData.tap('i', g, 430, StreamId.RIGHT),
            TestData.swipe("ete", g, 1000, 500, StreamId.LEFT),
        )
        val seqs = MergeAlternatives.sequences(onlyOneInterrupted, dtw)
        assertTrue(
            "only one swipe is interrupted, so no sequence may cut both: " +
                "${seqs.map { it.size }}",
            seqs.none { it.size > 4 },
        )
    }

    @Test
    fun cuandoTopOneAgainstRealSpanish() {
        // The hand-weighted dict above proves representability; this proves the
        // word actually wins against the full 49.5k-word dictionary's rivals,
        // which is what the device does.
        val p = assetPath("es_wordlist.txt")
        assumeTrue("es wordlist asset not found", Files.exists(p))
        val dict = Files.newBufferedReader(p).use { DictionaryLoader.load(it) }
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        val words = predictor.decode(cuando(0.25f), emptyList()).map { it.word }
        assertTrue("cuando missing from real-dict decode ${words.take(5)}", words.contains("cuando"))
        assertTrue("cuando not top-1 in ${words.take(5)}", words.firstOrNull() == "cuando")
        assertFalse("decode must not collapse to one candidate", words.size < 2)
    }

    private fun assetPath(name: String): Path {
        val direct = Paths.get("src/main/assets/dictionaries/$name")
        if (Files.exists(direct)) return direct
        return Paths.get("app/src/main/assets/dictionaries/$name")
    }
}
