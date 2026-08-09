package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.SwipeToken
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Regression suite for pass-run merging: a swipe whose
 * legs are all shorter than `R_INNER_KW` (1.8 kw) never leaves any of its keys'
 * discs, so `Matcher.buildSegment`'s one-index-per-run rule merged every visit
 * to a key into a SINGLE pass. A word needing that letter at increasing indices
 * then became unspellable and the decode returned nothing.
 *
 * Confirmed on a device capture: "vedere" failed
 * 2 of 3 natural gestures, committing "vette"/"brewer". Both failing swipes had
 * contacts `edere`, which reconstructs to pass runs `e[0] d[9] r[25]` - one run
 * for e - and zero candidates.
 *
 * **The defect punishes PRECISION**, which inverts this project's usual fixture
 * assumption that a clean centre-to-centre path is the optimistic case. So the
 * acceptance cases here are the PRECISE buffers, and the sloppy paths (already
 * locked by `DecodeReachabilityTest.realisticVederePathsDecodeVedere`) are the
 * controls that must not pay for them.
 *
 * The fix splits a run at an interior peak with `PASS_SPLIT_PROMINENCE_KW` of
 * prominence on BOTH sides - the path demonstrably left the key and came back.
 * It is monotone: each sub-run keeps its own argmin, so the pass set is a strict
 * superset of the old one and no previously-decodable word can be lost
 * (`passesAscendAndKeepTheNearestVisit` locks that).
 */
class PassRunSplitTest {

    private val g = TestData.qwertyGeometry()

    /** Every pass index of [c], read out through the only public accessor. */
    private fun passes(seg: Matcher.Segment, c: Char): List<Int> {
        val code = c - 'a'
        val out = ArrayList<Int>()
        var from = 0
        while (true) {
            val p = seg.passAtOrAfter(code, from)
            if (p < 0) return out
            out.add(p)
            from = p + 1
        }
    }

    private fun segmentOf(t: SwipeToken): Matcher.Segment = Matcher.buildSegment(t, g)

    @Test
    fun preciseShortReversalKeepsOnePassPerVisit() {
        // The reconstructed device buffer's swipe: e->d->e->r->e, every leg
        // under R_INNER_KW. Pre-fix the whole path is one run per key, so e has
        // exactly one pass (index 0) and v-e-d-e-r-e cannot be spelled.
        val seg = segmentOf(TestData.swipe("edere", g, 0, 800, StreamId.RIGHT))
        val e = passes(seg, 'e')
        assertEquals("one pass per visit to e, got $e", 3, e.size)
        assertEquals("the first e pass is the path's start", 0, e.first())
        assertEquals("the last e pass is the path's end", KineticaConstants.RESAMPLE_N - 1, e.last())
        assertTrue("e passes must ascend: $e", e.zipWithNext().all { (a, b) -> a < b })
        // d and r are visited once each and must NOT gain phantom passes: the
        // path only approaches them, it never leaves and returns.
        assertEquals("d visited once, got ${passes(seg, 'd')}", 1, passes(seg, 'd').size)
        assertEquals("r visited once, got ${passes(seg, 'r')}", 1, passes(seg, 'r').size)
    }

    @Test
    fun straightSweepKeepsOnePassPerKey() {
        // The anti-over-split control. Along a straight line the distance to any
        // key is strictly V-shaped: one minimum, no interior peak, so no split
        // may occur. If this reds, the rule is firing on ordinary travel and
        // every decode in the product just got looser.
        for (word in listOf("qwerty", "asdfg", "poiuy", "zxcvb")) {
            val seg = segmentOf(TestData.swipe(word, g, 0, 800, StreamId.RIGHT))
            for (code in 0 until Alphabet.LETTERS) {
                if (!g.hasKey(code)) continue
                val p = passes(seg, Alphabet.charOf(code))
                if (p.isEmpty()) continue
                assertEquals("'$word': straight sweep gave ${Alphabet.charOf(code)} passes $p", 1, p.size)
            }
        }
    }

    @Test
    fun passSetIsASupersetOfTheRunRule() {
        // The property that makes this change monotone, and therefore safe for
        // every existing golden: splitting a run cannot DROP an index the old
        // one-argmin-per-run rule chose, because that argmin is also the argmin
        // of whichever sub-run ends up containing it. The old rule is
        // reimplemented here rather than described, so the claim is checked and
        // not asserted by comment. Reversal-heavy and ordinary paths alike.
        for (word in listOf(
            "edere", "vedere", "however", "parlare", "cuando", "interessante",
            "sempre", "keyboard", "something", "uini",
        )) {
            for (overshoot in listOf(0f, 0.25f, 0.4f)) {
                val t = if (overshoot == 0f) {
                    TestData.swipe(word, g, 0, 900, StreamId.RIGHT)
                } else {
                    TestData.sloppySwipe(word, g, 0, 900, overshoot, StreamId.RIGHT)
                }
                val seg = segmentOf(t)
                for (code in 0 until Alphabet.LETTERS) {
                    if (!g.hasKey(code)) continue
                    val p = passes(seg, Alphabet.charOf(code))
                    assertTrue(
                        "$word/$overshoot: passes must ascend for ${Alphabet.charOf(code)}: $p",
                        p.zipWithNext().all { (a, b) -> a < b },
                    )
                    val runRule = oneArgminPerRun(seg, code)
                    assertTrue(
                        "$word/$overshoot: ${Alphabet.charOf(code)} lost run-rule passes " +
                            "$runRule from $p",
                        p.containsAll(runRule),
                    )
                }
            }
        }
    }

    /** The superseded rule: one argmin per maximal run within R_INNER_KW. */
    private fun oneArgminPerRun(seg: Matcher.Segment, code: Int): List<Int> {
        val out = ArrayList<Int>()
        var best = Float.MAX_VALUE
        var bi = -1
        for (k in 0 until KineticaConstants.RESAMPLE_N) {
            val d = g.distToCenter(seg.resampled[2 * k], seg.resampled[2 * k + 1], code)
            if (d <= KineticaConstants.R_INNER_KW) {
                if (d < best) {
                    best = d
                    bi = k
                }
            } else if (bi != -1) {
                out.add(bi)
                best = Float.MAX_VALUE
                bi = -1
            }
        }
        if (bi != -1) out.add(bi)
        return out
    }

    @Test
    fun deviceVedereBufferDecodesVedere() {
        // The captured failure verbatim: tap v plus a RIGHT swipe whose traced
        // contacts were e,d,e,r,e. Pre-fix this decodes EMPTY (device committed
        // "vette", then "brewer"), which is also why the empty-decode rescue then handed
        // the word to Spanish.
        val p = fullItalian() ?: return
        val tokens = listOf(
            TestData.tap('v', g, 0, StreamId.LEFT),
            TestData.swipe("edere", g, 94, 889, StreamId.RIGHT),
        )
        val out = p.decode(tokens, emptyList())
        assertEquals("device buffer: ${top(out)}", "vedere", out.firstOrNull()?.word)
    }

    @Test
    fun preciseVederePathDecodesVedere() {
        // The same defect without the tap: the whole word swiped precisely.
        // Pre-fix top-1 is "vere" at d=0.255 with "vedere" absent from the list.
        val p = fullItalian() ?: return
        val out = p.decode(listOf(TestData.swipe("vedere", g, 0, 1200, StreamId.RIGHT)), emptyList())
        assertEquals("precise path: ${top(out)}", "vedere", out.firstOrNull()?.word)
        assertTrue(
            "a centre-to-centre path must fit its own word: d=${out.first().dtwDistance}",
            out.first().dtwDistance < 0.1f,
        )
    }

    @Test
    fun shortReversalWordsAreReachableOnPrecisePaths() {
        // Three dictionaries, so the fix is the mechanism and not a patch for one
        // word. Every entry here is unspellable pre-fix on its own clean path:
        // measured across the shipped assets, 114 it / 87 en / 108 es words of
        // the top 20k are, and 0.7 kw of prominence recovers all but four.
        // Reachability is this suite's contract; where these words RANK is a scoring question.
        for ((lang, words) in listOf(
            "it" to listOf("vede", "sede", "cede", "rese"),
            "en" to listOf("deed", "seas", "pope"),
            "es" to listOf("cede", "dese", "caza"),
        )) {
            val p = predictorFor(lang) ?: continue
            for (w in words) {
                val out = p.decode(listOf(TestData.swipe(w, g, 0, 100L * w.length, StreamId.RIGHT)), emptyList())
                assertTrue(
                    "$lang '$w' unreachable on its own precise path: ${top(out)}",
                    out.any { it.word == w },
                )
            }
        }
    }

    private fun top(out: List<com.kinetica.keyboard.engine.models.WordCandidate>): String =
        if (out.isEmpty()) "<empty>" else out.take(3).joinToString(" ") { "${it.word}(d=${it.dtwDistance})" }

    private fun fullItalian(): WordPredictor? = predictorFor("it")

    private fun predictorFor(lang: String): WordPredictor? {
        val loaded = load(lang) ?: return null
        val (dict, bigrams) = loaded
        return WordPredictor(dict.trie, bigrams, g, dict.forms)
    }

    private companion object {
        private fun assetPath(name: String): Path {
            val direct = Paths.get("src/main/assets/dictionaries/$name")
            if (Files.exists(direct)) return direct
            return Paths.get("app/src/main/assets/dictionaries/$name")
        }

        private val cache = HashMap<String, Pair<LoadedDictionary, BigramTable>?>()

        fun load(lang: String): Pair<LoadedDictionary, BigramTable>? = cache.getOrPut(lang) {
            val w = assetPath("${lang}_wordlist.txt")
            val b = assetPath("${lang}_bigrams.txt")
            assumeTrue("$lang assets not found", Files.exists(w) && Files.exists(b))
            val dict = Files.newBufferedReader(w).use { DictionaryLoader.load(it) }
            val bigrams = Files.newBufferedReader(b).use { DictionaryLoader.loadBigrams(it, dict.trie) }
            dict to bigrams
        }
    }
}
