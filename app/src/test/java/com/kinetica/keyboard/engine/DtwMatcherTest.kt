package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.PathPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DtwMatcherTest {

    private val dtw = DtwMatcher()
    private val g = TestData.qwertyGeometry()
    private val n = KineticaConstants.RESAMPLE_N

    private fun idealOf(word: String): FloatArray {
        val letters = word.map { it - 'a' }.toIntArray()
        val out = FloatArray(2 * n)
        assertTrue(dtw.idealPath(letters, 0, letters.size, g, out))
        return out
    }

    @Test
    fun resamplePreservesEndpoints() {
        val path = listOf(
            PathPoint(0f, 0f, 0),
            PathPoint(10f, 0f, 50),
            PathPoint(10f, 5f, 100),
        )
        val out = FloatArray(2 * n)
        dtw.resample(path, out)
        assertEquals(0f, out[0], 1e-4f)
        assertEquals(0f, out[1], 1e-4f)
        assertEquals(10f, out[2 * (n - 1)], 1e-4f)
        assertEquals(5f, out[2 * (n - 1) + 1], 1e-4f)
    }

    @Test
    fun resampleIsUniformOnStraightPaths() {
        // Chord spacing equals arc spacing only without corners: assert
        // uniformity on a straight line (a corner's chord is legitimately
        // shorter than its arc).
        val path = listOf(
            PathPoint(0f, 0f, 0),
            PathPoint(3f, 4f, 40),
            PathPoint(6f, 8f, 80),
            PathPoint(15f, 20f, 200),
        )
        val out = FloatArray(2 * n)
        dtw.resample(path, out)
        val expected = 25f / (n - 1)
        for (k in 1 until n) {
            val dx = out[2 * k] - out[2 * (k - 1)]
            val dy = out[2 * k + 1] - out[2 * (k - 1) + 1]
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            assertTrue("gap $k = $d", abs(d - expected) < 0.02f)
        }
    }

    @Test
    fun identicalPathsHaveZeroDistance() {
        val a = idealOf("something")
        val d = dtw.distanceAccum(a, a, Float.POSITIVE_INFINITY)
        assertEquals(0f, d, 1e-3f)
    }

    @Test
    fun differentWordsAreFartherThanSameWord() {
        val obs = idealOf("something")
        val same = dtw.distanceAccum(obs, idealOf("something"), Float.POSITIVE_INFINITY)
        val other = dtw.distanceAccum(obs, idealOf("sometimes"), Float.POSITIVE_INFINITY)
        assertTrue(other > same + 1f)
    }

    @Test
    fun doubledLetterSharesIdealPath() {
        // "hello" and "helo" must produce identical ideal paths: consecutive
        // duplicate keys are deduped, the dictionary does the disambiguation.
        val a = idealOf("hello")
        val b = idealOf("helo")
        for (i in a.indices) {
            assertEquals(a[i], b[i], 1e-5f)
        }
    }

    @Test
    fun earlyAbandonReturnsInfinity() {
        val obs = idealOf("something")
        val far = idealOf("a")
        val d = dtw.distanceAccum(obs, far, 0.5f)
        assertTrue(d == Float.POSITIVE_INFINITY)
    }

    @Test
    fun apostropheSkippedInIdealPath() {
        val dontCodes = "don't".map { Alphabet.codeOf(it) }.toIntArray()
        val out1 = FloatArray(2 * n)
        assertTrue(dtw.idealPath(dontCodes, 0, dontCodes.size, g, out1))
        val out2 = idealOf("dont")
        for (i in out1.indices) {
            assertEquals(out2[i], out1[i], 1e-5f)
        }
    }
}
