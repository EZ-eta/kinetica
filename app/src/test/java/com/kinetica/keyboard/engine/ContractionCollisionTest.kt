package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.WordCandidate
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * An English contraction against another language's plain word on the same letters: Polish and
 * German `im` and `i'm`, French `dont` and `don't`, Dutch `lets` and `let's`.
 *
 * The apostrophe is free in the decoder, so both spellings fit the gesture equally. The merge
 * gives the word to the language being typed: its own spelling leads when it is active, and in
 * follow mode when it is in front. With English active or in front, English's own frequencies
 * decide and the contraction leads. On real dictionaries, so a merge change that moves any of
 * these shows here.
 */
class ContractionCollisionTest {

    private val g = TestData.qwertyGeometry()
    private val direct = Executor { it.run() }

    private class Inline : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    private class NoCallbacks : WordComposer.Callbacks {
        override fun onCandidates(candidates: List<WordCandidate>, tentative: WordCandidate?, literal: String, generation: Int) = Unit
    }

    private val predictors = HashMap<String, WordPredictor>()

    private fun predictor(lang: String): WordPredictor = predictors.getOrPut(lang) {
        val p = listOf(
            Paths.get("src/main/assets/dictionaries/${lang}_wordlist.txt"),
            Paths.get("app/src/main/assets/dictionaries/${lang}_wordlist.txt"),
        ).firstOrNull { Files.exists(it) }
        assumeTrue("$lang wordlist not found", p != null)
        val d = Files.newBufferedReader(p!!).use { DictionaryLoader.load(it) }
        WordPredictor(d.trie, BigramTable.EMPTY, g, d.forms, language = lang)
    }

    private val cases = listOf(
        Triple("pl", "im", "i'm"),
        Triple("de", "im", "i'm"),
        Triple("fr", "dont", "don't"),
        Triple("nl", "lets", "let's"),
    )

    private fun pairwise(active: WordPredictor, other: WordPredictor, word: String): String? {
        val tokens = listOf(TestData.sloppySwipe(word, g, 0L, 300L))
        val c = WordComposer(active, direct, direct, NoCallbacks(), Inline(), Inline())
        return c.merge(active.decode(tokens, emptyList()), other.decode(tokens, emptyList(), beam = false)).tentative?.word
    }

    private fun following(lang: String, front: String, word: String): String? {
        val tokens = listOf(TestData.sloppySwipe(word, g, 0L, 300L))
        val native = predictor(lang)
        val en = predictor("en")
        val weights = mapOf(front to 1f, (if (front == "en") lang else "en") to KineticaConstants.MOMENTUM_DAMPED_WEIGHT)
        val c = WordComposer(native, direct, direct, NoCallbacks(), Inline(), Inline())
        val lists = listOf(native.decode(tokens, emptyList(), beam = true), en.decode(tokens, emptyList(), beam = true))
        return c.equalFootingMerge(lists, weights).tentative?.word
    }

    @Test
    fun theLanguageBeingTypedKeepsItsPlainWord() {
        for ((lang, plain, _) in cases) {
            assertEquals("$lang active", plain, pairwise(predictor(lang), predictor("en"), plain))
            assertEquals("$lang in front", plain, following(lang, lang, plain))
        }
    }

    @Test
    fun englishInFrontKeepsItsContraction() {
        for ((lang, plain, contraction) in cases) {
            assertEquals("en active over $lang", contraction, pairwise(predictor("en"), predictor(lang), plain))
            assertEquals("en in front of $lang", contraction, following(lang, "en", plain))
        }
    }
}
