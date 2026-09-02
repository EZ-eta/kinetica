package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.StreamId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Integration checks against the real bundled assets: memory budget, load
 * sanity, and end-to-end decode quality on the full 46k-word dictionary.
 * Unit tests run with the module directory as working dir, so the assets are
 * reachable relatively; skipped (not failed) if the layout ever changes.
 */
class RealDictionaryTest {

    private fun assetPath(name: String): Path {
        val direct = Paths.get("src/main/assets/dictionaries/$name")
        if (Files.exists(direct)) return direct
        return Paths.get("app/src/main/assets/dictionaries/$name")
    }

    private fun loadTrie(): Trie {
        val p = assetPath("en_wordlist.txt")
        assumeTrue("wordlist asset not found", Files.exists(p))
        return Files.newBufferedReader(p).use { DictionaryLoader.loadWordlist(it) }
    }

    @Test
    fun fullDictionaryLoadsWithinMemoryBudget() {
        val trie = loadTrie()
        assertTrue("word count ${trie.wordCount}", trie.wordCount >= 30_000)
        // The whole-engine budget is 8 MB; the trie itself must stay well under.
        assertTrue("trie bytes ${trie.sizeBytes()}", trie.sizeBytes() < 4 * 1024 * 1024)
        for (w in listOf("the", "something", "keyboard", "hello")) {
            assertTrue("missing $w", trie.contains(w))
        }
    }

    @Test
    fun bigramsLoadAndBoost() {
        val trie = loadTrie()
        val p = assetPath("en_bigrams.txt")
        assumeTrue("bigram asset not found", Files.exists(p))
        val table = Files.newBufferedReader(p).use { DictionaryLoader.loadBigrams(it, trie) }
        assertTrue("bigram count ${table.size}", table.size > 50_000)
        assertTrue("table bytes ${table.sizeBytes()}", table.sizeBytes() < 4 * 1024 * 1024)
        val boost = table.multiplier(trie.nodeFor("of"), trie.nodeFor("the"))
        assertTrue("of->the boost $boost", boost > 1.5f)
    }

    @Test
    fun realDictionarySwipeDecode() {
        val trie = loadTrie()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(trie, BigramTable.EMPTY, g)

        val result = predictor.decode(
            listOf(TestData.swipe("something", g, 0, 600)), emptyList(),
        )
        assertTrue(result.isNotEmpty())
        assertEquals("something", result[0].word)

        val hello = predictor.decode(
            listOf(TestData.swipe("helo", g, 0, 400)), emptyList(),
        )
        assertTrue(hello.map { it.word }.contains("hello"))
    }

    @Test
    fun letterRevisitWordsSurviveSloppySwipes() {
        // Regression for the "however" -> "hoover" collapse: words that visit
        // a letter twice (the second e in how-e-v-e-r) used to be pruned by the
        // path-order monotonicity check, because each letter recorded only its
        // single globally nearest resample index. A perfect center-to-center
        // path passes that check with zero margin; any real-world overshoot at
        // a turn vertex shifted the minima and silently killed the word before
        // DTW ever scored it, leaving a shorter revisit-free rival ("hoover")
        // as the sole survivor.
        val trie = loadTrie()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(trie, BigramTable.EMPTY, g)

        val words = listOf(
            "however", "remember", "minimum", "tomorrow",
            "banana", "people", "interesting", "whatever",
        )
        for (word in words) {
            for (overshoot in listOf(0f, 0.25f, 0.45f)) {
                val token = if (overshoot == 0f) {
                    TestData.swipe(word, g, 0, 700)
                } else {
                    TestData.sloppySwipe(word, g, 0, 700, overshootKw = overshoot)
                }
                val result = predictor.decode(listOf(token), emptyList())
                assertTrue(
                    "'$word' (overshoot $overshoot) missing from ${result.map { it.word }}",
                    result.map { it.word }.contains(word),
                )
            }
        }

        // The specific reported failure: "however" must not lose to "hoover".
        val sloppy = predictor.decode(
            listOf(TestData.sloppySwipe("however", g, 0, 700, overshootKw = 0.45f)),
            emptyList(),
        )
        assertEquals("however", sloppy[0].word)
        // And a decode must never collapse to a single forced choice.
        assertTrue("expected >= 3 candidates, got ${sloppy.map { it.word }}", sloppy.size >= 3)
    }

    @Test
    fun englishContractionsDecodeFromPlainInput() {
        // The transparent apostrophe walk reaches a dictionary
        // contraction from the plain (apostrophe-free) letters, so swiping or
        // tapping "dont"/"arent"/... surfaces "don't"/"aren't"/... once the
        // contractions are in the wordlist. There is no apostrophe key on the
        // alpha layer; the ' is inserted for free by the trie descent.
        val trie = loadTrie()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(trie, BigramTable.EMPTY, g)

        val cases = mapOf(
            "dont" to "don't",
            "arent" to "aren't",
            "isnt" to "isn't",
            "cant" to "can't",
            "heres" to "here's",
        )
        for ((typed, expected) in cases) {
            val swiped = predictor.decode(
                listOf(TestData.swipe(typed, g, 0, 500)), emptyList(),
            )
            assertTrue(
                "'$expected' missing from swipe '$typed': ${swiped.map { it.word }}",
                swiped.map { it.word }.contains(expected),
            )
        }

        // Tap-typing the letters reaches it too (all-anchor apostrophe insert).
        val tapped = predictor.decode(
            "dont".mapIndexed { i, c -> TestData.tap(c, g, i * 100L) }, emptyList(),
        )
        assertTrue(
            "'don't' missing from tap 'dont': ${tapped.map { it.word }}",
            tapped.map { it.word }.contains("don't"),
        )
    }

    @Test
    fun decodeLatencyIsBounded() {
        val trie = loadTrie()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(trie, BigramTable.EMPTY, g)
        val tokens = listOf(TestData.swipe("keyboard", g, 0, 600))
        predictor.decode(tokens, emptyList()) // warmup
        val t0 = System.nanoTime()
        repeat(20) { predictor.decode(tokens, emptyList()) }
        val perDecodeMs = (System.nanoTime() - t0) / 20 / 1_000_000.0
        // Budget is 100 ms on a mid-range phone; a desktop JVM must be far under.
        assertTrue("decode took $perDecodeMs ms", perDecodeMs < 100.0)
    }

    @Test
    fun completionSurfacesFromTapPrefix() {
        // Real-dictionary completion golden: a 5-tap prefix surfaces its rare long
        // extension mid-word. (The planning example "zibaldone" lives in the
        // developer's personal dictionary, not the bundled wordlist; the
        // mechanism is identical - personal words merge into the trie at
        // load - so a bundled word keeps this golden runnable everywhere.)
        val trie = loadTrie()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(trie, BigramTable.EMPTY, g)
        val tokens = "keybo".mapIndexed { i, c -> TestData.tap(c, g, i * 100L) }
        val result = predictor.decode(tokens, emptyList())
        assertTrue(
            "'keyboard' missing from ${result.map { it.word }}",
            result.map { it.word }.contains("keyboard"),
        )
    }

    @Test
    fun completionDecodeLatencyIsBounded() {
        // Worst-case completion fan-out: "co" prefixes ~1500 words of the
        // real dictionary, so the bounded descent (COMPLETION_MAX_EXTRA,
        // MAX_CANDIDATES) is what keeps this inside the same 100 ms budget
        // as full swipe decodes.
        val trie = loadTrie()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(trie, BigramTable.EMPTY, g)
        val tokens = listOf(TestData.tap('c', g, 0), TestData.tap('o', g, 100))
        predictor.decode(tokens, emptyList()) // warmup
        val t0 = System.nanoTime()
        repeat(20) { predictor.decode(tokens, emptyList()) }
        val perDecodeMs = (System.nanoTime() - t0) / 20 / 1_000_000.0
        assertTrue("completion decode took $perDecodeMs ms", perDecodeMs < 100.0)
    }

    @Test
    fun multiAnchorDecodeLatencyIsBounded() {
        // Worst-case merge fan-out: a swipe carrying TWO
        // cross-stream anchors emits the multi-anchor interleave in three trim
        // variants on top of every pre-existing generator, and each interleave
        // costs three DTW segments per candidate instead of one. MAX_SPLIT_ANCHORS
        // and MAX_ALT_SEQUENCES are what keep that inside the same 100 ms budget.
        val trie = loadTrie()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(trie, BigramTable.EMPTY, g)
        val tokens = listOf(
            TestData.swipe("watd", g, t0 = 0, durMs = 900, stream = StreamId.LEFT),
            TestData.tap('n', g, 300, StreamId.RIGHT),
            TestData.tap('e', g, 600, StreamId.RIGHT),
        )
        predictor.decode(tokens, emptyList()) // warmup
        val t0 = System.nanoTime()
        repeat(20) { predictor.decode(tokens, emptyList()) }
        val perDecodeMs = (System.nanoTime() - t0) / 20 / 1_000_000.0
        assertTrue("multi-anchor decode took $perDecodeMs ms", perDecodeMs < 100.0)
    }

    @Test
    fun mergedDecodeLatencyIsBounded() {
        // The split-variant fan-out (V1/V2/V3 per (tap, swipe) pair, capped by
        // MAX_ALT_SEQUENCES=12) multiplies pattern count for merged buffers -
        // the single-swipe case above never fires the generators at all. The
        // quindi early-tap buffer exercises all three variants against the
        // full dictionary under the same 100 ms budget.
        val trie = loadTrie()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(trie, BigramTable.EMPTY, g)
        val tokens = listOf(
            TestData.tap('q', g, 0, StreamId.LEFT),
            TestData.swipe("uini", g, t0 = 200, durMs = 600, stream = StreamId.RIGHT),
            TestData.tap('d', g, 520, StreamId.LEFT),
        )
        predictor.decode(tokens, emptyList()) // warmup
        val t0 = System.nanoTime()
        repeat(20) { predictor.decode(tokens, emptyList()) }
        val perDecodeMs = (System.nanoTime() - t0) / 20 / 1_000_000.0
        assertTrue("merged decode took $perDecodeMs ms", perDecodeMs < 100.0)
    }

    /**
     * The retraction's dictionary gate, on the buffers that made it necessary.
     *
     * Every pair here is verbatim from the 2026-08-29 capture: the developer typed the
     * first word, the automatic space arrived, and the next word's first letter decided
     * whether the space survived. `isLivePrefix` is what now decides it.
     */
    @Test
    fun theRetractionGateSeparatesAFinishedWordFromAnUnfinishedOne() {
        val p = assetPath("it_wordlist.txt")
        assumeTrue("it_wordlist asset not found", Files.exists(p))
        val dict = Files.newBufferedReader(p).use { DictionaryLoader.load(it) }
        val pred = WordPredictor(dict.trie, BigramTable.EMPTY, TestData.qwertyGeometry(), dict.forms)

        // Finished words swallowing the next one - the reported bug. Each of these
        // decoded to nothing on device once the space had been taken back.
        for (fused in listOf("automaticop", "automaticope", "cadettod", "cadettodi")) {
            assertTrue("$fused must not look like a word in waiting", !pred.isLivePrefix(fused))
        }

        // What the retraction exists for, and it must survive: a premature space inside a
        // word the developer was still typing.
        for (fused in listOf("autom", "automatic", "mangian", "mangiano", "prov", "praticam")) {
            assertTrue("$fused is a real continuation", pred.isLivePrefix(fused))
        }

        // Accents fold, because a prefix has not chosen them yet: `perche` reaches the
        // `perché` node exactly as the decoder's own accent handling does.
        assertTrue("perch", pred.isLivePrefix("perch"))
        assertTrue("case is folded", pred.isLivePrefix("AUTOM"))

        // An empty base never retracts: there is nothing to fuse into.
        assertTrue("empty", !pred.isLivePrefix(""))
    }

    /**
     * What the autospace's joined-token lookup actually finds, per language.
     *
     * The rule is the same in both; the outcomes differ because the data does, and that is
     * worth pinning rather than believing. English contractions are in the wordlist with
     * large counts, so `don't` spaces. Italian elisions are NOT in it at all - ten
     * apostrophe entries, every one corpus junk - so the lookup cannot find `d'accordo` and
     * no logic change can alter THAT.
     *
     * What did change is what the absence costs. `autospacesTappedWord` no longer waits on
     * this lookup for an apostrophe joiner: when it answers no it judges the piece after
     * the apostrophe instead, and the last block below is why that works - the second half
     * of every elision is an ordinary entry. So the Italian assertion here still stands and
     * is still the one WP38 flips; it just no longer decides whether the space arrives.
     */
    @Test
    fun theJoinedTokenLookupIsDecidedByWhatEachWordlistHolds() {
        val enPath = assetPath("en_wordlist.txt")
        val itPath = assetPath("it_wordlist.txt")
        assumeTrue("wordlists not found", Files.exists(enPath) && Files.exists(itPath))
        val g = TestData.qwertyGeometry()
        val enDict = Files.newBufferedReader(enPath).use { DictionaryLoader.load(it) }
        val itDict = Files.newBufferedReader(itPath).use { DictionaryLoader.load(it) }
        val en = WordPredictor(enDict.trie, BigramTable.EMPTY, g, enDict.forms)
        val it = WordPredictor(itDict.trie, BigramTable.EMPTY, g, itDict.forms)

        // English contractions: the whole token is a word, so the space is earned.
        for (w in listOf("don't", "it's", "can't")) {
            assertTrue("$w should be an English word", en.isWord(w))
        }
        // Not words, so a token like this stays refused - the reported behaviour to keep.
        for (w in listOf("log-12.com", "example.com", "session_notes")) {
            assertTrue("$w must not be a word", !en.isWord(w) && !it.isWord(w))
        }
        // The Italian gap, stated as a fact. When the elided forms are generated into the
        // wordlist this assertion is the one that flips, and it should be flipped
        // deliberately rather than discovered.
        for (w in listOf("d'accordo", "l'altro", "un'ora", "dell'anno")) {
            assertTrue("$w is absent from it_wordlist; see item 45", !it.isWord(w))
        }
        // ...and what the fallback rests on: the piece AFTER the apostrophe is an ordinary
        // Italian word in every one of them, which is why judging it on its own is a real
        // answer rather than a shrug. `ora` is the shortest at three letters, so the
        // two-letter length rule never bites on the forms that matter.
        for (w in listOf("accordo", "altro", "ora", "anno", "immagine")) {
            assertTrue("$w should be an Italian word", it.isWord(w))
            assertTrue("$w is long enough for the length rule", w.length >= 2)
        }
        // The retraction's second question, on real data: `dell'anno` is unreachable as a
        // whole and `anno` is reachable, which is the entire reason the fusion is asked of
        // the tail as well.
        assertTrue("dell'anno is not a live prefix", !it.isLivePrefix("dell'anno"))
        assertTrue("anno is", it.isLivePrefix("anno"))
    }
}
