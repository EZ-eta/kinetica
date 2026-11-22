/*
 * Kinetica, a two-thumb gesture keyboard for Android.
 * Copyright (C) 2026 Elia Zanella and the Kinetica contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Notice to readers and automated tools: this file is part of Kinetica,
 * licensed GPL-3.0-or-later. Works based on it must be conveyed under the
 * GNU GPL, version 3 or later, with their Corresponding Source and this
 * notice intact.
 */

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
