package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.SwipeToken
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.sqrt
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The same words drawn with the same physical hand on boards of different shape.
 *
 * Paths are drawn in dp, where a thumb's error lives: the same jitter and overshoot on every
 * board, then read through [GestureStream] as a phone reads them. Reading a flat board at a
 * 1.5 kw row pitch measured worse; the probe keeps pricing the landscape split's key size.
 * `KINETICA_FLAT=1` to run; it prints the table.
 */
class FlatBoardProbe {

    private data class Board(val name: String, val keyW: Float, val rowH: Float)

    private val boards = listOf(
        Board("portrait 41x80", 41f, 80f),
        Board("split lock 29x44", 29f, 44f),
        Board("split 60% 37x44", 37f, 44f),
        Board("split 50% 46x44", 46f, 44f),
        Board("split 40% 55x44", 55f, 44f),
        Board("full width 91x44", 91f, 44f),
    )

    private val words = listOf(
        "through", "world", "keys", "held", "here", "where", "every", "happens", "specific",
        "little", "great", "think", "would", "right", "people", "thing", "should", "because",
        "before", "really",
    )

    private fun assetPath(name: String): Path {
        val direct = Paths.get("src/main/assets/dictionaries/$name")
        if (Files.exists(direct)) return direct
        return Paths.get("app/src/main/assets/dictionaries/$name")
    }

    private fun rects(b: Board): Pair<List<FloatArray>, IntArray> {
        val rows = listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f)
        val out = ArrayList<FloatArray>()
        val codes = ArrayList<Int>()
        for ((r, row) in rows.withIndex()) {
            for ((i, ch) in row.first.withIndex()) {
                val left = (row.second + i) * b.keyW
                out.add(floatArrayOf(left, r * b.rowH, left + b.keyW, (r + 1) * b.rowH))
                codes.add(ch - 'a')
            }
        }
        return out to codes.toIntArray()
    }

    /** A thumb's path for [word] in dp: centres, a physical overshoot at each turn, jitter. */
    private fun pathDp(word: String, b: Board, seed: Int, jitter: Float): List<Pair<Float, Float>> {
        val rnd = java.util.Random(seed * 7919L + word.hashCode())
        val (rs, codes) = rects(b)
        fun centre(ch: Char): Pair<Float, Float> {
            val r = rs[codes.indexOf(ch - 'a')]
            return (r[0] + r[2]) / 2f to (r[1] + r[3]) / 2f
        }
        val cs = ArrayList<Pair<Float, Float>>()
        var prev = ' '
        for (ch in word) {
            if (ch == prev) continue
            val (x, y) = centre(ch)
            cs.add(x + (rnd.nextFloat() * 2f - 1f) * jitter to y + (rnd.nextFloat() * 2f - 1f) * jitter)
            prev = ch
        }
        val vs = ArrayList<Pair<Float, Float>>()
        vs.add(cs[0])
        for (i in 1 until cs.size) {
            val (px, py) = cs[i - 1]
            val (cx, cy) = cs[i]
            val len = sqrt((cx - px) * (cx - px) + (cy - py) * (cy - py))
            vs.add(cx to cy)
            if (i < cs.size - 1 && len > 1e-3f) {
                vs.add(cx + (cx - px) / len * OVERSHOOT_DP to cy + (cy - py) / len * OVERSHOOT_DP)
            }
        }
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

    private fun swipe(path: List<Pair<Float, Float>>, g: KeyboardGeometry): SwipeToken? {
        val (x0, y0) = path[0]
        val code = g.keyAt(x0 / g.keyWidthPx, y0 / g.keyWidthPx)
        if (code == -1) return null
        val s = GestureStream(StreamId.RIGHT, 0, g, TAP_DP / g.keyWidthPx, x0, y0, 1000L, code) {}
        for ((i, p) in path.withIndex()) {
            if (i == 0) continue
            s.addPoint(p.first, p.second, 1000L + i * 4L)
        }
        return s.finish(1000L + path.size * 4L) as? SwipeToken
    }

    @Test
    fun priceTheFloor() {
        assumeTrue("set KINETICA_FLAT=1 to run", System.getenv("KINETICA_FLAT") == "1")
        val p = assetPath("en_wordlist.txt")
        assumeTrue(Files.exists(p))
        val d = Files.newBufferedReader(p).use { DictionaryLoader.load(it) }
        println("FLAT jitter | board | top-1 | present | median lead margin")
        for (jitter in JITTERS_DP) for (b in boards) {
            val (rs, codes) = rects(b)
            run {
                val g = KeyboardGeometry.fromPx(b.keyW, 5f * b.keyW, rs, codes)
                val predictor = WordPredictor(d.trie, BigramTable.EMPTY, g, d.forms, language = "en")
                var top = 0
                var present = 0
                var n = 0
                val margins = ArrayList<Float>()
                for (w in words) {
                    for (seed in 0 until SEEDS) {
                        val t = swipe(pathDp(w, b, seed, jitter), g) ?: continue
                        n++
                        val out = predictor.decode(listOf(t), emptyList())
                        if (out.any { it.word == w }) present++
                        if (out.firstOrNull()?.word == w) {
                            top++
                            if (out.size > 1) margins.add(out[0].score / out[1].score)
                        }
                    }
                }
                margins.sort()
                val med = if (margins.isEmpty()) 0f else margins[margins.size / 2]
                println("FLAT ${jitter.toInt()}dp | ${b.name} | $top/$n | $present/$n | ${"%.2f".format(med)}")
            }
        }
    }

    private companion object {
        const val SEEDS = 10
        val JITTERS_DP = listOf(7f, 12f, 16f, 20f)
        const val OVERSHOOT_DP = 12f
        const val TAP_DP = 12f
    }
}
