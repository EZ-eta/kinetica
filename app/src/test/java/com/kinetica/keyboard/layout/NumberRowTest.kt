package com.kinetica.keyboard.layout

import com.kinetica.keyboard.engine.GestureEngine
import com.kinetica.keyboard.engine.KeyboardGeometry
import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.keys.EdgeSwipeBindings
import com.kinetica.keyboard.keys.EdgeSwipeDetector
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The numbers row leaves the letters as they were: the board grows a row, so every
 * distance in key widths is unchanged. TestData never goes through a layout, so
 * no golden can see this and it is checked here.
 */
class NumberRowTest {

    private val viewW = 1080f
    private val viewH = 600f

    private fun qwerty(): KeyboardLayout {
        val keys = ArrayList<Key>()
        fun row(letters: String, y: Float, offset: Float) {
            letters.forEachIndexed { i, c ->
                keys.add(Key(c.toString(), KeyType.CHAR, c.toString(), c.toString(), (offset + i) * 0.1f, y, 0.1f, 0.25f))
            }
        }
        row("qwertyuiop", 0.0f, 0.0f)
        row("asdfghjkl", 0.25f, 0.5f)
        row("zxcvbnm", 0.5f, 1.5f)
        keys.add(Key("space", KeyType.SPACE, " ", " ", 0.2f, 0.75f, 0.6f, 0.25f))
        return KeyboardLayout("qwerty", "en_US", keys)
    }

    /** kw-space letter geometry of [layout] drawn [heightPx] tall, FULL mode by arithmetic. */
    private fun geometry(layout: KeyboardLayout, heightPx: Float): KeyboardGeometry {
        val rects = ArrayList<FloatArray>()
        val codes = ArrayList<Int>()
        var minW = Float.MAX_VALUE
        for (k in layout.keys) {
            if (!k.isLetter) continue
            val r = floatArrayOf(k.x * viewW, k.y * heightPx, (k.x + k.w) * viewW, (k.y + k.h) * heightPx)
            rects.add(r)
            codes.add(k.output[0] - 'a')
            if (r[2] - r[0] < minW) minW = r[2] - r[0]
        }
        return KeyboardGeometry.fromPx(minW, viewW / 2f, rects, codes.toIntArray())
    }

    @Test
    fun everyLetterDistanceInKwSurvivesTheRowWhenTheBoardGrows() {
        val base = geometry(qwerty(), viewH)
        val grown = geometry(LayoutMutations.withNumberRow(qwerty()), viewH * LayoutMutations.NUMBER_ROW_GROWTH)
        for (a in 0 until 26) for (b in 0 until 26) {
            assertTrue(
                "${'a' + a}-${'a' + b}: ${base.keyDist(a, b)} vs ${grown.keyDist(a, b)}",
                abs(base.keyDist(a, b) - grown.keyDist(a, b)) < 1e-3f,
            )
        }
    }

    @Test
    fun theDigitsAreOneTapOnlyRowAboveEveryOtherKey() {
        val l = LayoutMutations.withNumberRow(qwerty())
        val digits = l.keys.filter { it.id.startsWith("num_") }
        assertEquals("1234567890", digits.joinToString("") { it.output })
        assertTrue(digits.all { it.y == 0f && !it.isLetter && it.type == KeyType.CHAR })
        val rowBottom = digits.first().y + digits.first().h
        assertTrue(l.keys.filter { !it.id.startsWith("num_") }.all { it.y >= rowBottom - 1e-6f })
        assertTrue(l.keys.all { it.y + it.h <= 1f + 1e-6f })
        assertEquals("every letter is still there", 26, l.keys.count { it.isLetter })
    }

    @Test
    fun aSloppyGlideThroughTheDigitsIsTwoLettersAndNoFlick() {
        // From q's right edge up into 1 and back down just past q's sticky bounds into w: the
        // digit is no contact, the word gets q and w, and only the returned glide refuses q's flick.
        val l = LayoutMutations.withNumberRow(LayoutMutations.withNumberRowSymbols(qwerty()))
        val h = viewH * LayoutMutations.NUMBER_ROW_GROWTH
        val g = geometry(l, h)
        val tokens = ArrayList<InputToken>()
        val engine = GestureEngine(object : GestureEngine.Listener {
            override fun onTokenFinalized(token: InputToken) { tokens.add(token) }
            override fun onKeyTransition(streamId: StreamId, code: Int) {}
            override fun onAllPointersUp() {}
        }).also { it.setGeometry(g, 12f) }
        val q = l.keys.first { it.id == "q" }
        val one = l.keys.first { it.id == "num_1" }
        val x = g.centerX('q' - 'a') * g.keyWidthPx + 52f
        val y = g.centerY('q' - 'a') * g.keyWidthPx + 4f
        val peakX = x - 3f
        val peakY = (one.y + one.h * .6f) * h
        assertFalse("a digit starts no stream", engine.onPointerDown(0, peakX, peakY, 0))
        assertTrue(engine.onPointerDown(0, x, y, 10))
        engine.onPointerMove(0, peakX, peakY, 70)
        assertEquals(1, engine.contactCount(0))
        val endX = g.centerX('w' - 'a') * g.keyWidthPx - 31f
        val endY = y - 3f
        engine.onPointerMove(0, endX, endY, 130)
        assertEquals(2, engine.contactCount(0))
        val bindings = EdgeSwipeBindings.withImplicitAlternates(l, EdgeSwipeBindings(emptyList()))
        val flick = requireNotNull(q.hintChar)
        assertEquals(flick, EdgeSwipeDetector.detect(
            q, endX - x, endY - y, peakX - x, peakY - y, 1f, bindings,
            engine.contactCount(0), returnedFromNumberRow = false,
        ))
        assertNull(EdgeSwipeDetector.detect(
            q, endX - x, endY - y, peakX - x, peakY - y, 1f, bindings,
            engine.contactCount(0), returnedFromNumberRow = true,
        ))
        // The view feeds the lift sample before detection; the engine's repeat of it is dropped.
        engine.onPointerUp(0, endX, endY, 130)
        assertEquals(listOf('q' - 'a', 'w' - 'a'), (tokens.single() as SwipeToken).keyContacts.map { it.code })
    }

    @Test
    fun theBoardGrowsByExactlyOneOfItsOwnRows() {
        val l = LayoutMutations.withNumberRow(qwerty())
        val q = l.keys.first { it.id == "q" }
        val one = l.keys.first { it.id == "num_1" }
        assertEquals(q.h, one.h, 1e-6f)
        assertEquals(0.25f * viewH, q.h * viewH * LayoutMutations.NUMBER_ROW_GROWTH, 1e-3f)
    }

    @Test
    fun withTheRowTheTopKeysOfferSymbolsNotDigits() {
        val base = qwerty()
        val keys = base.keys.map {
            when (it.id) {
                "q" -> it.copy(alternates = listOf("1"))
                "e" -> it.copy(alternates = listOf("è", "é", "3"))
                "a" -> it.copy(alternates = listOf("à"))
                else -> it
            }
        }
        val l = LayoutMutations.withNumberRowSymbols(base.copy(keys = keys))
        assertEquals(listOf("%"), l.keys.first { it.id == "q" }.alternates)
        assertEquals(listOf("|", "è", "é"), l.keys.first { it.id == "e" }.alternates)
        assertEquals("%", l.keys.first { it.id == "q" }.hintChar)
        assertEquals("}", l.keys.first { it.id == "p" }.alternates.first())
        // Only the top row.
        assertEquals(listOf("à"), l.keys.first { it.id == "a" }.alternates)
    }
}
