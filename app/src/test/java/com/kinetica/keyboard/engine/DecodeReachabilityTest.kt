package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Decode reachability for long zigzag paths, and the emit accounting behind it.
 *
 * Two separate mechanisms once hid a word the pattern admits:
 *  - "parlare" was admissible but starved: d=0.000 against a two-word trie, absent
 *    from the top-10 against the full 49k it one. 93.6% of the emit budget went to
 *    candidates the DTW abandon prune rejects, and the p- start subtree spent its
 *    whole MAX_CANDIDATES/2 slice inside pe-/pr- while 267 units of the global
 *    budget went unspent.
 *  - "vedere" on a clean path was not admissible at all, with zero budget stops: its
 *    legs are shorter than R_INNER_KW and collinear, so each of e/d/r merged into one
 *    pass run and the three e's of v-e-d-e-r-e could not map to increasing indices.
 *    The pass rule now ends a visit when the path turns inside the radius, and
 *    PassRunSplitTest owns those goldens. The vedere tests here are its controls:
 *    realistic, overshooting paths decoded "vedere" before that fix and must still.
 */
class DecodeReachabilityTest {

    private val g = TestData.qwertyGeometry()

    private fun fullItalian(): WordPredictor {
        val (dict, bigrams) = IT
        return WordPredictor(dict.trie, bigrams, g, dict.forms)
    }

    /** The same pattern against a trie holding [words] only. */
    private fun tiny(vararg words: String): WordPredictor {
        val text = words.joinToString("\n") { "$it\t1000" }
        val dict = StringReader(text).buffered().use { DictionaryLoader.load(it) }
        return WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
    }

    @Test
    fun patternAdmissibilityDoesNotDependOnDictionarySize() {
        // The control that makes this a budget question and not a gate one:
        // every gate in Matcher/descend (isStart, the pass monotonicity, the
        // length band, minLetters/maxLetters) depends on the path geometry and
        // the letter sequence alone. So "the monotonicity prune cuts the
        // repeated a/r pass" and "maxLetters binds" are excluded: both would
        // fail here too.
        for (tokens in listOf(
            listOf(TestData.swipe("parlare", g, 0, 1400, StreamId.RIGHT)),
            listOf(TestData.sloppySwipe("parlare", g, 0, 1400, 0.4f, StreamId.RIGHT)),
        )) {
            val out = tiny("parlare", "persone").decode(tokens, emptyList())
            assertEquals("parlare must head a two-word trie", "parlare", out.first().word)
        }
    }

    @Test
    fun parlareDecodesTop1AgainstTheFullDictionary() {
        // The pattern admits "parlare" (control above), so the full dictionary
        // must produce it too. Without the budget split it was absent from the
        // whole top-10, with nothing better than "perdonare" d=0.753.
        val full = fullItalian()
        for ((label, tokens) in listOf(
            "clean" to listOf(TestData.swipe("parlare", g, 0, 1400, StreamId.RIGHT)),
            "sloppy" to listOf(TestData.sloppySwipe("parlare", g, 0, 1400, 0.4f, StreamId.RIGHT)),
        )) {
            val out = full.decode(tokens, emptyList())
            assertEquals(
                "$label path: ${out.take(3).map { "${it.word}(d=${it.dtwDistance})" }}",
                "parlare",
                out.first().word,
            )
            // The clean path is centre-to-centre, so its ideal path is the
            // observed one; the sloppy fixture's 0.4 kw jitter measured 0.342.
            val bound = if (label == "clean") 0.05f else 0.4f
            assertTrue(
                "$label path fit d=${out.first().dtwDistance} exceeds $bound",
                out.first().dtwDistance < bound,
            )
        }
    }

    @Test
    fun theParlareSearchTerminatesWithoutHittingAnyBudget() {
        // Tells "the budget is no longer spent on words that never reach the
        // heap" from "the budget was raised": with the split the search
        // exhausts the admissible trie by itself, so no branch is cut off.
        // Without it: stops=40, firstStop=pregate.
        for (line in searchSummaries(TestData.swipe("parlare", g, 0, 1400, StreamId.RIGHT))) {
            assertEquals("a branch was cut off by the budget in: $line", 0, field(line, "stops"))
        }
    }

    @Test
    fun realisticVederePathsDecodeVedere() {
        val full = fullItalian()
        // A finger overshoots every turn, which splits the pass runs on its own:
        // these two paths decoded "vedere" before the pass rule saw a turn inside
        // the radius, and the looser rule must not cost them.
        val sloppy = listOf(TestData.sloppySwipe("vedere", g, 0, 1200, 0.4f, StreamId.RIGHT))
        assertTop3(full, sloppy, "vedere", "sloppy path")
        // The real gesture from a device capture: tap v + a right-thumb swipe
        // whose printed contacts were e,d,e,r,t,r. On device "vede" committed
        // and "vedere" was outside the traced top-5, a ranking question, not
        // reachability: here the word is present.
        val device = listOf(
            TestData.tap('v', g, 0, StreamId.LEFT),
            TestData.swipe("edertr", g, 94, 889, StreamId.RIGHT),
        )
        assertTop3(full, device, "vedere", "device buffer")
    }

    @Test
    fun searchSummaryAccountsForEveryEmitAttempt() {
        // Locks the instrument, not the defect: every attempt either becomes a
        // candidate on the heap or is charged to one abandon reason, and the
        // per-first-letter histogram covers all of them. The 93.6% waste was
        // measured through this invariant; if it drifts, the budget numbers
        // stop meaning what they say.
        for (line in searchSummaries(TestData.swipe("parlare", g, 0, 1400, StreamId.RIGHT))) {
            val attempts = field(line, "attempts")
            val cands = field(line, "cands")
            val abandoned = ABANDON.findAll(line).sumOf { it.groupValues[1].toInt() }
            assertEquals(
                "attempts must equal cands + abandoned in: $line",
                attempts,
                cands + abandoned,
            )
            val byFirst = BY_FIRST.find(line)!!.groupValues[1]
                .split(",").filter { it.isNotBlank() }
                .sumOf { it.substringAfter(':').toInt() }
            assertEquals("attemptsByFirst must cover every attempt in: $line", attempts, byFirst)
        }
    }

    /** Every `search[...]` summary the full-dictionary decode of [tokens] traced. */
    private fun searchSummaries(vararg tokens: InputToken): List<String> {
        val captured = ArrayList<String>()
        DecodeTrace.sink = { captured.add(it) }
        try {
            fullItalian().decode(tokens.toList(), emptyList())
        } finally {
            DecodeTrace.sink = null
        }
        val summaries = captured.filter { it.startsWith("search[") }
        assertTrue("no search summary was traced", summaries.isNotEmpty())
        return summaries
    }

    private fun assertTop3(p: WordPredictor, tokens: List<InputToken>, word: String, label: String) {
        val out = p.decode(tokens, emptyList())
        assertTrue(
            "$word missing from top-3 on the $label: ${out.take(3).map { it.word }}",
            out.take(3).any { it.word == word },
        )
    }

    private fun field(line: String, name: String): Int =
        Regex("$name=(\\d+)").find(line)!!.groupValues[1].toInt()

    private companion object {
        val ABANDON = Regex("(?:score|ideal|dtw)=(\\d+)")
        val BY_FIRST = Regex("attemptsByFirst=([^ ]*)")

        private fun assetPath(name: String): Path {
            val direct = Paths.get("src/main/assets/dictionaries/$name")
            if (Files.exists(direct)) return direct
            return Paths.get("app/src/main/assets/dictionaries/$name")
        }

        private fun load(lang: String): Pair<LoadedDictionary, BigramTable> {
            val w = assetPath("${lang}_wordlist.txt")
            val b = assetPath("${lang}_bigrams.txt")
            assumeTrue("$lang assets not found", Files.exists(w) && Files.exists(b))
            val dict = Files.newBufferedReader(w).use { DictionaryLoader.load(it) }
            val bigrams = Files.newBufferedReader(b).use {
                DictionaryLoader.loadBigrams(it, dict.trie)
            }
            return dict to bigrams
        }

        val IT by lazy { load("it") }
    }
}
