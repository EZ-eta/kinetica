package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.WordCandidate
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No primary language (Prefs.NO_PRIMARY): the resident languages rank as one list,
 * each word weighed by how much its language is being typed (LanguageMomentum).
 */
class EqualFootingTest {

    private val direct = Executor { it.run() }
    private val g = TestData.qwertyGeometry()

    private class Inline : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    private class Capture : WordComposer.Callbacks {
        var candidates: List<WordCandidate> = emptyList()
        var tentative: WordCandidate? = null
        override fun onCandidates(candidates: List<WordCandidate>, tentative: WordCandidate?, literal: String, generation: Int) {
            this.candidates = candidates
            this.tentative = tentative
        }
    }

    private fun cand(word: String, score: Float, lang: String) =
        WordCandidate(word, score, 0.1f, 0.5f, 1f, 0, WordCandidate.Source.MERGED, lang)

    private fun composer(cap: Capture = Capture()): WordComposer {
        val active = WordPredictor(Trie.build(listOf("tee" to 100, "tenne" to 80)), BigramTable.EMPTY, g, language = "it")
        return WordComposer(active, direct, direct, cap, Inline(), Inline())
    }

    @Test
    fun theFrontRunnerFollowsWhatIsCommitted() {
        val m = LanguageMomentum()
        val langs = listOf("it", "en")
        assertEquals(mapOf("it" to 1f, "en" to 1f), m.weightsFor(langs))
        repeat(6) { m.observe(mapOf("it" to 0f, "en" to 0.8f)) }
        assertEquals("en", m.front())
        val w = m.weightsFor(langs)
        assertEquals(1f, w.getValue("en"), 0f)
        assertEquals(KineticaConstants.MOMENTUM_DAMPED_WEIGHT, w.getValue("it"), 0.001f)
        // Italian words bring Italian back to the front.
        repeat(8) { m.observe(mapOf("it" to 0.8f, "en" to 0f)) }
        assertEquals("it", m.front())
        m.forget()
        assertEquals(mapOf("it" to 1f, "en" to 1f), m.weightsFor(langs))
    }

    @Test
    fun aLanguageThatLeftCannotLeadTheOnesThatStayed() {
        // Polish standing left over from earlier typing led 58 of 163 momentum lines with
        // only Italian and English resident, and damped both alike.
        val m = LanguageMomentum()
        repeat(8) { m.observe(mapOf("pl" to 0.9f, "en" to 0.1f)) }
        m.observe(mapOf("it" to 0.6f, "en" to 0.2f))
        val w = m.weightsFor(listOf("it", "en"))
        assertEquals(1f, w.getValue("it"), 0f)
        assertTrue("${w["en"]}", w.getValue("en") < 1f)
        assertEquals("it", m.front(listOf("it", "en")))
        m.retain(listOf("it", "en"))
        assertEquals("it", m.front())
    }

    @Test
    fun aCloseRaceKeepsBothLanguagesNearEqual() {
        val m = LanguageMomentum()
        m.observe(mapOf("it" to 0.60f, "en" to 0.55f))
        val w = m.weightsFor(listOf("it", "en"))
        assertTrue("${w["en"]}", w.getValue("en") > 0.85f)
    }

    @Test
    fun theHeadLeadsWhateverItsLanguage() {
        // `understanding` under Italian: nothing Italian fits as well, so it commits itself.
        val c = composer()
        val m = c.equalFootingMerge(
            listOf(listOf(cand("undersea", 0.30f, "it")), listOf(cand("understanding", 0.55f, "en"))),
            mapOf("it" to 1f, "en" to 1f),
        )
        assertEquals("understanding", m.tentative?.word)
        assertEquals("equal", m.reason)
        assertEquals(listOf("understanding", "undersea"), m.candidates.map { it.word })
    }

    @Test
    fun aDampedLanguageNeedsAClearlyBetterWord() {
        val c = composer()
        val lists = listOf(listOf(cand("porta", 0.50f, "it")), listOf(cand("port", 0.55f, "en")))
        assertEquals("port", c.equalFootingMerge(lists, mapOf("it" to 1f, "en" to 1f)).tentative?.word)
        assertEquals("porta", c.equalFootingMerge(lists, mapOf("it" to 1f, "en" to 0.6f)).tentative?.word)
    }

    @Test
    fun aSwipeTakesTheEqualPathOnlyWithWeights() {
        val cap = Capture()
        val c = composer(cap)
        c.alternatePredictor = WordPredictor(TestData.smallDictionary(), BigramTable.EMPTY, g, language = "en")
        val lines = java.util.Collections.synchronizedList(ArrayList<String>())
        DecodeTrace.sink = { lines.add(it) }
        try {
            c.onToken(TestData.sloppySwipe("the", g, 0, 300, stream = StreamId.RIGHT))
            assertTrue(lines.none { it.startsWith("merge equal") })
            c.clear()
            lines.clear()
            c.languageWeights = mapOf("it" to 1f, "en" to 1f)
            c.onToken(TestData.sloppySwipe("the", g, 1_000, 300, stream = StreamId.RIGHT))
        } finally {
            DecodeTrace.sink = null
        }
        assertTrue("no equal merge in $lines", lines.any { it.startsWith("merge equal") })
        assertEquals("the", cap.tentative?.word)
        assertEquals("en", cap.tentative?.language)
    }

    @Test
    fun aMoreFrequentForeignWordLeadsHereAndNotInThePairwiseMerge() {
        // The case the mode exists for: the Italian word fits a little closer, the English one
        // is the word meant and far more common. Pairwise keeps Italian (no better fit).
        val c = composer()
        val it = WordCandidate("tenne", 0.30f, 0.20f, 0.5f, 1f, 0, WordCandidate.Source.MERGED, "it")
        val en = WordCandidate("then", 0.60f, 0.25f, 0.5f, 1f, 0, WordCandidate.Source.MERGED, "en")
        assertEquals("tenne", c.merge(listOf(it), listOf(en)).tentative?.word)
        assertEquals("then", c.equalFootingMerge(listOf(listOf(it), listOf(en)), mapOf("it" to 1f, "en" to 1f)).tentative?.word)
    }

    @Test
    fun aTappedWordKeepsThePairwiseMerge() {
        // Taps feed autocorrect, and the equal ranking was never measured on them.
        val cap = Capture()
        val c = composer(cap)
        c.alternatePredictor = WordPredictor(TestData.smallDictionary(), BigramTable.EMPTY, g, language = "en")
        c.languageWeights = mapOf("it" to 1f, "en" to 1f)
        c.onToken(TestData.tap('t', g, 0))
        c.onToken(TestData.tap('e', g, 100))
        c.onToken(TestData.tap('e', g, 200))
        assertEquals("tee", cap.tentative?.word)
        assertEquals("it", cap.tentative?.language)
    }

    @Test
    fun withoutASecondLanguageNothingChanges() {
        val cap = Capture()
        val c = composer(cap)
        c.languageWeights = mapOf("it" to 1f)
        c.onToken(TestData.sloppySwipe("tee", g, 0, 300, stream = StreamId.RIGHT))
        assertEquals("tee", cap.tentative?.word)
        c.clear()
        assertNull(c.alternatePredictor)
    }
}
