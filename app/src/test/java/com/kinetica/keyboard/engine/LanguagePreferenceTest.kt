package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.WordCandidate
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-language candidate ranking (`WordComposer.merge`), pinned with the
 * exact candidate tuples captured on device.
 *
 * Dictionary-free on purpose (the StartSubtreeFairnessTest precedent): what is
 * under test is which candidate of which list may lead, so the regression must
 * be locked to that mechanism and not to a wordlist snapshot that a future
 * asset regeneration would shift out from under it. The real-asset end-to-end
 * behavior lives in LanguageDetectGoldenTest.
 *
 * Rewritten when the merged ranking replaced the swap. This class used to test
 * `preferAlternate`, a whole-list swap gated by LANG_DETECT_LOW_CONF and
 * LANG_DETECT_MARGIN. Measurement took that gate to its design limit - the
 * same-language and foreign confidence-ratio populations overlap in
 * [1.000, 1.095] on real geometry, so no threshold separates them, and a wrong
 * swap discarded the active language's candidates entirely. Both languages now
 * rank into ONE list. Every case below keeps its original evidence and comment;
 * the assertion changes from "does the swap fire" to "which candidate leads,
 * and is the other language still reachable in the bar".
 *
 * Two cases invert deliberately, each with its reason on the test:
 * [aForeignFitPastTheCapCannotLeadEvenWhenItsListHoldsABetterOne] and
 * [aHopelessForeignFitCannotLead] (the last half of the hopeless-decode
 * defect, which the merged ranking closes).
 */
class LanguagePreferenceTest {

    private val direct = Executor { it.run() }
    private val g = TestData.qwertyGeometry()

    /** A composer whose ACTIVE language ("it") dictionary holds exactly [words]. */
    private fun composerWithActiveWords(vararg words: String): WordComposer {
        val trie = Trie.build(if (words.isEmpty()) listOf("zzz" to 1) else words.map { it to 1000 })
        val predictor = WordPredictor(trie, BigramTable.EMPTY, g, language = ACTIVE)
        return WordComposer(predictor, direct, direct, NoCallbacks)
    }

    private object NoCallbacks : WordComposer.Callbacks {
        override fun onCandidates(
            candidates: List<WordCandidate>,
            tentative: WordCandidate?,
            literal: String,
            generation: Int,
        ) = Unit
    }

    /** Only word, score, dtwDistance and language matter to the merge. */
    private fun cand(word: String, d: Float, score: Float, lang: String): WordCandidate =
        WordCandidate(word, score, d, 0.5f, 1f, 0, WordCandidate.Source.MERGED, lang)

    private fun it(word: String, d: Float, score: Float) = cand(word, d, score, ACTIVE)
    private fun es(word: String, d: Float, score: Float) = cand(word, d, score, OTHER)

    private fun List<WordCandidate>.words() = map { it.word }

    @Test
    fun sudareGestureKeepsItalianDespiteFrequentPoorFitHead() {
        // Trace lines 69-73, intended "sudare". Italian rank 1 by score was
        // "state" (d=0.795, s=0.744 - carried there by the quindi->state bigram
        // bm=1.54 plus one personal commit), while the SAME list held "sudare"
        // at d=0.18. The old gate read the heads (pConf 0.557 vs oConf 0.699)
        // and swapped, so "ayudarte" committed and the whole Italian list was
        // discarded. Merged, Italian's own head simply outscores every Spanish
        // candidate and leads - and "sudare" stays reachable.
        //
        // That "state" heads Italian here is a scoring question, not a
        // language defect; the saturating geometric term is what fixed it.
        val composer = composerWithActiveWords("state", "sudare", "stare", "siate", "due")
        val italian = listOf(
            it("state", 0.795f, 0.744f),
            it("sudare", 0.18f, 0.619f),
            it("stare", 0.68f, 0.592f),
            it("siate", 0.41f, 0.576f),
            it("due", 1.29f, 0.51f),
        )
        val spanish = listOf(
            es("ayudarte", 0.43f, 0.505f),
            es("ayudare", 0.35f, 0.485f),
            es("diste", 0.58f, 0.438f),
        )
        val m = composer.merge(italian, spanish)
        assertEquals("Italian must lead", ACTIVE, m.tentative?.language)
        assertEquals("state", m.tentative?.word)
        assertTrue("sudare must stay pickable", m.candidates.words().contains("sudare"))
    }

    @Test
    fun sareiKeepsItalianWhenTheForeignLeadIsPastTheCap() {
        // Trace lines 81-85, intended "sarei" (developer-confirmed). Italian
        // rank 1 by score was "sarei" (d=1.066, s=0.364) with "sergei" right
        // behind at d=0.59/s=0.358; Spanish offered the very same "sergei" at
        // d=0.5999, scoring marginally higher on Spanish frequency. The old
        // gate swapped (0.484 vs 0.625), so "sergei" committed and "sarei" was
        // not even left in the strip.
        //
        // Merged, the Spanish "sergei" does head the ranking - and is demoted,
        // because at d=0.5999 it sits past GEO_SATURATION_KW: its score carries
        // no geometric information, only foreign frequency, and frequency alone
        // is not evidence that a word belongs to another language. "sarei"
        // leads, which is the intended word.
        //
        // The active-language filter deliberately does NOT carry this case: the
        // fixture's active dictionary does not contain "sergei".
        val composer = composerWithActiveWords("sarei", "aerei", "seri", "atei")
        val italian = listOf(
            it("sarei", 1.0657f, 0.364f),
            it("sergei", 0.59f, 0.358f),
            it("aerei", 0.85f, 0.344f),
            it("seri", 0.84f, 0.338f),
            it("atei", 0.59f, 0.305f),
        )
        val spanish = listOf(
            es("sergei", 0.5999f, 0.367f),
            es("ari", 1.22f, 0.277f),
            es("serguei", 0.78f, 0.276f),
        )
        val m = composer.merge(italian, spanish)
        assertEquals("demoted-past-cap", m.reason)
        assertEquals("sarei", m.tentative?.word)
        assertEquals(ACTIVE, m.tentative?.language)
    }

    @Test
    fun aWordTheActiveDictionaryAlreadyHasIsDroppedFromTheForeignList() {
        // The shared-word filter, isolated - the isWord veto, now applied
        // per candidate instead of to the list head. Real case: "sergei" is in
        // it_wordlist at 947 and es_wordlist at 1765. Keeping the Spanish entry
        // could only re-rank a word Italian already has by foreign frequency,
        // which is exactly how the swap used to lose the intended "sarei".
        // Here the Spanish "sergei" fits far better (d=0.2) and still must not
        // appear: Italian's own ranking of its own word already had its say.
        val composer = composerWithActiveWords("sarei", "sergei")
        val m = composer.merge(
            listOf(it("sarei", 1.0657f, 0.364f)),
            listOf(es("sergei", 0.2f, 0.367f)),
        )
        assertEquals("no-foreign", m.reason)
        assertEquals("sarei", m.tentative?.word)
        assertTrue("the foreign duplicate must be gone", !m.candidates.words().contains("sergei"))
    }

    @Test
    fun sieteGestureKeepsItalianAndSuerteNeverLeads() {
        // Trace lines 86-90 (unreported, repaired by hand from
        // the correction strip - ctx became [sergei, siete]). Intended "siete",
        // undershooting i to u. Italian rank 1 by score was the very frequent
        // "due" (d=0.579, fw=0.85) while "siete" sat at d=0.33 in the same
        // list; Spanish offered "suerte" at d=0.354. Old gate: 0.633 vs 0.739
        // -> SWAP.
        //
        // This is the row that pinned LANG_DETECT_MARGIN, and the one place a
        // merged list could plausibly steal a correctly-decoding Italian word:
        // "suerte" is Spanish-only, so the shared-word filter does not remove
        // it, and it fits marginally better than Italian's own "siete". It
        // stays in the bar and does not lead - Italian's head outscores it.
        // Spanish's own "siete" IS dropped, which is what keeps this row
        // behaving exactly as it did before the merged ranking.
        val composer = composerWithActiveWords("due", "dire", "siete", "sue", "she")
        val italian = listOf(
            it("due", 0.579f, 0.741f),
            it("dire", 0.68f, 0.641f),
            it("siete", 0.33f, 0.603f),
            it("sue", 0.57f, 0.545f),
            it("she", 0.79f, 0.466f),
        )
        val spanish = listOf(
            es("suerte", 0.354f, 0.577f),
            es("siete", 0.33f, 0.55f),
            es("dijiste", 0.56f, 0.497f),
        )
        val m = composer.merge(italian, spanish)
        assertEquals(ACTIVE, m.tentative?.language)
        assertEquals("due", m.tentative?.word)
        assertEquals(
            "Spanish's own siete is a shared word and must be dropped",
            1,
            m.candidates.words().count { it == "siete" },
        )
    }

    @Test
    fun foreignWordLeadsWhenTheActiveLanguageCannotExplainThePath() {
        // The feature must survive the rewrite: a word that is NOT in the
        // active dictionary leaves the active language with no geometric
        // explanation of the path. Under the swap this was the low-confidence
        // gate's job; under the merge it needs no gate at all - the foreign
        // candidate simply outscores everything Italian offers, and its own fit
        // (d=0.19) is well inside the informative zone, so it may lead.
        val composer = composerWithActiveWords("sudare", "state", "sarei")
        val m = composer.merge(
            listOf(it("sudare", 1.31f, 0.28f), it("state", 1.44f, 0.25f)),
            listOf(es("trabajo", 0.19f, 0.61f), es("trabaja", 0.28f, 0.44f)),
        )
        assertEquals("foreign-lead", m.reason)
        assertEquals("trabajo", m.tentative?.word)
        assertEquals(OTHER, m.tentative?.language)
        assertTrue("Italian stays pickable", m.candidates.words().contains("sudare"))
    }

    @Test
    fun aForeignFitPastTheCapCannotLeadEvenWhenItsListHoldsABetterOne() {
        // INVERTED deliberately, and the inversion is the point. This case used
        // to assert that a swap SHOULD fire because the secondary's best FIT
        // (trabajo, d=0.14) sits below its own score head - "judging both lists
        // on their best fit is the only symmetric comparison". True as far as
        // it went, but the swap then handed over the whole list, so the word
        // the user actually received was the head "gracias" (d=0.95), not the
        // good fit that justified the swap. That is precisely the defect the
        // old gate recorded at a device row where a swap justified by "mujer"
        // would have committed "me".
        //
        // Merged, there is no list to hand over. "gracias" leads on frequency
        // and is demoted at the cap; Italian's "state" takes the editor; and
        // "trabajo" - the fit that motivated the old swap - is right there in
        // the bar, one tap away. Nothing is lost and nothing wrong is committed.
        val composer = composerWithActiveWords("sudare", "state")
        val m = composer.merge(
            listOf(it("state", 0.9f, 0.4f)),
            listOf(es("gracias", 0.95f, 0.62f), es("trabajo", 0.14f, 0.31f)),
        )
        assertEquals("demoted-past-cap", m.reason)
        assertEquals("state", m.tentative?.word)
        assertTrue("the good foreign fit stays pickable", m.candidates.words().contains("trabajo"))
    }

    @Test
    fun deviceForeignGestureLeadsWhenNoItalianWordComesClose() {
        // A device row: "nosotros" swiped with Italian
        // active - one of only two rows in that whole capture where the old
        // gate got it right (ratio 1.361). Italian's entire list sits at
        // d >= 1.19 against Spanish "nosotros" at 0.29. Scores are the device's
        // own, restated under the saturating geometric term.
        val composer = composerWithActiveWords("nella", "bella", "novita", "miseria")
        val italian = listOf(
            it("nella", 1.69f, 0.2429f),
            it("bella", 1.84f, 0.2204f),
            it("notizia", 1.44f, 0.1734f),
            it("novità", 1.41f, 0.1839f),
            it("miseria", 1.19f, 0.1565f),
        )
        val spanish = listOf(
            es("nosotros", 0.29f, 0.3182f),
            es("nuestros", 1.18f, 0.1697f),
            es("nosotras", 0.94f, 0.1531f),
            es("jodidos", 0.69f, 0.1371f),
        )
        val m = composer.merge(italian, spanish)
        assertEquals("nosotros", m.tentative?.word)
        assertEquals(OTHER, m.tentative?.language)
    }

    @Test
    fun theMujerRowCommitsMujerAndKeepsItalianPickable() {
        // A device row, ctx [me, ne]. NOT a red-before-the-fix row, and
        // deliberately labelled as such: the old gate detected this one
        // correctly too (ratio 1.352). What is new is the second half of the
        // assertion - the swap replaced the WHOLE list, so Italian "me" was
        // thrown away and unrecoverable, leaving the user to retype it.
        //
        // It also carries device row 8's lesson without needing row 8's veto.
        // The Spanish list is itself headed by "me" on score (fw 0.93 at
        // d=0.91 beating fw 0.81 at d=0.27 - a scoring question inside Spanish), so a swap
        // justified by "mujer" would have committed "me". Here the shared word
        // simply drops out and "mujer" wins on its own fit.
        //
        // Not every mujer attempt in that capture is recovered, and the reason
        // is not language: at line 50 the same word decoded at d=0.407 against
        // an Italian "me" carrying a 1.61x personal boost, and 0.89 * 1.61
        // beats 0.81 on a geometric edge of only 1.33x. That is a scoring
        // question, recorded here rather than pinned
        // here, because a golden that pins a miss cements it.
        val composer = composerWithActiveWords("me", "mie", "notte", "mille", "nome")
        val italian = listOf(
            it("me", 0.91f, 0.3127f),
            it("mie", 0.78f, 0.2160f),
            it("nome", 1.25f, 0.2110f),
            it("notte", 1.04f, 0.2096f),
            it("mille", 0.99f, 0.1936f),
        )
        val spanish = listOf(
            es("mujer", 0.27f, 0.3317f),
            es("me", 0.91f, 0.2042f),
            es("muerte", 0.85f, 0.1707f),
            es("mike", 0.72f, 0.1598f),
            es("muere", 0.64f, 0.1534f),
        )
        val m = composer.merge(italian, spanish)
        assertEquals("mujer", m.tentative?.word)
        assertEquals(OTHER, m.tentative?.language)
        assertEquals("me", m.candidates[1].word)
        assertTrue("the shared Spanish me must be dropped", m.candidates.count { it.word == "me" } == 1)
    }

    @Test
    fun theAyudarteRowNowCommitsAyudarte() {
        // Red before the merged ranking. A device row: Italian holds a competing
        // explanation at d=0.310 ("sudare", personally reinforced to pb=1.27)
        // against Spanish "ayudarte" at 0.230, so the ratio is 1.063 and the
        // margin blocked the swap. This is the shape measurement proved unrecoverable
        // by tuning: between two Romance languages the active language almost
        // always has SOMETHING, so "the active language has nothing at all" -
        // which is all the gate could detect - is a much rarer event than a
        // foreign word.
        val composer = composerWithActiveWords("sudare", "stare", "siete", "siate", "aiutare")
        val italian = listOf(
            it("sudare", 0.31f, 0.3041f),
            it("siate", 0.37f, 0.2503f),
            it("stare", 0.77f, 0.2391f),
            it("siete", 0.76f, 0.2312f),
            it("aiutare", 0.46f, 0.1780f),
        )
        val spanish = listOf(
            es("ayudarte", 0.23f, 0.3311f),
            es("ayudaste", 0.25f, 0.2658f),
            es("ayudaré", 0.29f, 0.2527f),
            es("asusté", 0.26f, 0.2532f),
            es("ayudar", 0.41f, 0.2084f),
        )
        val m = composer.merge(italian, spanish)
        assertEquals("ayudarte", m.tentative?.word)
        assertTrue("sudare stays pickable", m.candidates.words().contains("sudare"))
    }

    @Test
    fun theCuandoRowNowCommitsCuando() {
        // Red before the merged ranking. A device row: ratio 1.047, blocked.
        // This row is also the recall fix's recorded cost - better recall gave Italian a
        // real explanation of the cuando path, which pushed the ratio further
        // below the margin and made the swap structurally unreachable
        // (LanguageDetectGoldenTest.theRecallFixGivesItalianAnExplanationOfThe-
        // CuandoPath). Ranking the languages together needs no ratio, so the
        // recall fix stops costing anything here.
        val composer = composerWithActiveWords(
            "chiedendo", "ciao", "cibando", "chiudendo", "chiamero",
        )
        val italian = listOf(
            it("ciao", 1.18f, 0.2454f),
            it("chiedendo", 0.56f, 0.1807f),
            it("cibando", 0.68f, 0.1542f),
            it("chiamero", 0.62f, 0.1395f),
            it("chiudendo", 0.48f, 0.1358f),
        )
        val spanish = listOf(
            es("cuando", 0.32f, 0.3067f),
            es("cuándo", 0.32f, 0.2722f),
            es("cuidando", 0.35f, 0.2068f),
            es("cuánto", 0.57f, 0.1699f),
            es("cuanto", 0.57f, 0.1644f),
        )
        val m = composer.merge(italian, spanish)
        assertEquals("cuando", m.tentative?.word)
        assertEquals(OTHER, m.tentative?.language)
    }

    @Test
    fun anEmptyActiveDecodeNeverHandsTheEditorToTheOtherLanguage() {
        // the hopeless-decode defect, and the reason the empty case is a rule of the
        // merge rather than a property of it. The old empty-primary rescue
        // ("anything beats an empty bar") committed "patéale" off two empty
        // "parlare" decodes and then poisoned the bigram context with it; a
        // one-line fix closed that by refusing the swap. A merged
        // list has no swap to refuse, so the same reasoning becomes rule 2's
        // first clause: with nothing from the active language there is nothing
        // to compare against, and an empty active decode is evidence the
        // gesture was undecodable, not evidence about language.
        //
        // The candidates are still returned - the bar shows them and they are
        // pickable. What must not happen is an automatic commit.
        val composer = composerWithActiveWords("parlare", "palese", "parlate")
        for (d in listOf(0.39504826f, 0.34980536f)) {
            val m = composer.merge(emptyList(), listOf(es("patéale", d, 0.349f)))
            assertNull("empty Italian decode must not commit patéale (d=$d)", m.tentative)
            assertEquals("no-native", m.reason)
            assertTrue("but it must stay pickable", m.candidates.words().contains("patéale"))
        }
    }

    @Test
    fun aHopelessForeignFitCannotLead() {
        // INVERTED: this used to assert the defect's remaining half was still
        // open, and pinned it so the residual stayed visible. The merged
        // ranking closes it.
        //
        // A device row, Spanish active: the
        // primary was not empty - "verdugo" at d=1.270 - so the empty-primary
        // rule never applied. The swap fired anyway, because pConf = 1/2.270 =
        // 0.44 clears LANG_DETECT_LOW_CONF trivially while the Italian best fit
        // ("cardini", 0.625) clears the 1.15 margin against it. Italian
        // "credimi" committed for a gesture NEITHER language could read.
        //
        // Merged, no foreign candidate here is inside the informative zone
        // (0.625 and 1.243 both past the cap), so none may lead: the active
        // language keeps the editor and the gesture stays visibly unresolved,
        // which is the honest outcome when nothing fits.
        val composer = composerWithActiveWords("verdugo", "cuando", "cariño")
        val m = composer.merge(
            listOf(it("verdugo", 1.2700267f, 0.31f)),
            listOf(es("credimi", 1.2432618f, 0.306f), es("cardini", 0.6245799f, 0.302f)),
        )
        assertEquals(ACTIVE, m.tentative?.language)
        assertEquals("verdugo", m.tentative?.word)
    }

    @Test
    fun anEmptyOtherLanguageDecodeChangesNothing() {
        val composer = composerWithActiveWords("sudare")
        val m = composer.merge(listOf(it("sudare", 1.4f, 0.2f)), emptyList())
        assertEquals("no-foreign", m.reason)
        assertNotNull(m.tentative)
        assertEquals("sudare", m.tentative?.word)
    }

    @Test
    fun theMergedListIsBoundedByTopK() {
        // Two full lists merge into one bar, not two. TOP_K is what the
        // suggestion bar paginates over, so the merged list must
        // respect it or the second page silently grows.
        val composer = composerWithActiveWords("zzz")
        val italian = (1..10).map { it("it$it", 0.2f, 1f - it * 0.01f) }
        val spanish = (1..10).map { es("es$it", 0.2f, 0.9f - it * 0.01f) }
        val m = composer.merge(italian, spanish)
        assertEquals(KineticaConstants.TOP_K, m.candidates.size)
    }

    private companion object {
        const val ACTIVE = "it"
        const val OTHER = "es"
    }
}
