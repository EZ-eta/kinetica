package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.WordCandidate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Cross-language ranking against the real bundled it/es assets, with Italian
 * active, the configuration in which three swipe words committed in Spanish.
 *
 * The failing buffers are rebuilt from the `keys=` contact letters the trace
 * prints, as a polyline through those contacts' key centers, so distances
 * differ from the device's by a few hundredths: these lock the mechanism, not
 * the number. The device's own candidate tuples are pinned dictionary-free in
 * LanguagePreferenceTest.
 *
 * Each case asks whether the gesture hands the editor to the wrong language,
 * of the merged list's lead; the losing language stays pickable.
 */
class LanguageDetectGoldenTest {

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

    /** The merged Italian+Spanish ranking for [tokens]. */
    private fun merged(
        tokens: List<InputToken>,
        ctx: List<String> = emptyList(),
    ): WordComposer.Merged {
        val it = italian()
        val composer = WordComposer(it, direct, direct, Capture())
        return composer.merge(it.decode(tokens, ctx), spanish().decode(tokens, ctx))
    }

    /** True when the word the editor would take comes from Spanish. */
    private fun leadsInSpanish(tokens: List<InputToken>, ctx: List<String> = emptyList()): Boolean =
        merged(tokens, ctx).tentative?.language == "es"

    private fun italianWords(tokens: List<InputToken>, ctx: List<String> = emptyList()) =
        italian().decode(tokens, ctx).map { it.word }

    @Test
    fun sudareGestureStaysItalian() {
        // A left swipe with contacts s,d,f,t,y,u,y,t,f,d,s,a,s,e,r,e after
        // "quindi", meant as "sudare"; "ayudarte" committed instead. Italian
        // ranks "state" first by score here too (the quindi->state bigram), and
        // its best fit is "sudare" at d=0.278. A whole-list swap read the heads,
        // it 0.954 against es 0.454 (pConf 0.512 < 0.75, oConf 0.688 > 0.589),
        // and swapped.
        val ctx = listOf("sempre", "quindi")
        val clean = listOf(TestData.swipe(CASE_A, g, 0, 1416, StreamId.LEFT))
        assertFalse("clean path led in Spanish", leadsInSpanish(clean, ctx))
        assertTrue(
            "Italian list must contain sudare: ${italianWords(clean, ctx)}",
            italianWords(clean, ctx).contains("sudare"),
        )
        val sloppy = listOf(TestData.sloppySwipe(CASE_A, g, 0, 1416, 0.4f, StreamId.LEFT))
        assertFalse("sloppy path led in Spanish", leadsInSpanish(sloppy, ctx))
    }

    @Test
    fun sareiGestureStaysItalianEndToEnd() {
        // A left swipe s,e,r,t,r,e with a simultaneous right tap of "i" 78 ms in,
        // meant as "sarei".
        // Top-1 depends on the tap's clock: 40-80 ms gives "sarei" and 81 ms
        // "siete", because SPLIT_MARGIN_MS is 80 and the mid-swipe split fires only
        // above it. So this asserts what holds at every offset: the editor never
        // takes a Spanish word, and "sarei" stays pickable. The sarei/siete
        // contest is
        // On device "sergei" committed, a word both dictionaries hold at the same
        // distance. A whole-list swap let the Spanish head "odette" (d=0.334) beat
        // the Italian head "sarei" (d=0.804) and threw the Italian list away.
        // Runs through WordComposer so the wiring is covered too.
        val cap = Capture()
        val composer = WordComposer(italian(), direct, direct, cap)
        composer.alternatePredictor = spanish()
        composer.commitWord("sudare")
        composer.onToken(TestData.swipe("sertre", g, 0, 700, StreamId.LEFT))
        composer.onToken(TestData.tap('i', g, 78, StreamId.RIGHT))
        assertEquals("the editor must not take a Spanish word", "it", cap.tentative?.language)
        assertTrue(
            "sarei must stay reachable: ${cap.candidates.map { it.word }}",
            cap.candidates.any { it.word == "sarei" },
        )
    }

    @Test
    fun theSareiBufferHoldsItsInvariantsAtEveryTapOffset() {
        // Keeps the edge above from being hidden again by a constant: the two claims
        // true of the gesture, asserted across the range a thumb can land in. The
        // reading may change with the offset (sarei to 80 ms, siete from 81, serie from
        // 300), but the language must not, and the word meant must stay reachable so a
        // pick can teach it.
        for (offset in listOf(40L, 60L, 78L, 81L, 90L, 200L, 300L, 500L)) {
            val cap = Capture()
            val composer = WordComposer(italian(), direct, direct, cap)
            composer.alternatePredictor = spanish()
            composer.commitWord("sudare")
            composer.onToken(TestData.swipe("sertre", g, 0, 700, StreamId.LEFT))
            composer.onToken(TestData.tap('i', g, offset, StreamId.RIGHT))
            assertEquals(
                "tap@$offset handed the editor a Spanish word",
                "it",
                cap.tentative?.language,
            )
            assertTrue(
                "tap@$offset lost sarei entirely: ${cap.candidates.map { it.word }}",
                cap.candidates.any { it.word == "sarei" },
            )
        }
    }

    @Test
    fun sieteGestureIsReachableAndSpanishLeadsOnlyOnTheReconstruction() {
        // "suerte" committed over the intended "siete".
        // Narrowed, like the scoring goldens: a reconstruction is a reachability
        // fixture, not a ranking fixture. On this polyline-through-key-centres path
        // Spanish "suerte" (d=0.188) fits better than Italian "siete" (d=0.301), so
        // the merged list leads in Spanish and rule 2 cannot say otherwise: the
        // foreign candidate is inside the informative zone and fits strictly better,
        // the positive evidence the rule asks for.
        // The device row differs: both dictionaries returned "siete" at d=0.229, so
        // the shared-word filter drops the Spanish entry and Italian leads by
        // construction. That row is pinned dictionary-free in
        // LanguagePreferenceTest.sieteGestureKeepsItalianAndSuerteNeverLeads. The gap
        // is the reconstruction bias: a path through exact key centres is cleaner
        // than the gesture and here flatters a word the real gesture never favoured.
        // What must hold on both is that the intended word stays reachable and one
        // tap away; a whole-list swap discarded the Italian list on a wrong decision
        // and the word had to be retyped.
        val ctx = listOf("sudare", "sergei")
        val tokens = listOf(TestData.swipe(CASE_C, g, 0, 1421, StreamId.LEFT))
        val m = merged(tokens, ctx)
        assertTrue(
            "siete must stay pickable: ${m.candidates.map { "${it.word}[${it.language}]" }}",
            m.candidates.any { it.word == "siete" && it.language == "it" },
        )
        assertEquals(
            "on the reconstruction Spanish fits better; see the device row",
            "suerte",
            m.tentative?.word,
        )
    }

    @Test
    fun italianWordsNeverHandOverToSpanish() {
        // The guarantee that matters for daily use: typing Italian must never
        // hand the editor to Spanish. 23 words x (clean, sloppy) = 46 decodes.
        //
        // With no ratio to clear the property is structural: an Italian word
        // bundled in both lists is dropped from the Spanish one by the
        // shared-word filter, and one bundled only in Italian is compared like
        // with like on fit. A ratio gate had 0.055 of headroom here (synthetic
        // paths at 1.0, device geometry up to 1.095). MergedRankingSweepTest
        // carries the same check at scale and in both directions.
        for (w in IT_WORDS) {
            for (sloppy in listOf(false, true)) {
                val tokens = listOf(swipeFor(w, sloppy))
                assertFalse("Italian '$w' (sloppy=$sloppy) led in Spanish", leadsInSpanish(tokens))
            }
        }
    }

    @Test
    fun bundledLoanwordsStayItalian() {
        // Borrowed words that live in the Italian asset must keep decoding from
        // Italian: the active language explains the path exactly (d=0.0).
        for (w in listOf("computer", "weekend", "internet", "film")) {
            val tokens = listOf(TestData.swipe(w, g, 0, 100L * w.length, StreamId.RIGHT))
            assertFalse("loanword '$w' led in Spanish", leadsInSpanish(tokens))
            assertEquals(w, merged(tokens).tentative?.word)
        }
    }

    @Test
    fun spanishOnlyWordsLeadTheMergedList() {
        // The other half of the contract: the Spanish word has to fit better,
        // nothing more. A whole-list swap needed the active language to have no
        // comparable explanation, much rarer between two Romance languages than a
        // foreign word, and reached only 23 of 38 rows.
        // "cuando" is the clearest case: Italian explains that path with "curando"
        // (d=0.132, see theRecallFixGivesItalianAnExplanationOfTheCuandoPath),
        // which capped the swap's ratio at 1.132, under its 1.15 margin. With no
        // ratio to clear, better Italian recall costs Spanish nothing.
        for (w in listOf("ayudarte", "trabajo", "siempre", "mujer", "nosotros", "cuando")) {
            val tokens = listOf(TestData.swipe(w, g, 0, 100L * w.length, StreamId.LEFT))
            val m = merged(tokens)
            assertEquals(
                "Spanish-only '$w' did not lead; merged list is " +
                    "${m.candidates.take(3).map { "${it.word}[${it.language}]" }}",
                w,
                m.tentative?.word,
            )
            assertEquals("es", m.tentative?.language)
        }
    }

    @Test
    fun theRecallFixGivesItalianAnExplanationOfTheCuandoPath() {
        // Italian holds "curando" at d=0.132 on the cuando path. Without the budget
        // split the same decode offered nothing better than d=0.39 ("citando" on the
        // device path), because the p-/c- subtree spent its whole slice on
        // DTW-abandoned words. If a change starves the subtree again, this goes red
        // first and names the reason.
        val tokens = listOf(TestData.swipe("cuando", g, 0, 600, StreamId.LEFT))
        val top3 = italian().decode(tokens, emptyList()).take(3)
        val curando = top3.firstOrNull { it.word == "curando" }
        assertTrue("curando missing from Italian's top-3: ${top3.map { it.word }}", curando != null)
        assertTrue("curando fit regressed: d=${curando!!.dtwDistance}", curando.dtwDistance < 0.15f)
    }

    @Test
    fun aForeignLeadCarriesItsLanguageAndLeavesItalianPickable() {
        // The learning guard depends on provenance: the committed word must be
        // attributable to a dictionary so KineticaIME learns it into that one,
        // not the active language ("sonore"/"imposte" got in that way).
        // The second assertion is the recoverability the merged ranking exists
        // for: Italian candidates survive a correct detection too, so a wrong one
        // is undone with a pick, not a retype.
        val cap = Capture()
        val composer = WordComposer(italian(), direct, direct, cap)
        composer.alternatePredictor = spanish()
        composer.onToken(TestData.swipe("nosotros", g, 0, 800, StreamId.LEFT))
        assertEquals("nosotros", cap.tentative?.word)
        assertEquals("es", cap.tentative?.language)
        assertTrue(
            "Italian must stay pickable: ${cap.candidates.map { "${it.word}[${it.language}]" }}",
            cap.candidates.any { it.language == "it" },
        )
    }

    private fun swipeFor(w: String, sloppy: Boolean) =
        if (sloppy) {
            TestData.sloppySwipe(w, g, 0, 100L * w.length, 0.4f, StreamId.LEFT)
        } else {
            TestData.swipe(w, g, 0, 100L * w.length, StreamId.LEFT)
        }

    private fun italian(): WordPredictor {
        val (dict, bigrams) = IT
        return WordPredictor(dict.trie, bigrams, g, dict.forms, language = "it")
    }

    private fun spanish(): WordPredictor {
        val (dict, bigrams) = ES
        return WordPredictor(dict.trie, bigrams, g, dict.forms, language = "es")
    }

    private companion object {
        /** Contact letters of the failing left-thumb swipes, verbatim from the trace. */
        const val CASE_A = "sdftyuytfdsasere"
        const val CASE_C = "sdftyuytrertre"

        val IT_WORDS = listOf(
            "sempre", "quindi", "interessante", "sudare", "sarei", "siete", "lei", "loro",
            "quando", "perche", "grazie", "domani", "lavoro", "casa", "tempo", "bene",
            "sono", "fare", "vedere", "portare", "mangiare", "lavorare", "necessario",
        )

        // Assets are loaded once per class: two wordlists plus two bigram
        // tables is ~6 MB of parsing, and every test needs both languages.
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
        val ES by lazy { load("es") }
    }
}
