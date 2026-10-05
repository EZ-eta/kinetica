package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.layout.Key
import com.kinetica.keyboard.layout.KeyType
import com.kinetica.keyboard.layout.KeyboardLayout
import com.kinetica.keyboard.layout.LayoutMutations
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.sqrt
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The middle row spread, priced on a thumb that aims at one board and lands on another.
 *
 * A path is drawn in dp at the centres of the board the hand remembers, with [FlatBoardProbe]'s
 * jitter and overshoot, then read on the board drawn. The diagonal is a hand used to its board;
 * aiming at a flush row on the indented board is the Nintype habit the request describes.
 * `KINETICA_SPREAD=1` to run; it prints the table.
 */
class HomeRowSpreadProbe {

    private val words = listOf(
        "through", "world", "keys", "held", "here", "where", "every", "happens", "specific",
        "little", "great", "think", "would", "right", "people", "thing", "should", "because",
        "before", "really", "glass", "shall", "flash", "skills", "half", "flags", "salad", "lakes",
    )

    private fun assetPath(name: String): Path {
        val direct = Paths.get("src/main/assets/dictionaries/$name")
        if (Files.exists(direct)) return direct
        return Paths.get("app/src/main/assets/dictionaries/$name")
    }

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
        return KeyboardLayout("qwerty", "en_US", keys)
    }

    /** Letter rects in dp for the portrait board, 41 dp keys and 80 dp rows. */
    private fun rects(amount: Float): Pair<List<FloatArray>, IntArray> {
        val l = LayoutMutations.withHomeRowSpread(qwerty(), amount)
        val out = ArrayList<FloatArray>()
        val codes = ArrayList<Int>()
        for (k in l.keys) {
            if (!k.isLetter) continue
            out.add(floatArrayOf(k.x * BOARD_W, k.y * BOARD_H, (k.x + k.w) * BOARD_W, (k.y + k.h) * BOARD_H))
            codes.add(k.output[0] - 'a')
        }
        return out to codes.toIntArray()
    }

    private fun pathDp(word: String, aim: Pair<List<FloatArray>, IntArray>, seed: Int, jitter: Float): List<Pair<Float, Float>> {
        val rnd = java.util.Random(seed * 7919L + word.hashCode())
        val (rs, codes) = aim
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
    fun priceTheSpread() {
        assumeTrue("set KINETICA_SPREAD=1 to run", System.getenv("KINETICA_SPREAD") == "1")
        val p = assetPath("en_wordlist.txt")
        assumeTrue(Files.exists(p))
        val d = Files.newBufferedReader(p).use { DictionaryLoader.load(it) }
        println("SPREAD jitter | aim | board | top-1 | present")
        for (jitter in JITTERS_DP) for (aim in AMOUNTS) for (board in AMOUNTS) {
            val (rs, codes) = rects(board)
            val g = KeyboardGeometry.fromPx(BOARD_W / 10f, BOARD_W / 2f, rs, codes)
            val predictor = WordPredictor(d.trie, BigramTable.EMPTY, g, d.forms, language = "en")
            val aimRects = rects(aim)
            var top = 0
            var present = 0
            var n = 0
            for (w in words) {
                for (seed in 0 until SEEDS) {
                    val t = swipe(pathDp(w, aimRects, seed, jitter), g) ?: continue
                    n++
                    val out = predictor.decode(listOf(t), emptyList())
                    if (out.any { it.word == w }) present++
                    if (out.firstOrNull()?.word == w) top++
                }
            }
            println("SPREAD ${jitter.toInt()}dp | ${(aim * 100).toInt()}% | ${(board * 100).toInt()}% | $top/$n | $present/$n")
        }
    }

    private companion object {
        const val SEEDS = 10
        val JITTERS_DP = listOf(7f, 12f, 16f, 20f)
        val AMOUNTS = listOf(0f, 0.5f, 1f)
        const val BOARD_W = 410f
        const val BOARD_H = 320f
        const val OVERSHOOT_DP = 12f
        const val TAP_DP = 12f
    }
}
