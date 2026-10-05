package com.kinetica.keyboard.layout

import com.kinetica.keyboard.engine.KeyboardGeometry
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The middle row spread toward the edges: keys keep touching, stay on the board
 * and in their split half, and nothing outside the row moves. The decoder reads the same rects,
 * so the kw distances are checked here too.
 */
class HomeRowSpreadTest {

    private val viewW = 1080f
    private val viewH = 600f

    /** QWERTY with the asset's own literals, so the floats match what the loader produces. */
    private fun qwerty(): KeyboardLayout {
        val keys = ArrayList<Key>()
        fun row(letters: String, y: Float, xs: List<Float>) {
            letters.forEachIndexed { i, c ->
                keys.add(Key(c.toString(), KeyType.CHAR, c.toString(), c.toString(), xs[i], y, 0.10f, 0.25f))
            }
        }
        row("qwertyuiop", 0.00f, listOf(0.00f, 0.10f, 0.20f, 0.30f, 0.40f, 0.50f, 0.60f, 0.70f, 0.80f, 0.90f))
        row("asdfghjkl", 0.25f, listOf(0.05f, 0.15f, 0.25f, 0.35f, 0.45f, 0.55f, 0.65f, 0.75f, 0.85f))
        row("zxcvbnm", 0.50f, listOf(0.15f, 0.25f, 0.35f, 0.45f, 0.55f, 0.65f, 0.75f))
        keys.add(Key("shift", KeyType.SHIFT, "⇧", "", 0.00f, 0.50f, 0.15f, 0.25f))
        keys.add(Key("space", KeyType.SPACE, " ", " ", 0.25f, 0.75f, 0.50f, 0.25f))
        return KeyboardLayout("qwerty", "en_US", keys)
    }

    private fun homeRow(layout: KeyboardLayout): List<Key> =
        layout.keys.filter { it.isLetter && abs(it.y - 0.25f) < 0.01f }.sortedBy { it.x }

    private val amounts = (1..20).map { it / 20f }

    @Test
    fun offIsTheSameLayout() {
        val base = qwerty()
        assertSame(base, LayoutMutations.withHomeRowSpread(base, 0f))
        assertSame(base, LayoutMutations.withHomeRowSpread(base, -0.5f))
    }

    @Test
    fun theRowTouchesAndStaysOnTheBoard() {
        for (apostrophe in listOf(false, true)) {
            val base = qwerty().let { if (apostrophe) LayoutMutations.withApostropheKey(it) else it }
            val limit = if (apostrophe) 0.95f else 1f
            for (a in amounts) {
                val row = homeRow(LayoutMutations.withHomeRowSpread(base, a))
                assertEquals(9, row.size)
                for (i in 0 until row.size - 1) {
                    assertEquals("gap $a ${row[i].id}", row[i].x + row[i].w, row[i + 1].x, 1e-5f)
                }
                assertTrue("left $a", row.first().x >= -1e-5f)
                assertTrue("right $a ${row.last().x + row.last().w}", row.last().x + row.last().w <= limit + 1e-5f)
            }
            val full = homeRow(LayoutMutations.withHomeRowSpread(base, 1f))
            assertEquals(0f, full.first().x, 1e-5f)
            assertEquals(limit, full.last().x + full.last().w, 1e-5f)
        }
    }

    @Test
    fun nothingOutsideTheRowMoves() {
        for (apostrophe in listOf(false, true)) {
            val base = qwerty().let { if (apostrophe) LayoutMutations.withApostropheKey(it) else it }
            val spread = LayoutMutations.withHomeRowSpread(base, 1f)
            val rowIds = homeRow(base).map { it.id }.toSet()
            for (k in base.keys.filter { it.id !in rowIds }) {
                assertEquals(k, spread.keys.single { it.id == k.id })
            }
        }
    }

    @Test
    fun everyKeyKeepsItsSplitHalf() {
        // QWERTY's g is centred on 0.5 exactly, where both split rules divide: rounding in the
        // spread must not carry it across either of them.
        for (apostrophe in listOf(false, true)) {
            val base = qwerty().let { if (apostrophe) LayoutMutations.withApostropheKey(it) else it }
            for (a in amounts) {
                val spread = LayoutMutations.withHomeRowSpread(base, a)
                for (k in homeRow(base)) {
                    val s = spread.keys.single { it.id == k.id }
                    assertEquals("landscape ${k.id} $a", LayoutTransforms.inLeftHalf(k), LayoutTransforms.inLeftHalf(s))
                    assertEquals("split ${k.id} $a", k.x + k.w / 2f < 0.5f, s.x + s.w / 2f < 0.5f)
                }
            }
        }
    }

    /** kw-space letter geometry of [layout], FULL mode by arithmetic, as NumberRowTest. */
    private fun geometry(layout: KeyboardLayout): KeyboardGeometry {
        val rects = ArrayList<FloatArray>()
        val codes = ArrayList<Int>()
        var minW = Float.MAX_VALUE
        for (k in layout.keys) {
            if (!k.isLetter) continue
            val r = floatArrayOf(k.x * viewW, k.y * viewH, (k.x + k.w) * viewW, (k.y + k.h) * viewH)
            rects.add(r)
            codes.add(k.letterCode)
            if (r[2] - r[0] < minW) minW = r[2] - r[0]
        }
        return KeyboardGeometry.fromPx(minW, viewW / 2f, rects, codes.toIntArray())
    }

    @Test
    fun onlyTheMiddleRowsDistancesGrow() {
        val base = geometry(qwerty())
        val spread = geometry(LayoutMutations.withHomeRowSpread(qwerty(), 1f))
        val scale = 1f / 0.9f
        fun code(c: Char) = c - 'a'
        // The unit is a top-row key, so the top and bottom rows read the same in kw.
        for (p in listOf("qp", "qw", "zm", "xc")) {
            assertEquals(p, base.keyDist(code(p[0]), code(p[1])), spread.keyDist(code(p[0]), code(p[1])), 1e-3f)
        }
        for (p in listOf("al", "as", "gh", "dk")) {
            assertEquals(p, base.keyDist(code(p[0]), code(p[1])) * scale, spread.keyDist(code(p[0]), code(p[1])), 1e-3f)
        }
    }

    @Test
    fun aRowAlreadyFlushIsLeftAlone() {
        val keys = "azertyuiopqsdfghjklm".mapIndexed { i, c ->
            val top = i < 10
            Key(c.toString(), KeyType.CHAR, c.toString(), c.toString(), (i % 10) * 0.1f, if (top) 0f else 0.25f, 0.1f, 0.25f)
        }
        val azerty = KeyboardLayout("azerty_fr", "fr_FR", keys)
        assertSame(azerty, LayoutMutations.withHomeRowSpread(azerty, 1f))
    }
}
