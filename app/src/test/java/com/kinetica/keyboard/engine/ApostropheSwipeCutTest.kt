package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.TapToken
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * One stroke through the apostrophe key, cut where it entered the key: `nell`, over `'`, `altro` is
 * two words. The key sits right of `l` on the home row, outside the letter geometry.
 */
class ApostropheSwipeCutTest {

    private val g = TestData.qwertyGeometry()
    private val kw = g.keyWidthPx

    /** The apostrophe key's left edge and centre, kw: right of `l`, as the layout adds it. */
    private val keyLeft = g.centerX(g.alphabet.codeOf('l')) + 0.5f
    private val apostrophe = keyLeft + 0.25f to g.centerY(g.alphabet.codeOf('l'))

    private val tokens = ArrayList<InputToken>()
    private var allUp = 0
    private val engine = GestureEngine(object : GestureEngine.Listener {
        override fun onTokenFinalized(token: InputToken) { tokens.add(token) }
        override fun onKeyTransition(streamId: StreamId, code: Int) {}
        override fun onAllPointersUp() { allUp++ }
    }).also {
        it.maxPointers = 2
        it.setGeometry(g, 12f)
    }

    private fun assetPath(name: String): Path {
        val direct = Paths.get("src/main/assets/dictionaries/$name")
        if (Files.exists(direct)) return direct
        return Paths.get("app/src/main/assets/dictionaries/$name")
    }

    private fun italian(): WordPredictor {
        val p = assetPath("it_wordlist.txt")
        assumeTrue("it_wordlist asset not found", Files.exists(p))
        val dict = Files.newBufferedReader(p).use { DictionaryLoader.load(it) }
        return WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms, language = "it")
    }

    private fun centre(c: Char): Pair<Float, Float> = g.alphabet.codeOf(c).let { g.centerX(it) to g.centerY(it) }

    /** Vertices through [stops], each interior turn overshot by [overshoot] kw as a thumb does. */
    private fun vertices(stops: List<Pair<Float, Float>>, overshoot: Float): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        out.add(stops[0])
        for (i in 1 until stops.size) {
            val (px, py) = stops[i - 1]
            val (cx, cy) = stops[i]
            out.add(cx to cy)
            val len = kotlin.math.sqrt((cx - px) * (cx - px) + (cy - py) * (cy - py))
            if (i < stops.size - 1 && len > 1e-3f) out.add(cx + (cx - px) / len * overshoot to cy + (cy - py) / len * overshoot)
        }
        return out
    }

    /** Points along [vs], ten per leg, 8 ms apart, kw. */
    private fun sample(vs: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        for (s in 0 until vs.size - 1) {
            for (k in 0 until 10) {
                val f = k / 10f
                out.add(vs[s].first + f * (vs[s + 1].first - vs[s].first) to vs[s].second + f * (vs[s + 1].second - vs[s].second))
            }
        }
        out.add(vs.last())
        return out
    }

    private fun inKey(p: Pair<Float, Float>): Boolean = p.first >= keyLeft

    /**
     * Draws [before], the apostrophe, then [after] as one stroke, and cuts it as the view does: once
     * the pointer has entered the key, left it and reached a letter. Returns the tokens finalized.
     */
    private fun stroke(before: String, after: String, overshoot: Float, pause: Boolean = false): List<InputToken> {
        tokens.clear()
        val pts = if (!pause) {
            sample(vertices(before.map(::centre) + listOf(apostrophe) + after.map(::centre), overshoot))
        } else {
            // A 200 ms rest on the first letter after the key: a dwell, as a thumb aiming at it makes.
            val lead = sample(vertices(before.map(::centre) + listOf(apostrophe, centre(after[0])), overshoot))
            val rest = List(25) { lead.last() }
            lead + rest + sample(vertices(after.map(::centre), overshoot)).drop(1)
        }
        val t0 = 1_000L
        assertTrue(engine.onPointerDown(0, pts[0].first * kw, pts[0].second * kw, t0))
        var tEnter = -1L
        var tExit = -1L
        var cut = false
        for (i in 1 until pts.size) {
            val (x, y) = pts[i]
            val t = t0 + 8L * i
            engine.onPointerMove(0, x * kw, y * kw, t)
            if (cut) continue
            if (inKey(pts[i]) && tEnter < 0) tEnter = t
            if (!inKey(pts[i]) && tEnter >= 0 && tExit < 0) tExit = t
            if (tExit >= 0 && g.keyAt(x, y) != -1) {
                assertTrue(engine.cutPointer(0, tEnter, tExit))
                cut = true
            }
        }
        val (x, y) = pts.last()
        engine.onPointerUp(0, x * kw, y * kw, t0 + 8L * pts.size)
        return tokens.toList()
    }

    @Test
    fun theCutGivesAHeadAndATailOnTheSameStream() {
        val out = stroke("nell", "altro", 0.3f)
        assertEquals(2, out.size)
        val head = out[0] as SwipeToken
        val tail = out[1] as SwipeToken
        assertTrue(head.softEnd)
        assertFalse(head.softStart)
        assertTrue(tail.softStart)
        assertFalse(tail.softEnd)
        assertEquals(head.streamId, tail.streamId)
        assertTrue(head.tEnd < tail.tStart)
        // The pointer stayed down through the cut: one lift, one all-up.
        assertEquals(1, allUp)
        // The head ends at the key's edge: no point of it lies inside the key.
        assertTrue(head.rawPath.all { it.x < keyLeft })
    }

    @Test
    fun nellOverTheApostropheAltroDecodesBothWords() {
        val p = italian()
        for (o in listOf(0f, 0.25f, 0.45f)) {
            val out = stroke("nell", "altro", o)
            assertEquals(2, out.size)
            val head = p.decode(listOf(out[0]), emptyList()).map { it.word }
            assertTrue("at $o kw: $head", "nell" in head.take(3))
            val tail = p.decode(listOf(out[1]), listOf("nell")).map { it.word }
            assertEquals("at $o kw: $tail", "altro", tail.firstOrNull())
        }
    }

    @Test
    fun fifteenElisionsWriteBothWords() {
        val p = italian()
        val heads = com.kinetica.keyboard.ime.elisionHeadsFor(listOf("it"))
        val pairs = listOf(
            "nell" to "altro", "dell" to "anno", "l" to "uomo", "un" to "amica", "quest" to "anno",
            "nell" to "immagine", "sull" to "erba", "l" to "ho", "c" to "era", "l" to "italia",
            "dell" to "estate", "all" to "inizio", "d" to "accordo", "l" to "acqua", "quell" to "uomo",
        )
        for ((h, w) in pairs) for (o in listOf(0f, 0.25f, 0.45f)) {
            // `i` lies on the straight way from the key to `t`: only a pause on it says where the word
            // starts. Without one the run reads `alia`.
            val out = stroke(h, w, o, pause = w == "italia")
            val cands = p.decode(listOf(out[0]), emptyList()).map { it.word }
            assertEquals("$h'$w at $o kw: $cands", h, com.kinetica.keyboard.ime.elisionHead(cands, heads) ?: cands.firstOrNull())
            val tail = p.decode(listOf(out[1]), listOf(h)).map { it.word }
            assertEquals("$h'$w at $o kw: $tail", w, tail.firstOrNull())
        }
    }

    @Test
    fun aTouchOnLThatSlidesOverTheKeyIsATapOfL() {
        tokens.clear()
        val (lx, ly) = centre('l')
        val t0 = 5_000L
        assertTrue(engine.onPointerDown(0, lx * kw, ly * kw, t0))
        engine.onPointerMove(0, (keyLeft + 0.1f) * kw, ly * kw, t0 + 40)
        engine.onPointerMove(0, (keyLeft - 0.6f) * kw, ly * kw, t0 + 80)
        assertTrue(engine.cutPointer(0, t0 + 40, t0 + 80))
        val head = tokens.single()
        assertTrue(head is TapToken)
        assertEquals(g.alphabet.codeOf('l'), (head as TapToken).code)
    }

    @Test
    fun aRestThatReachesNoLetterGivesNoToken() {
        tokens.clear()
        val (lx, ly) = centre('l')
        val (ex, ey) = centre('e')
        val t0 = 9_000L
        assertTrue(engine.onPointerDown(0, ex * kw, ey * kw, t0))
        engine.onPointerMove(0, lx * kw, ly * kw, t0 + 80)
        engine.onPointerMove(0, (keyLeft + 0.2f) * kw, ly * kw, t0 + 120)
        engine.onPointerMove(0, (keyLeft + 0.05f) * kw, (ly - 0.6f) * kw, t0 + 160)
        assertTrue(engine.cutPointer(0, t0 + 120, t0 + 160))
        engine.onPointerUp(0, (keyLeft + 0.05f) * kw, (ly - 0.6f) * kw, t0 + 200)
        assertEquals(1, tokens.size)
        assertEquals(1, allUp)
    }

    @Test
    fun aCutBeforeAnyLetterIsRefused() {
        val t0 = 12_000L
        val (lx, ly) = centre('l')
        assertTrue(engine.onPointerDown(0, lx * kw, ly * kw, t0))
        // Entered the key before the stroke began: no head point.
        assertFalse(engine.cutPointer(0, t0 - 10, t0 + 10))
        assertFalse(engine.cutPointer(7, t0, t0 + 10))
    }
}
