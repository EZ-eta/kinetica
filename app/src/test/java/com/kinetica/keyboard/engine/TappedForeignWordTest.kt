package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.WordCandidate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A word tapped out letter by letter reaches the other enabled language.
 *
 * `WordComposer` used to ask the second language only about buffers holding a swipe. Of
 * seventeen English words a user with Polish active found in his Polish dictionary, seven are
 * English only, and those are the ones this reaches. Nine are also in the Polish list, where
 * the merge drops the English reading as a shared word, and one is in neither;
 * [aWordBothListsHoldStaysWithTheActiveLanguage] pins that limit.
 *
 * The rest are the guards, on captured buffers with Italian active and English resident. Each
 * keeps a commit that asking the second language would otherwise change.
 */
class TappedForeignWordTest {

    private val direct = Executor { it.run() }
    private val g = TestData.qwertyGeometry()

    private class Capture : WordComposer.Callbacks {
        var candidates: List<WordCandidate> = emptyList()
        var tentative: WordCandidate? = null

        override fun onCandidates(
            candidates: List<WordCandidate>,
            tentative: WordCandidate?,
            literal: String,
            generation: Int,
        ) {
            this.candidates = candidates
            this.tentative = tentative
        }
    }

    /** Feeds [tokens] through a composer with [active] primary and [other] resident. */
    private fun compose(active: WordPredictor, other: WordPredictor, tokens: List<InputToken>): Capture {
        val cap = Capture()
        val composer = WordComposer(active, direct, direct, cap)
        composer.alternatePredictor = other
        for (t in tokens) composer.onToken(t)
        return cap
    }

    /** One stream, 150 ms apart: no cross-thumb order for the merge to reconsider. */
    private fun tapped(word: String): List<InputToken> =
        word.mapIndexed { i, c -> TestData.tap(c, g, i * 150L) }

    /** The language a commit of [word] would be filed under, the IME's languageOf rule. */
    private fun filedUnder(cap: Capture, word: String, active: String): String =
        cap.candidates.firstOrNull { it.word.equals(word, true) }?.language?.takeIf { it.isNotEmpty() }
            ?: active

    @Test
    fun aTappedItalianAccentReachesTheBarWithEnglishActive() {
        // English active, Italian resident, a tapped `e`: Italian decodes `è` beside `e`, and
        // the merge must not drop it as shared because English's `e` has no display forms. It
        // is offered; the letters typed stay the lead.
        val cap = compose(english(), italian(), tapped("e"))
        val words = cap.candidates.map { it.word }
        assertTrue("è not offered: $words", "è" in words)
        assertEquals("it", cap.candidates.first { it.word == "è" }.language)
        assertEquals("e", cap.tentative?.word)
    }

    @Test
    fun aTappedWordOnlyEnglishHoldsIsFoundAndFiledUnderEnglish() {
        val pl = polish()
        val en = english()
        for (word in ENGLISH_ONLY) {
            assertTrue("$word must be absent from pl_wordlist for this test to mean anything", !pl.isWord(word))
            val cap = compose(pl, en, tapped(word))
            val hit = cap.candidates.firstOrNull { it.word == word }
            assertNotNull("$word never reached the bar with Polish active", hit)
            assertEquals(word, "en", hit!!.language)
            assertEquals(word, "en", filedUnder(cap, word, "pl"))
            // The letters are the word, so a delimiter keeps them. `add` is the case that
            // decides the frequency rule: Polish corrects it to `asd` at 0.87 confidence, and
            // English `add` is the more frequent word.
            assertNull(word, pl.tapAutocorrect(word, cap.tentative, THRESHOLD))
        }
    }

    @Test
    fun aWordBothListsHoldStaysWithTheActiveLanguage() {
        // Nine of the reporter's seventeen. Merge rule 1 drops another language's copy of a
        // word the active dictionary holds, so these are Polish to the keyboard however they
        // are typed. Not this gate; a question about shared-word provenance.
        val pl = polish()
        val en = english()
        for (word in SHARED) {
            assertTrue("$word must be in both lists", pl.isWord(word) && en.isWord(word))
            val cap = compose(pl, en, tapped(word))
            assertEquals(word, "pl", filedUnder(cap, word, "pl"))
            assertTrue(word, cap.candidates.none { it.word == word && it.language == "en" })
        }
    }

    @Test
    fun aWordOnlyTheOtherLanguageCanFixIsNeverAutocorrectedIntoIt() {
        // A captured tapped buffer: `conquesta`, Italian active. Italian decodes nothing,
        // English offers `conquests` at a confidence that clears autocorrect. The merge refuses
        // to let it lead, and autocorrecting from the head of the list would commit it anyway.
        val it = italian()
        val cap = compose(it, english(), TraceReplay.tokens(CONQUESTA, g))
        assertNull("the merge must refuse the lead", cap.tentative)
        val head = cap.candidates.firstOrNull()
        assertEquals("the hazard must exist for this to test the guard", "conquests", head?.word)
        assertNotNull(it.autocorrectTarget("conquesta", cap.candidates, THRESHOLD))
        assertNull(it.tapAutocorrect("conquesta", cap.tentative, THRESHOLD))
    }

    @Test
    fun aRarerForeignFixNeverCorrectsATappedWord() {
        // A captured tapped buffer: `comun`. English `comin` fits better than anything
        // Italian has and is the rarer word, so it may not lead.
        val it = italian()
        val cap = compose(it, english(), TraceReplay.tokens(COMUN, g))
        assertNotEquals("comin", cap.tentative?.word)
        assertNotEquals("comin", it.tapAutocorrect("comun", cap.tentative, THRESHOLD)?.word)
    }

    @Test
    fun theActiveLanguageKeepsItsOwnCorrectionOverRarerForeignJunk() {
        // A captured tapped buffer: `maa`. English holds `maa` at rank 40 829; Italian
        // corrects it to `ama`, rank 1 309. On score the English exact reading wins, because
        // the Italian one pays its tap penalty; on frequency it does not.
        val it = italian()
        val cap = compose(it, english(), TraceReplay.tokens(MAA, g))
        assertEquals("ama", it.tapAutocorrect("maa", cap.tentative, THRESHOLD)?.word)
    }

    private fun polish(): WordPredictor = predictor(PL, "pl")
    private fun english(): WordPredictor = predictor(EN, "en")
    private fun italian(): WordPredictor = predictor(IT, "it")

    private fun predictor(loaded: Pair<LoadedDictionary, BigramTable>, lang: String): WordPredictor =
        WordPredictor(loaded.first.trie, loaded.second, g, loaded.first.forms, language = lang)

    private companion object {
        /** The shipped autocorrect default, "normal". */
        const val THRESHOLD = 0.85f

        /** Of the reporter's seventeen, the words only the English list holds. */
        val ENGLISH_ONLY = listOf("add", "boot", "cardinal", "kite", "phrase", "shortcuts", "within")

        /** Of the seventeen, the words the Polish list holds too. */
        val SHARED = listOf("act", "edge", "enough", "made", "not", "oregon", "they", "true", "trying")

        const val CONQUESTA = "tap[c,LEFT,t=2611528] tap[o,LEFT,t=2611529] tap[n,LEFT,t=2611530] " +
            "tap[q,LEFT,t=2611532] tap[u,RIGHT,t=2611658] tap[e,LEFT,t=2611784] " +
            "tap[s,LEFT,t=2611942] tap[t,LEFT,t=2612019] tap[a,LEFT,t=2612155]"
        const val COMUN = "tap[c,LEFT,t=26673875] tap[o,LEFT,t=26673876] tap[m,LEFT,t=26673877] " +
            "tap[u,LEFT,t=26673878] tap[n,LEFT,t=26673879]"
        const val MAA = "tap[m,RIGHT,t=9162866] tap[a,LEFT,t=9162991] tap[a,LEFT,t=9163299]"

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
            val bigrams = Files.newBufferedReader(b).use { DictionaryLoader.loadBigrams(it, dict.trie) }
            return dict to bigrams
        }

        val EN by lazy { load("en") }
        val PL by lazy { load("pl") }
        val IT by lazy { load("it") }
    }
}
