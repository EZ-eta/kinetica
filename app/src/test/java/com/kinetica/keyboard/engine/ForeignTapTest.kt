package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.TapToken
import com.kinetica.keyboard.engine.models.WordCandidate
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A tap read on another script's board, between a language switch and its dictionary loading,
 * carries a code past the loaded alphabet. Spelling it out as the literal indexed past the
 * alphabet's letters and threw on the main thread.
 */
class ForeignTapTest {

    private val direct = Executor { it.run() }

    private class Inline : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    private class Capture : WordComposer.Callbacks {
        var literal: String? = null
        override fun onCandidates(candidates: List<WordCandidate>, tentative: WordCandidate?, literal: String, generation: Int) {
            this.literal = literal
        }
    }

    @Test
    fun aTapPastTheAlphabetLeavesNoLiteralAndNoCrash() {
        val g = TestData.qwertyGeometry()
        val p = WordPredictor(Trie.build(listOf("the" to 100)), BigramTable.EMPTY, g)
        val cap = Capture()
        val c = WordComposer(p, direct, direct, cap, Inline(), Inline())
        c.onToken(TapToken(StreamId.LEFT, 40, 10f, 10f, false, 0L, 50L))
        assertEquals("", cap.literal ?: "")
    }
}
