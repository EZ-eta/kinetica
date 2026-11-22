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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for start-subtree starvation, found on "siempre": emit attempts count against one
 * global budget (MAX_CANDIDATES) while children are explored frequency-first, so a giant
 * start-letter subtree beside the path start (es: d-, holding "de") could spend the whole
 * budget before the intended word's subtree was visited. The budget is sliced across admissible
 * start subtrees so the cap bites within a subtree, never across one.
 *
 * Synthetic so the lock is dictionary-independent: a d- subtree stuffed with hundreds of
 * path-admissible fillers, all ahead of the s- subtree in frequency order, must not starve the
 * exact-overlay target word.
 */
class StartSubtreeFairnessTest {

    @Test
    fun dominantNeighborSubtreeDoesNotStarveTheTargetWord() {
        val g = TestData.qwertyGeometry()
        // Fillers: d + four letters near the s-i-e-m-p-r-e path + closing e
        // (start d is within R_ENDPOINT of the s start; the middles are path
        // passes; the final e closes at the path end). Enough order-valid
        // combinations close and emit to exhaust the global budget before
        // the s- subtree is visited.
        val near = "iemproukn"
        val fillers = ArrayList<Pair<String, Int>>()
        var f = 2000
        for (a in near) for (b in near) for (c in near) for (d in near) {
            fillers.add("d$a$b$c${d}e" to f)
            f++
        }
        val words = fillers + listOf("siempre" to 1000)
        assertTrue("need more fillers than the emit budget",
            fillers.size > KineticaConstants.MAX_CANDIDATES)
        val predictor = WordPredictor(Trie.build(words), BigramTable.EMPTY, g)
        val result = predictor.decode(
            listOf(TestData.swipe("siempre", g, 0, 700)), emptyList(),
        )
        assertTrue("decode came back empty", result.isNotEmpty())
        assertEquals(
            "exact-overlay target lost to ${result.take(3).map { it.word }}",
            "siempre", result[0].word,
        )
    }
}
