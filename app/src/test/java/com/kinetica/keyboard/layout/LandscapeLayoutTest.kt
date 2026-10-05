package com.kinetica.keyboard.layout

import com.kinetica.keyboard.engine.KeyboardGeometry
import com.kinetica.keyboard.engine.KineticaConstants
import com.kinetica.keyboard.settings.Prefs
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The landscape block in the decoder's own terms.
 *
 * No golden can see this: `TestData` builds its geometry directly and never goes through
 * `LayoutTransforms`, the same gap `LayoutPaddingTest` closes for the side inset. The claims are
 * that the centred block holds row pitch at the goldens' 1.5 kw, that the split's key width comes
 * from its gap, that splitting moves no distance inside a half, and that each half's
 * keys sit on its own thumb's side of the midline.
 */
class LandscapeLayoutTest {

    // A Pixel 7 held sideways at 45%, in px at density 2.625: 914 x 175 dp.
    private val viewW = 2400f
    private val viewH = 460f
    private val density = 2.625f

    private fun qwerty(): List<Key> {
        val out = ArrayList<Key>(26)
        fun row(letters: String, y: Float, offset: Float) {
            letters.forEachIndexed { i, c ->
                out.add(Key("$c", KeyType.CHAR, "$c", "$c", (offset + i) * 0.1f, y, 0.1f, 0.25f))
            }
        }
        row("qwertyuiop", 0.0f, 0.0f)
        row("asdfghjkl", 0.25f, 0.5f)
        row("zxcvbnm", 0.5f, 1.5f)
        return out
    }

    private fun blockPx(h: Float = viewH, pad: Float = 0f, minKeyDp: Float = LayoutTransforms.LANDSCAPE_MIN_KEY_DP): Float =
        LayoutTransforms.landscapeBlockPx(viewW, h, pad, 0.1f, 0.25f, minKeyDp * density)

    private fun splitPx(gap: Float = 0.5f, pad: Float = 0f): Float =
        LayoutTransforms.splitBlockPx(viewW, pad, gap, 0.1f, LayoutTransforms.LANDSCAPE_MIN_KEY_DP * density)

    private fun geometry(
        arrangement: LandscapeArrangement,
        h: Float = viewH,
        pad: Float = 0f,
        b: Float = if (arrangement == LandscapeArrangement.SPLIT) splitPx(pad = pad) else blockPx(h, pad),
    ): KeyboardGeometry {
        val rects = ArrayList<FloatArray>()
        val codes = ArrayList<Int>()
        var minW = Float.MAX_VALUE
        var left = Float.MAX_VALUE
        var right = 0f
        for (k in qwerty()) {
            val half = LayoutTransforms.inLeftHalf(k)
            val l = LayoutTransforms.landscapeX(k.x, half, arrangement, viewW, b, pad)
            val r = LayoutTransforms.landscapeX(k.x + k.w, half, arrangement, viewW, b, pad)
            rects.add(floatArrayOf(l, k.y * h, r, (k.y + k.h) * h))
            codes.add(k.output[0] - 'a')
            if (r - l < minW) minW = r - l
            if (l < left) left = l
            if (r > right) right = r
        }
        return KeyboardGeometry.fromPx(minW, (left + right) / 2f, rects, codes.toIntArray())
    }

    private fun pitch(g: KeyboardGeometry): Float = g.centerY('a' - 'a') - g.centerY('q' - 'a')

    @Test
    fun theCentredBlockHoldsThePitchEveryGoldenDecodesAt() {
        val centred = geometry(LandscapeArrangement.CENTERED)
        assertEquals(KineticaConstants.LANDSCAPE_ROW_PITCH_KW, pitch(centred), 1e-3f)
    }

    @Test
    fun theSplitGapSetsTheKeyWidth() {
        // 50% of a Pixel 7 held sideways: 46 dp keys, where the measured decode plateaus.
        assertEquals(viewW / 2f / 10f / density, splitPx(0.5f) * 0.1f / density, 1e-2f)
        // No gap is the whole width; a gap too wide stops at the 24 dp key.
        assertEquals(viewW, splitPx(0f), 1e-3f)
        assertEquals(LayoutTransforms.LANDSCAPE_MIN_KEY_DP * density * 10f, splitPx(0.95f), 1e-2f)
        // A smaller gap is always wider keys.
        assertTrue(splitPx(0.3f) > splitPx(0.5f) && splitPx(0.5f) > splitPx(0.7f))
        assertTrue(geometry(LandscapeArrangement.SPLIT).keyWidthPx > geometry(LandscapeArrangement.CENTERED).keyWidthPx)
    }

    @Test
    fun splittingMovesNoDistanceInsideAHalf() {
        // Against a centred block of the same width: tearing it moves nothing inside a half.
        val centred = geometry(LandscapeArrangement.CENTERED, b = splitPx())
        val split = geometry(LandscapeArrangement.SPLIT)
        val keys = qwerty()
        for (a in keys) {
            for (b in keys) {
                val ca = a.output[0] - 'a'
                val cb = b.output[0] - 'a'
                val want = centred.keyDist(ca, cb)
                val got = split.keyDist(ca, cb)
                if (LayoutTransforms.inLeftHalf(a) == LayoutTransforms.inLeftHalf(b)) {
                    assertEquals("${a.id}->${b.id}", want, got, 1e-3f)
                } else {
                    assertTrue("${a.id}->${b.id} crosses the gap: $want vs $got", got > want)
                }
            }
        }
    }

    @Test
    fun eachHalfSitsOnItsOwnThumbsSideOfTheMidline() {
        val g = geometry(LandscapeArrangement.SPLIT)
        assertEquals(viewW / 2f, g.midlinePx, 1e-3f)
        for (k in qwerty()) {
            val x = g.centerX(k.output[0] - 'a') * g.keyWidthPx
            assertEquals("${k.id} at $x", LayoutTransforms.inLeftHalf(k), x < g.midlinePx)
        }
        // Touch typing's own split: g, v and b's neighbours land where a thumb reaches them.
        assertTrue(LayoutTransforms.inLeftHalf(qwerty().first { it.id == "g" }))
        assertTrue(LayoutTransforms.inLeftHalf(qwerty().first { it.id == "v" }))
    }

    @Test
    fun aShortBoardWidensToTheKeyFloorRatherThanShrinkingTheKeys() {
        val shortH = 200f
        val g = geometry(LandscapeArrangement.CENTERED, h = shortH)
        assertEquals(LayoutTransforms.LANDSCAPE_MIN_KEY_DP * density, g.keyWidthPx, 1e-2f)
        assertTrue("pitch ${pitch(g)}", pitch(g) < KineticaConstants.LANDSCAPE_ROW_PITCH_KW)
    }

    @Test
    fun theBlockNeverOutgrowsTheViewLessItsInsets() {
        val pad = 60f
        assertEquals(viewW - 2f * pad, blockPx(h = 5000f, pad = pad), 1e-3f)
        assertEquals(viewW - 2f * pad, splitPx(0f, pad), 1e-3f)
        val split = geometry(LandscapeArrangement.SPLIT, pad = pad)
        val left = LayoutTransforms.landscapeX(0f, true, LandscapeArrangement.SPLIT, viewW, splitPx(pad = pad), pad)
        val right = LayoutTransforms.landscapeX(1f, false, LandscapeArrangement.SPLIT, viewW, splitPx(pad = pad), pad)
        assertEquals(pad, left, 1e-3f)
        assertEquals(viewW - pad, right, 1e-3f)
        assertEquals(viewW / 2f, split.midlinePx, 1e-3f)
    }

    @Test
    fun stretchIsTheFullWidthBoard() {
        for (x in listOf(0f, 0.25f, 0.5f, 1f)) {
            for (left in listOf(true, false)) {
                assertEquals(x * viewW, LayoutTransforms.landscapeX(x, left, LandscapeArrangement.STRETCH, viewW, 123f, 40f), 0f)
            }
        }
    }

    private fun res(rel: String): String {
        val direct = Paths.get("src/main/res/$rel")
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/src/main/res/$rel")
        assumeTrue("$rel not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }

    private fun items(xml: String, name: String): List<String> {
        val block = Regex("""<string-array name="$name"[^>]*>(.*?)</string-array>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1) ?: return emptyList()
        return Regex("""<item>([^<]*)</item>""").findAll(block).map { it.groupValues[1] }.toList()
    }

    private fun default(xml: String, key: String): String? =
        Regex("""android:key="$key"[^>]*?android:defaultValue="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)

    @Test
    fun theSettingsOfferEveryArrangementAndShipTheCodeDefaults() {
        val arrays = res("values/arrays.xml")
        val values = items(arrays, "landscape_arrangement_values")
        assertEquals(LandscapeArrangement.entries.toSet(), values.map { LandscapeArrangement.fromPref(it) }.toSet())
        assertEquals(values.size, items(arrays, "landscape_arrangement_entries").size)
        val prefs = res("xml/keyboard_prefs.xml")
        assertEquals(Prefs.DEFAULT_LANDSCAPE_ARRANGEMENT, default(prefs, Prefs.LANDSCAPE_ARRANGEMENT))
        assertEquals("${Prefs.DEFAULT_HEIGHT_PCT_LANDSCAPE}", default(prefs, Prefs.KEYBOARD_HEIGHT_PCT_LANDSCAPE))
        assertEquals("${Prefs.DEFAULT_LANDSCAPE_SPLIT_GAP_PCT}", default(prefs, Prefs.LANDSCAPE_SPLIT_GAP_PCT))
    }
}
