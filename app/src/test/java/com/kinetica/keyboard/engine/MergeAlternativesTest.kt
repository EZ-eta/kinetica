package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.SwipeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class MergeAlternativesTest {

    private val g = TestData.qwertyGeometry()
    private val dtw = DtwMatcher()
    private val predictor = WordPredictor(TestData.smallDictionary(), BigramTable.EMPTY, g)

    @Test
    fun singleStreamProducesOnlyPrimarySequence() {
        val tokens = listOf(
            TestData.tap('s', g, 0, StreamId.LEFT),
            TestData.swipe("om", g, 200, 200, StreamId.LEFT),
        )
        val seqs = MergeAlternatives.sequences(tokens, dtw)
        assertEquals(1, seqs.size)
    }

    @Test
    fun splitProducesHeadTrimmedSecondHalf() {
        val swipe = TestData.swipe("helo", g, 0, 400)
        // The l key is reached around 2/3 of the path (20 of 30 points).
        val tCut = swipe.rawPath[20].t
        val halves = MergeAlternatives.splitSwipe(swipe, tCut, dtw)
        assertNotNull(halves)
        val (h1, h2) = halves!!
        assertTrue(h1.arcLen >= KineticaConstants.MIN_SPLIT_HALF_ARC_KW)
        assertTrue(h2.arcLen >= KineticaConstants.MIN_SPLIT_HALF_ARC_KW)
        // The halves carry the matcher relaxations for their cut-adjacent ends
        // (the resume fix treated the second half's start; the reversal fix
        // the first half's end).
        assertTrue("first half must be softEnd", h1.softEnd)
        assertTrue("second half must be softStart", h2.softStart)
        // Second half must resume past the trim radius from the cut point.
        val cutPt = swipe.rawPath[20]
        val resumePt = h2.rawPath.first()
        val dx = resumePt.x - cutPt.x
        val dy = resumePt.y - cutPt.y
        assertTrue(
            kotlin.math.sqrt(dx * dx + dy * dy) >=
                KineticaConstants.SPLIT_HEAD_TRIM_KW - 1e-3f,
        )
        assertTrue(h1.tEnd <= h2.tStart)
    }

    @Test
    fun splitRejectsCutsNearTheEnds() {
        val swipe = TestData.swipe("helo", g, 0, 400)
        assertNull(MergeAlternatives.splitSwipe(swipe, swipe.rawPath[0].t, dtw))
    }

    @Test
    fun crossThumbTapInsertsDoubleLetter() {
        // Right thumb swipes h-e-l-o; left thumb taps l while the swipe is on
        // the l key. The split alternative decodes hel + l + o = "hello".
        val swipe = TestData.swipe("helo", g, 0, 400, StreamId.RIGHT)
        val tCut = swipe.rawPath[20].t
        val tokens = listOf(swipe, TestData.tap('l', g, tCut, StreamId.LEFT))
        val result = predictor.decode(tokens, emptyList())
        assertTrue(result.isNotEmpty())
        assertEquals("hello", result[0].word)
    }

    @Test
    fun splitAtRestResumesAtNextRealLetter() {
        // Left swipes t-e-r-e-s-a, rests on A, then resumes A->T->E. A cut in
        // the rest must resume the second half at T (the first real resumed
        // letter, where the path turns away from A) - not ~0.6kw from the rest
        // position A, which is what the old fixed head trim did.
        val swipe = TestData.dwellSwipe("teresa", "te", g, 0, 300, 400, 200, 0f, StreamId.LEFT)
        val halves = MergeAlternatives.splitSwipe(swipe, 500L, dtw) // 500ms is inside the rest
        assertNotNull(halves)
        val (_, h2) = halves!!
        assertTrue("second half must be softStart", h2.softStart)
        val start = h2.rawPath.first()
        val tCode = 't' - 'a'
        val aCode = 'a' - 'a'
        val dT = hypot((start.x - g.centerX(tCode)).toDouble(), (start.y - g.centerY(tCode)).toDouble())
        val dA = hypot((start.x - g.centerX(aCode)).toDouble(), (start.y - g.centerY(aCode)).toDouble())
        assertTrue("h2 starts nearer A($dA) than T($dT) at $start", dT < dA)
        assertTrue("h2 must start within R_ENDPOINT of T (dT=$dT)", dT <= KineticaConstants.R_ENDPOINT_KW)
    }

    @Test
    fun swipeAroundSwipeSplitsOuterAroundInner() {
        // Left swipes s-e, holds on E, right swipes m-p during the hold, left
        // resumes r-e. sequences() must generate the [se][mp][re] interleave
        // that the tap-only generator could never represent.
        val left = TestData.dwellSwipe("se", "re", g, 0, 200, 400, 200, 0f, StreamId.LEFT)
        val right = TestData.swipe("mp", g, 300, 200, StreamId.RIGHT)
        val seqs = MergeAlternatives.sequences(listOf(left, right), dtw)
        val interleave = seqs.firstOrNull { it.size == 3 }
        assertNotNull("no swipe-around interleave in sizes ${seqs.map { it.size }}", interleave)
        val s = interleave!!.map { it as SwipeToken }
        assertEquals(StreamId.LEFT, s[0].streamId)
        assertEquals(StreamId.RIGHT, s[1].streamId)
        assertEquals(StreamId.LEFT, s[2].streamId)
        assertTrue("resumed half must be softStart", s[2].softStart)
    }

    /**
     * The budget accounting, on the buffer that made it necessary.
     *
     * `provando` typed with both thumbs, verbatim from the 2026-08-28 capture: the
     * right thumb sweeps p-o-i-j, lifts, sweeps b-n-j-k-o, while the left taps r
     * into the first sweep and d into the second. Both cross-stream taps land past
     * SPLIT_MARGIN_MS, so the mid-swipe split is obliged to examine both, and in
     * the capture it examined neither - the cap was spent before it ran, and a
     * generator that was never reached looked exactly like a generator that
     * rejected every cut.
     *
     * This asserts the accounting rather than a word, because the reading IS built
     * here and is then refused by the segment gates (item 41): the piece that must
     * spell the second `o` overshoots to `i` and `j`, so `isEnd` is [h, j, k], and
     * the final piece carries letterArc 2.00 kw against MIN_SWIPE_ARC_KW 1.8, so
     * minLetters is 2 where the word needs one letter.
     */
    @Test
    fun theBudgetAccountingNamesTheGeneratorTheCapRefused() {
        val line =
            "decode in[it]: swipe[RIGHT,t=1624452..1624768,keys=p@0-131,o@131-251,i@251-306,j@306-316] " +
                "tap[r,LEFT,t=1624612] tap[v,LEFT,t=1624900] tap[a,LEFT,t=1625009] " +
                "swipe[RIGHT,t=1625179..1625571,keys=b@0-115,n@115-149,j@149-181,k@181-250,o@250-392] " +
                "tap[d,LEFT,t=1625306] ctx=[rad, rad]"
        val lines = ArrayList<String>()
        DecodeTrace.sink = { lines.add(it) }
        try {
            MergeAlternatives.sequences(TraceReplay.tokens(line, g), dtw)
        } finally {
            DecodeTrace.sink = null
        }
        val seqs = lines.firstOrNull { it.trimStart().startsWith("seqs ") }
        assertNotNull("no budget accounting was traced: $lines", seqs)
        // taken/produced per generator, and a non-zero dropped count: this buffer
        // asks for more sequences than MAX_ALT_SEQUENCES allows, which is the fact
        // the trace could not previously express.
        for (name in listOf("anchorIL", "crossIL", "orderSwap", "tapSplit", "swipeAround")) {
            assertTrue("$name is missing from the accounting: $seqs", seqs!!.contains("$name="))
        }
        val dropped = Regex("""dropped=(\d+)""").find(seqs!!)?.groupValues?.get(1)?.toInt()
        assertNotNull("dropped is not reported: $seqs", dropped)
        assertTrue("this buffer overspends the budget; dropped was $dropped", dropped!! > 0)
        // And the generator that can cut both swipes is one of the ones it refused.
        val tapSplit = Regex("""tapSplit=(\d+)/(\d+)""").find(seqs)!!.groupValues
        assertTrue(
            "the mid-swipe split was not starved on this buffer: $seqs",
            tapSplit[2].toInt() > tapSplit[1].toInt(),
        )
    }
}
