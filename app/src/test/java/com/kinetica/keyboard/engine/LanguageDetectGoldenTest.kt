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
 * active - the configuration in which three swipe words committed in Spanish.
 *
 * The failing buffers are rebuilt from the `keys=` contact letters the trace
 * prints, which is what that field was added for: a captured gesture is
 * reconstructible as a fixture. The reconstruction is a polyline through those
 * contacts' key centers, so distances differ from the device's by a few
 * hundredths - the mechanism, not the exact number, is what these lock. The
 * device's own candidate tuples are pinned separately and dictionary-free in
 * LanguagePreferenceTest.
 *
 * Rewritten when the whole-list swap became one ranked
 * list. The question each case asks is unchanged - would this gesture hand the
 * editor to the wrong language - but it is now asked of the merged list's lead
 * rather than of a swap decision, and the losing language stays pickable
 * instead of being discarded.
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
        // Trace lines 69-73: LEFT swipe with contacts s,d,f,t,y,u,y,t,f,d,s,a,s,
        // e,r,e after committing "quindi", intending "sudare"; "ayudarte"
        // committed instead. Italian ranks "state" first by score here too (the
        // quindi->state bigram), and its best FIT is "sudare" at d=0.278.
        // Pre-fix the gate read the heads - it 0.954 vs es 0.454, so
        // pConf 0.512 < 0.75 and oConf 0.688 > 0.589 - and swapped.
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
        // Trace lines 81-85: LEFT swipe s,e,r,t,r,e with a simultaneous RIGHT
        // tap of "i" 78 ms in, intending "sarei" (developer-confirmed).
        // "sergei" committed - a word BOTH dictionaries hold at the same
        // distance, so the swap could not add information. Pre-fix the Spanish
        // head "odette" (d=0.334) beat the Italian head "sarei" (d=0.804) and
        // the whole Italian list, "sarei" included, was thrown away.
        // Runs through WordComposer so the wiring is covered too.
        val cap = Capture()
        val composer = WordComposer(italian(), direct, direct, cap)
        composer.alternatePredictor = spanish()
        composer.commitWord("sudare")
        composer.onToken(TestData.swipe("sertre", g, 0, 700, StreamId.LEFT))
        composer.onToken(TestData.tap('i', g, 78, StreamId.RIGHT))
        assertEquals("the editor must not take a Spanish word", "it", cap.tentative?.language)
        assertEquals(
            "top-1 lost to ${cap.candidates.take(3).map { it.word }}",
            "sarei",
            cap.tentative?.word,
        )
    }

    @Test
    fun sieteGestureIsReachableAndSpanishLeadsOnlyOnTheReconstruction() {
        // Trace lines 86-90 (unreported; repaired from the correction strip,
        // so ctx became [sergei, siete]): "suerte" committed
        // over the intended "siete".
        //
        // Narrowed deliberately, on the same precedent as the scoring goldens: a
        // reconstruction is a reachability fixture, not a ranking fixture.
        // On this polyline-through-key-centres path Spanish "suerte" (d=0.188)
        // genuinely fits better than Italian "siete" (d=0.301), so the merged
        // list leads in Spanish and rule 2 cannot say otherwise - the foreign
        // candidate is inside the informative zone AND fits strictly better,
        // which is exactly the positive evidence the rule asks for.
        //
        // The DEVICE row does not behave that way, and it is what this gesture
        // actually does in use: both dictionaries returned "siete" at d=0.229
        // (measured), so the shared-word filter
        // drops the Spanish entry and Italian leads by construction. That row
        // is pinned dictionary-free in
        // LanguagePreferenceTest.sieteGestureKeepsItalianAndSuerteNeverLeads.
        // The gap between the two is the measured reconstruction bias: a path
        // through exact key centres is cleaner than the
        // gesture it stands for, and here it flatters a word the real gesture
        // never favoured.
        //
        // What must hold on both is that the intended word stays REACHABLE and
        // one tap away, which is what the merged list buys and the swap did
        // not: under the old gate a wrong decision discarded the Italian list
        // wholesale and this word had to be retyped.
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
        // This used to be a statement about LANG_DETECT_MARGIN's headroom, and
        // the headroom was the problem: these synthetic paths ceiling at a ratio
        // of exactly 1.0 (a word bundled in both dictionaries gets identical
        // geometry from both) while device-reconstructed geometry reached 1.095,
        // leaving 0.055 - which is why the gate was declared at its design
        // limit. With no ratio to clear the property is structural instead: an
        // Italian word bundled in both lists is dropped from the Spanish one by
        // the shared-word filter, and one bundled only in Italian is compared
        // like with like on fit. MergedRankingSweepTest carries the same check
        // at scale and in both directions.
        for (w in IT_WORDS) {
            for (sloppy in listOf(false, true)) {
                val tokens = listOf(swipeFor(w, sloppy))
                assertFalse("Italian '$w' (sloppy=$sloppy) led in Spanish", leadsInSpanish(tokens))
            }
        }
    }

    @Test
    fun bundledLoanwordsStayItalian() {
        // Borrowed words that live IN the Italian asset must keep decoding from
        // Italian: the gate asks whether the active language can explain the
        // path, and here it explains it exactly (d=0.0).
        for (w in listOf("computer", "weekend", "internet", "film")) {
            val tokens = listOf(TestData.swipe(w, g, 0, 100L * w.length, StreamId.RIGHT))
            assertFalse("loanword '$w' led in Spanish", leadsInSpanish(tokens))
            assertEquals(w, merged(tokens).tentative?.word)
        }
    }

    @Test
    fun spanishOnlyWordsLeadTheMergedList() {
        // The other half of the contract. Under the swap this needed the active
        // language to have NO comparable explanation - a much rarer event
        // between two Romance languages than a foreign word is, which is why
        // the sweep measured detection at only 23 of 38 rows.
        // Ranking the languages together asks nothing of the sort: the Spanish
        // word simply has to fit better.
        //
        // "cuando" is back on this list, and it is the clearest evidence of the
        // difference. It was removed once the recall fix
        // gave Italian a genuine explanation of that path ("curando", d=0.132,
        // see theRecallFixGivesItalianAnExplanationOfTheCuandoPath), capping the
        // achievable ratio at 1.132 - under the old 1.15 margin, so the swap
        // became structurally unreachable. With no ratio to clear, better
        // Italian recall stops costing Spanish anything.
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
        // The budget split is what removed "cuando" from the swap list above,
        // and this locks the CAUSE rather than the consequence: Italian holds
        // "curando" at d=0.132 on that path. Pre-fix the same decode offered
        // nothing better than d=0.39 ("citando" on the device path), because the
        // p-/c- subtree spent its whole slice on DTW-abandoned words.
        //
        // Before the merged ranking this number was also what made the cuando swap
        // unreachable: pConf = 1/1.132 = 0.883 capped the best ratio ANY Spanish
        // word could reach at 1.132, below the 1.15 margin. The merged list has
        // no ratio, so that cost is gone (spanishOnlyWordsLeadTheMergedList
        // covers cuando again) - but the recall property itself still matters,
        // so this stays: if a future change starves the subtree again, this test
        // goes red first and names the reason.
        val tokens = listOf(TestData.swipe("cuando", g, 0, 600, StreamId.LEFT))
        val top3 = italian().decode(tokens, emptyList()).take(3)
        val curando = top3.firstOrNull { it.word == "curando" }
        assertTrue("curando missing from Italian's top-3: ${top3.map { it.word }}", curando != null)
        assertTrue("curando fit regressed: d=${curando!!.dtwDistance}", curando.dtwDistance < 0.15f)
    }

    @Test
    fun aForeignLeadCarriesItsLanguageAndLeavesItalianPickable() {
        // The learning guard depends on provenance, not on a swap flag: the
        // committed word must be attributable to a dictionary so KineticaIME
        // learns it into that one rather than into the active language (the
        // "sonore"/"imposte" poisoning captured on a live trace).
        //
        // The second assertion is the recoverability property the merged
        // ranking exists for: the swap used to discard
        // the Italian list wholesale, so a wrong detection could only be undone
        // by retyping. Italian candidates now survive a correct detection too.
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
        /** Contact letters of the failing LEFT swipes, verbatim from the trace. */
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
