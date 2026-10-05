package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.PathPoint
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.SwipeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The time a letter is given on a piece, which a hand-over between thumbs is ordered by. */
class LetterTimeTest {

    private val g = TestData.qwertyGeometry()

    @Test
    fun resampleTimesRunFromTheFirstSampleToTheLastInOrder() {
        val path = listOf(PathPoint(0f, 0f, 1_000), PathPoint(3f, 0f, 1_300), PathPoint(3f, 3f, 1_900))
        val out = LongArray(KineticaConstants.RESAMPLE_N)
        DtwMatcher.resampleTimes(path, out)
        assertEquals(1_000L, out.first())
        assertEquals(1_900L, out.last())
        for (k in 1 until out.size) assertTrue("times must not run backwards at $k", out[k] >= out[k - 1])
        // Halfway along the arc is the corner, reached at 1 300 ms.
        val mid = (KineticaConstants.RESAMPLE_N - 1) / 2
        assertTrue(out[mid] in 1_250L..1_350L)
    }

    @Test
    fun aPathThatNeverMovedSpreadsItsTimesEvenly() {
        val path = listOf(PathPoint(1f, 1f, 500), PathPoint(1f, 1f, 810))
        val out = LongArray(KineticaConstants.RESAMPLE_N)
        DtwMatcher.resampleTimes(path, out)
        assertEquals(500L, out.first())
        assertEquals(810L, out.last())
    }

    @Test
    fun aHeldKeyTimesItsLetterAtTheContactsExit() {
        val swipe = TestData.sloppySwipe("world", g, t0 = 0, durMs = 500, stream = StreamId.RIGHT)
        val seg = Matcher.buildSegment(swipe, g)
        assertEquals(StreamId.RIGHT, seg.stream)
        val o = 'o' - 'a'
        val contact = swipe.keyContacts.first { it.code == o }
        val idx = seg.passAtOrAfter(o, 0)
        assertEquals(
            (contact.tEnter + (contact.tExit - contact.tEnter) * KineticaConstants.HANDOVER_CONTACT_FRACTION).toLong(),
            seg.letterTime(o, idx),
        )
    }

    @Test
    fun aKeyThePieceNeverHeldIsTimedByItsSample() {
        val swipe = TestData.sloppySwipe("world", g, t0 = 0, durMs = 500, stream = StreamId.RIGHT)
        val seg = Matcher.buildSegment(swipe, g)
        val q = 'q' - 'a'
        assertEquals(seg.sampleT[5], seg.letterTime(q, 5))
    }

    @Test
    fun aContactStraddlingACutBelongsToEachHalfOnlyForItsOwnPart() {
        val swipe = TestData.sloppySwipe("world", g, t0 = 0, durMs = 500, stream = StreamId.LEFT)
        val cut = swipe.rawPath[swipe.rawPath.size / 2].t
        val halves = MergeAlternatives.splitSwipe(swipe, cut, DtwMatcher()) ?: return
        for (half in listOf(halves.first, halves.second)) {
            val seg = Matcher.buildSegment(half, g)
            val first = half.rawPath.first().t
            val last = half.rawPath.last().t
            for (code in 0 until Alphabet.LETTERS) {
                for (idx in 0 until KineticaConstants.RESAMPLE_N) {
                    val t = seg.letterTime(code, idx)
                    assertTrue("letter time $t outside the half's span $first..$last", t in first..last)
                }
            }
        }
        assertTrue(halves.first is SwipeToken)
    }
}
