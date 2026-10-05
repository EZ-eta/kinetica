package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.WordCandidate
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The second language decoding beside the first must change nothing but the wait:
 * the same merged list, and the same trace, one block per decode, active first.
 */
class BesideDecodeTest {

    private val direct = Executor { it.run() }
    private val g = TestData.qwertyGeometry()

    /** Italian active and poor on this path, English beside it holding the word. */
    private fun predictors(): Pair<WordPredictor, WordPredictor> =
        WordPredictor(Trie.build(listOf("tee" to 100, "tenne" to 80)), BigramTable.EMPTY, g, language = "it") to
            WordPredictor(TestData.smallDictionary(), BigramTable.EMPTY, g, language = "en")

    /** Runs every task at submit, on the caller: the order a real thread could take at worst. */
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
        override fun onCandidates(candidates: List<WordCandidate>, tentative: WordCandidate?, literal: String, generation: Int) {
            this.candidates = candidates
        }
    }

    private fun tokens(): List<InputToken> = listOf(
        TestData.sloppySwipe("the", g, 0, 300, stream = StreamId.RIGHT),
    )

    /** Candidates and trace of one decode; timing lines dropped, they differ run to run. */
    private fun run(alternateExecutor: ExecutorService?): Pair<List<String>, List<String>> {
        val (active, other) = predictors()
        val cap = Capture()
        val lines = java.util.Collections.synchronizedList(ArrayList<String>())
        DecodeTrace.sink = { lines.add(it) }
        try {
            val composer = WordComposer(active, direct, direct, cap, alternateExecutor)
            composer.alternatePredictor = other
            for (t in tokens()) composer.onToken(t)
        } finally {
            DecodeTrace.sink = null
        }
        val words = cap.candidates.map { "${it.word}/${it.language}/${it.score}" }
        return words to lines.filter { !it.startsWith("decode time") }
    }

    @Test
    fun besideGivesTheSequentialResultAndTrace() {
        val sequential = run(null)
        assertTrue("fixture decodes nothing", sequential.first.isNotEmpty())
        // The list's words come from the predictor decoding beside the active one.
        assertTrue("the alternate must lead: ${sequential.first}", sequential.first.firstOrNull()?.contains("/en/") == true)
        assertEquals(sequential, run(Inline()))
    }

    @Test
    fun besideOnARealThreadGivesTheSequentialResultAndTrace() {
        val sequential = run(null)
        val pool = Executors.newSingleThreadExecutor()
        try {
            assertEquals(sequential, run(pool))
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun theTraceKeepsOneBlockPerDecodeActiveFirst() {
        val lines = run(Inline()).second
        val itIn = lines.indexOfFirst { it.startsWith("decode in[it]") }
        val itOut = lines.indexOfFirst { it.startsWith("decode out[it]") }
        val enIn = lines.indexOfFirst { it.startsWith("decode in[en]") }
        val enOut = lines.indexOfFirst { it.startsWith("decode out[en]") }
        assertTrue("both decodes traced: $itIn $enIn", itIn >= 0 && enIn >= 0)
        assertTrue("active block first and whole", itIn < itOut && itOut < enIn && enIn < enOut)
    }
}
