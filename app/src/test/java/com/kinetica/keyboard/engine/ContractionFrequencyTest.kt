package com.kinetica.keyboard.engine

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The 40 bundled English contractions carried the
 * frequency of their apostrophe-LESS MISSPELLING, because the generator assumed
 * the OpenSubtitles source ships "dont"/"arent". It does not - it splits at the
 * apostrophe and keeps the clitic as its own entry ('t 9628970, 's 14291013),
 * which WORD_RE rejects, so the mass stays on the stem ("don" 4158644) while
 * "dont" 9523 is only the typo count.
 *
 * Two mechanisms are locked here, and they are not the same defect:
 *
 *  - RANK. A contraction at freq 163 has fw at the FREQ_WEIGHT_FLOOR, so any
 *    ordinary word sharing its path outranks it whatever the geometry says.
 *  - TIE. The apostrophe is transparent on the swipe path (zero path length in
 *    WordPredictor, skipped in the DtwMatcher ideal path), so a contraction and
 *    its misspelling decode at IDENTICAL dTotal. Copying the frequency made
 *    them identical in score too - 32 exact ties broken by heap order alone.
 *
 * The completion path is deliberately NOT asserted as a win: there the
 * apostrophe costs a full COMPLETION_PENALTY_PER_LETTER that the bare spelling
 * does not pay, worth geo(0.50)/geo(0.25) = 1.98x, and the frequency repair
 * buys only 1.61x of it. See [contractionCompletionStaysPickable].
 */
class ContractionFrequencyTest {

    /** The generator's CONTRACTIONS["en"] map: misspelling -> apostrophe form. */
    private val contractions = mapOf(
        "dont" to "don't", "wont" to "won't", "cant" to "can't", "isnt" to "isn't",
        "arent" to "aren't", "wasnt" to "wasn't", "werent" to "weren't",
        "doesnt" to "doesn't", "didnt" to "didn't", "havent" to "haven't",
        "hasnt" to "hasn't", "hadnt" to "hadn't", "wouldnt" to "wouldn't",
        "couldnt" to "couldn't", "shouldnt" to "shouldn't", "mustnt" to "mustn't",
        "neednt" to "needn't", "aint" to "ain't", "its" to "it's", "thats" to "that's",
        "whats" to "what's", "hes" to "he's", "shes" to "she's", "whos" to "who's",
        "theres" to "there's", "heres" to "here's", "wheres" to "where's",
        "hows" to "how's", "lets" to "let's", "youre" to "you're",
        "theyre" to "they're", "weve" to "we've", "youve" to "you've",
        "theyve" to "they've", "ive" to "i've", "im" to "i'm", "youll" to "you'll",
        "theyll" to "they'll", "youd" to "you'd", "theyd" to "they'd",
    )

    private fun assetPath(name: String): Path {
        val direct = Paths.get("src/main/assets/dictionaries/$name")
        if (Files.exists(direct)) return direct
        return Paths.get("app/src/main/assets/dictionaries/$name")
    }

    /** Raw asset counts, before log-quantization - the defect lives here. */
    private fun counts(): Map<String, Int> {
        val p = assetPath("en_wordlist.txt")
        assumeTrue("wordlist asset not found", Files.exists(p))
        val out = HashMap<String, Int>(50_000)
        Files.newBufferedReader(p).use { r ->
            r.lineSequence().forEach { line ->
                val parts = line.split('\t')
                if (parts.size == 2) parts[1].toIntOrNull()?.let { out[parts[0]] = it }
            }
        }
        return out
    }

    private fun predictor(): Pair<WordPredictor, KeyboardGeometry> {
        val p = assetPath("en_wordlist.txt")
        assumeTrue("wordlist asset not found", Files.exists(p))
        val trie = Files.newBufferedReader(p).use { DictionaryLoader.loadWordlist(it) }
        val g = TestData.qwertyGeometry()
        return WordPredictor(trie, BigramTable.EMPTY, g) to g
    }

    @Test
    fun everyContractionOutranksItsMisspelling() {
        // The tie half. 32 of the 40 have their misspelling in the list; the
        // augmentation copied its count, so every pair was exactly equal.
        val f = counts()
        val ties = ArrayList<String>()
        var pairs = 0
        for ((typo, form) in contractions) {
            val a = f[form] ?: continue
            val b = f[typo] ?: continue
            pairs++
            if (a <= b) ties.add("$form=$a vs $typo=$b")
        }
        assertTrue("expected the misspelling pairs, found $pairs", pairs >= 30)
        assertTrue("contraction not above its misspelling: $ties", ties.isEmpty())
    }

    @Test
    fun noContractionSitsAtTheFallbackFloor() {
        // CONTRACTION_FALLBACK_FREQ was 200 for the eight forms whose
        // misspelling is absent from the source - a floor, not a measurement.
        val f = counts()
        val floored = contractions.values.filter { (f[it] ?: 0) <= 200 }
        assertTrue("still at the fallback floor: $floored", floored.isEmpty())
    }

    @Test
    fun everyContractionIsPresentAndPlausible() {
        // The repair must not silently drop a form, and no form may exceed the
        // corpus maximum (which would rescale every other word's freqByte).
        val f = counts()
        val maxCount = f.values.max()
        for (form in contractions.values) {
            val c = f[form]
            assertTrue("$form missing from the asset", c != null)
            assertTrue("$form=$c exceeds maxCount $maxCount", c!! <= maxCount)
        }
    }

    @Test
    fun freqWeightScaleIsUnchanged() {
        // freqByteFor quantizes against the list maximum, so a contraction
        // above "you" would move EVERY other word's fw. This is what makes the
        // asset diff exactly the contraction rows.
        val f = counts()
        assertEquals("maxCount moved", 28_787_591, f.values.max())
        assertEquals("the max is no longer 'you'", 28_787_591, f["you"])
    }

    @Test
    fun contractionLeadsItsMisspellingOnTheSwipePath() {
        // The apostrophe is transparent, so these decode at identical dTotal
        // and the contest is pure fw. Before the repair the scores were equal.
        val (predictor, g) = predictor()
        for (typo in listOf("dont", "heres", "youre", "thats", "isnt")) {
            val form = contractions.getValue(typo)
            val words = predictor.decode(
                listOf(TestData.swipe(typo, g, 0, 500)), emptyList(),
            ).map { it.word }
            val i = words.indexOf(form)
            val j = words.indexOf(typo)
            assertTrue("'$form' missing from swipe '$typo': $words", i >= 0)
            assertTrue(
                "'$form' (rank ${i + 1}) does not lead '$typo' (rank ${j + 1}): $words",
                j < 0 || i < j,
            )
        }
    }

    @Test
    fun hereIsBeatsItsHomographRivalsOnTheSwipePath() {
        // The defect's own device row (swipe contacts "hgfrerds"): a real
        // "here's" gesture returned gets/hers/herd/has/
        // herds, with here's nowhere - it is admissible, just outranked at the
        // frequency floor. Rivals share the h-e-r-*-s path at comparable fits.
        //
        // The fixture must be SLOPPY. On a perfect centre-to-centre path
        // here's own ideal polyline is the input, so it wins at d=0 whatever
        // its frequency, and the golden would pass for the wrong reason - the
        // reconstruction caveat recorded elsewhere (a clean rebuild compresses the
        // distance gap the device row turns on).
        val (predictor, g) = predictor()
        val words = predictor.decode(
            listOf(TestData.sloppySwipe("heres", g, 0, 500, overshootKw = 0.45f)), emptyList(),
        ).map { it.word }
        val i = words.indexOf("here's")
        assertTrue("'here's' missing from the decode: $words", i >= 0)
        for (rival in listOf("hers", "herds", "herd", "gets")) {
            val j = words.indexOf(rival)
            assertTrue(
                "'here's' (rank ${i + 1}) does not lead '$rival' (rank ${j + 1}): $words",
                j < 0 || i < j,
            )
        }
    }

    @Test
    fun realWordsSharingAContractionPathStayReachable() {
        // The over-promotion guard. it's is clamped to its stem's whole count
        // (13.6M), the largest jump in the repair, and "its" is a real
        // possessive that must stay pickable on the same path; "let"/"can"/
        // "won"/"here" are the bare words whose stems fed the estimate.
        val (predictor, g) = predictor()
        val its = predictor.decode(
            listOf(TestData.swipe("its", g, 0, 300)), emptyList(),
        ).map { it.word }
        assertTrue("'its' unreachable: $its", its.take(3).contains("its"))

        for (word in listOf("here", "there", "what", "that", "you", "they")) {
            val got = predictor.decode(
                listOf(TestData.swipe(word, g, 0, 500)), emptyList(),
            ).map { it.word }
            assertEquals("'$word' no longer leads its own path: $got", word, got.firstOrNull())
        }
    }

    @Test
    fun contractionCompletionStaysPickable() {
        // On the completion path the apostrophe costs a whole
        // COMPLETION_PENALTY_PER_LETTER that "heres" does not pay, so here's
        // sits at d=0.50 against 0.25 - a 1.98x handicap the frequency repair
        // only closes to 1.23x. Assert PICKABILITY, not the win: pinning the
        // loss would cement it, and pinning a win would be false.
        val (predictor, g) = predictor()
        val words = predictor.decode(
            "here".mapIndexed { i, c -> TestData.tap(c, g, i * 100L) }, emptyList(),
        ).map { it.word }
        assertEquals("'here' is not the exact-tap head: $words", "here", words.firstOrNull())
        assertTrue("'here's' not offered as a completion: $words", words.contains("here's"))
    }
}
