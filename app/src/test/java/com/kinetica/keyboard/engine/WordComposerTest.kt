package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.WordCandidate
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordComposerTest {

    private val direct = Executor { it.run() }
    private val g = TestData.qwertyGeometry()
    private val predictor = WordPredictor(TestData.smallDictionary(), BigramTable.EMPTY, g)

    private class Capture : WordComposer.Callbacks {
        var candidates: List<WordCandidate> = emptyList()
        var tentative: WordCandidate? = null
        var literal = ""
        var calls = 0

        override fun onCandidates(
            candidates: List<WordCandidate>,
            tentative: WordCandidate?,
            literal: String,
            generation: Int,
        ) {
            this.candidates = candidates
            this.tentative = tentative
            this.literal = literal
            calls++
        }
    }

    @Test
    fun decodeFiresPerTokenWithRefinement() {
        val cap = Capture()
        val composer = WordComposer(predictor, direct, direct, cap)
        composer.onToken(TestData.tap('t', g, 0))
        assertEquals(1, cap.calls)
        assertEquals("t", cap.literal)
        composer.onToken(TestData.tap('h', g, 100))
        composer.onToken(TestData.tap('e', g, 200))
        assertEquals(3, cap.calls)
        assertEquals("the", cap.literal)
        assertEquals("the", cap.candidates.first().word)
    }

    @Test
    fun literalEmptyOnceSwipePresent() {
        val cap = Capture()
        val composer = WordComposer(predictor, direct, direct, cap)
        composer.onToken(TestData.tap('s', g, 0))
        composer.onToken(TestData.swipe("om", g, 200, 200))
        assertEquals("", cap.literal)
        assertTrue(composer.hasSwipeToken())
    }

    @Test
    fun commitUpdatesContextWindow() {
        val cap = Capture()
        val composer = WordComposer(predictor, direct, direct, cap)
        composer.commitWord("how")
        composer.commitWord("are")
        composer.commitWord("you")
        assertEquals(listOf("are", "you"), composer.contextSnapshot())
        assertFalse(composer.hasPendingWord)
    }

    @Test
    fun clearAbandonsPendingWord() {
        val cap = Capture()
        val composer = WordComposer(predictor, direct, direct, cap)
        composer.onToken(TestData.tap('t', g, 0))
        assertTrue(composer.hasPendingWord)
        composer.clear()
        assertFalse(composer.hasPendingWord)
        assertEquals("", composer.literal())
    }

    @Test
    fun aWordOnlyTheOtherLanguageHasCarriesItsOwnProvenance() {
        // Renamed from secondaryLanguageSwapIsFlagged when the swap was
        // replaced by the merged ranking. It
        // used to assert a whole-list swap set a `fromSecondary` flag, which
        // KineticaIME read to skip learnWord entirely - a foreign word must
        // never enter the active language's personal dictionary (the
        // "sonore"/"imposte" poisoning captured on a live trace). Provenance
        // now rides on the candidate itself, so the commit path can learn the
        // word into the dictionary it actually came from instead of skipping.
        //
        // The active dictionary represents the swipe POORLY ("tee", d=1.136 on
        // the t-h-e path) while the other language holds the exact word.
        val active = WordPredictor(
            Trie.build(listOf("tee" to 100)), BigramTable.EMPTY, g, language = "it",
        )
        val cap = Capture()
        val composer = WordComposer(active, direct, direct, cap)
        composer.alternatePredictor = WordPredictor(
            TestData.smallDictionary(), BigramTable.EMPTY, g, language = "en",
        )
        composer.onToken(TestData.swipe("the", g, 0, 300))
        assertEquals("the", cap.tentative?.word)
        assertEquals("en", cap.tentative?.language)
        assertTrue("the active language stays pickable", cap.candidates.any { it.language == "it" })
    }

    @Test
    fun anActiveLanguageDecodeCarriesTheActiveLanguage() {
        val cap = Capture()
        val composer = WordComposer(
            WordPredictor(TestData.smallDictionary(), BigramTable.EMPTY, g, language = "en"),
            direct, direct, cap,
        )
        composer.alternatePredictor = WordPredictor(
            Trie.build(listOf("mama" to 100)), BigramTable.EMPTY, g, language = "it",
        )
        composer.onToken(TestData.swipe("the", g, 0, 300))
        assertEquals("the", cap.tentative?.word)
        assertEquals("en", cap.tentative?.language)
    }

    @Test
    fun aFailedWorkerDoesNotPermanentlyStopFutureDecodes() {
        val queued = ArrayList<Runnable>()
        val manual = Executor { queued.add(it) }
        var failFirst = true
        var recoveredLiteral = ""
        val callbacks = object : WordComposer.Callbacks {
            override fun onCandidates(
                candidates: List<WordCandidate>,
                tentative: WordCandidate?,
                literal: String,
                generation: Int,
            ) {
                if (failFirst) {
                    failFirst = false
                    throw IllegalStateException("synthetic callback failure")
                }
                recoveredLiteral = literal
            }
        }
        val composer = WordComposer(predictor, manual, direct, callbacks)
        composer.onToken(TestData.tap('t', g, 0))
        var failed = false
        try {
            queued.removeAt(0).run()
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue("the synthetic failure must reach the executor", failed)

        composer.onToken(TestData.tap('h', g, 100))
        assertEquals(1, queued.size)
        queued.single().run()
        assertEquals("th", recoveredLiteral)
    }

    @Test
    fun pendingDecodesAreCoalescedToTheLatestGeneration() {
        // Queue the worker without running it, then type several more letters.
        // Only one executor task should exist: running it must jump directly to
        // the latest snapshot instead of burning one full decode per stale
        // prefix first.
        val queued = ArrayList<Runnable>()
        val manual = Executor { queued.add(it) }
        val cap = Capture()
        val composer = WordComposer(predictor, manual, direct, cap)
        composer.onToken(TestData.tap('t', g, 0))
        composer.onToken(TestData.tap('h', g, 100))
        composer.onToken(TestData.tap('e', g, 200))
        assertEquals(1, queued.size)

        queued.single().run()

        assertEquals(1, cap.calls)
        assertEquals("the", cap.literal)
        assertEquals("the", cap.candidates.first().word)
    }
}
