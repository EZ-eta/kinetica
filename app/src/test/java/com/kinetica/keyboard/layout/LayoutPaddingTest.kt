package com.kinetica.keyboard.layout

import com.kinetica.keyboard.engine.KeyboardGeometry
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The side inset must be geometrically INERT.
 *
 * This is the whole safety argument for the padding setting, and it cannot be
 * made by the golden decodes: TestData builds its geometry synthetically and
 * never goes through KeyboardView or LayoutTransforms, so no golden can see a
 * regression here. The claim is that scaling the key block on both axes leaves
 * every distance measured in kw exactly as it was, and that is what these
 * tests check directly.
 *
 * Shrinking width alone would not be inert. It would cut keyWidthPx while
 * leaving row height, moving row pitch in kw - item 14e's mechanism, measured,
 * and the reason a shorter keyboard is a less forgiving one.
 */
class LayoutPaddingTest {

    private val viewW = 1080f
    private val viewH = 600f

    // FULL-mode projection computed arithmetically: RectF is stubbed in the JVM
    // test runtime, so the fixture must not touch LayoutTransforms.apply.
    private data class L(val code: Int, val x: Float, val y: Float, val w: Float, val h: Float)

    private fun qwerty(): List<L> {
        val out = ArrayList<L>(26)
        fun row(letters: String, y: Float, offset: Float) {
            letters.forEachIndexed { i, c ->
                out.add(L(c - 'a', (offset + i) * 0.1f, y, 0.1f, 0.25f))
            }
        }
        row("qwertyuiop", 0.0f, 0.0f)
        row("asdfghjkl", 0.25f, 0.5f)
        row("zxcvbnm", 0.5f, 1.5f)
        return out
    }

    /** kw-space geometry for the board at [padPx] of side inset. */
    private fun geometryAt(padPx: Float): KeyboardGeometry {
        val rects = ArrayList<FloatArray>()
        val codes = ArrayList<Int>()
        var minW = Float.MAX_VALUE
        var left = Float.MAX_VALUE
        var right = 0f
        for (k in qwerty()) {
            val l = LayoutTransforms.padX(k.x * viewW, padPx, viewW)
            val r = LayoutTransforms.padX((k.x + k.w) * viewW, padPx, viewW)
            val t = LayoutTransforms.padY(k.y * viewH, padPx, viewW)
            val b = LayoutTransforms.padY((k.y + k.h) * viewH, padPx, viewW)
            rects.add(floatArrayOf(l, t, r, b))
            codes.add(k.code)
            if (r - l < minW) minW = r - l
            if (l < left) left = l
            if (r > right) right = r
        }
        return KeyboardGeometry.fromPx(minW, (left + right) / 2f, rects, codes.toIntArray())
    }

    @Test
    fun everyPairwiseKeyDistanceInKwSurvivesTheInset() {
        // keyDist is the kw distance the matcher and the DTW scorer both
        // measure against, so this is the invariant in the decoder's own terms.
        val plain = geometryAt(0f)
        for (padPx in listOf(12f, 40f, 80f, 160f)) {
            val padded = geometryAt(padPx)
            for (a in 0 until 26) {
                for (b in 0 until 26) {
                    val want = plain.keyDist(a, b)
                    val got = padded.keyDist(a, b)
                    assertTrue(
                        "pad=$padPx dist ${'a' + a}->${'a' + b}: $want vs $got",
                        abs(want - got) < 1e-3f,
                    )
                }
            }
        }
    }

    @Test
    fun rowPitchInKwIsUnchanged() {
        // The number item 14e is about. If this moves, the decode's calibration
        // has moved with it and the setting is not free.
        val plain = geometryAt(0f)
        val plainPitch = plain.centerY('a' - 'a') - plain.centerY('q' - 'a')
        assertTrue("fixture has no row pitch", plainPitch > 0.5f)
        for (padPx in listOf(12f, 40f, 80f, 160f)) {
            val padded = geometryAt(padPx)
            val padPitch = padded.centerY('a' - 'a') - padded.centerY('q' - 'a')
            assertTrue(
                "pad=$padPx row pitch moved: $plainPitch vs $padPitch",
                abs(plainPitch - padPitch) < 1e-3f,
            )
        }
    }

    @Test
    fun theBlockIsInsetLeftAndRightAndAnchoredToTheTop() {
        val padPx = 60f
        assertEquals("left edge is not the inset", padPx, LayoutTransforms.padX(0f, padPx, viewW), 1e-3f)
        assertEquals(
            "right edge is not the inset",
            viewW - padPx, LayoutTransforms.padX(viewW, padPx, viewW), 1e-3f,
        )
        assertEquals("block is not anchored to the top", 0f, LayoutTransforms.padY(0f, padPx, viewW), 1e-3f)
        // The freed vertical space shows up below the keys, which is the bottom
        // gap that side padding buys for free.
        assertTrue(
            "no space freed below the block",
            LayoutTransforms.padY(viewH, padPx, viewW) < viewH,
        )
    }

    @Test
    fun zeroInsetIsTheShippedProjectionExactly() {
        for (v in listOf(0f, 1f, 123.5f, viewW)) {
            assertEquals(v, LayoutTransforms.padX(v, 0f, viewW), 0f)
            assertEquals(v, LayoutTransforms.padY(v, 0f, viewW), 0f)
        }
    }

    @Test
    fun theInsetIsBoundedByTheViewAsWellAsByTheDpCap() {
        val density = 3f
        // A narrow board cannot be inset until the keys are unusable, whatever
        // the dp value says.
        val narrow = LayoutTransforms.sidePadPx(LayoutTransforms.MAX_SIDE_PAD_DP, density, 300f)
        assertTrue("inset exceeds the view fraction: $narrow", narrow <= 300f * 0.15f + 1e-3f)
        assertTrue(LayoutTransforms.blockScale(narrow, 300f) >= 0.5f)
        // And it is never negative, whatever it is handed.
        assertEquals(0f, LayoutTransforms.sidePadPx(-10, density, 1080f), 0f)
        assertEquals(1f, LayoutTransforms.blockScale(0f, 1080f), 0f)
        assertEquals(1f, LayoutTransforms.blockScale(10f, 0f), 0f)
    }

    @Test
    fun theMidlineFollowsTheKeyBlockNotTheView() {
        // The one real correctness hazard: the dual-stream split assigns a
        // thumb by comparing its x against this line, so on an inset board a
        // view-centre line would put the divider off the keys' centre.
        for (padPx in listOf(0f, 60f, 160f)) {
            val g = geometryAt(padPx)
            val blockCentre = viewW / 2f
            assertEquals(
                "midline drifted at pad=$padPx",
                blockCentre, g.midlinePx, 1e-3f,
            )
        }
    }
}
