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
