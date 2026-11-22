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

package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.models.WordCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the retype button does to the word it just rejected.
 *
 * Of 78 retype presses in one capture, 12 handed back the word just rejected, but the wanted
 * word was among the alternates in only 5 of the 12. So this demotes
 * instead of hiding, and it is off by default.
 */
class RetypeDemoteTest {

    private fun cand(word: String, score: Float) = WordCandidate(
        word, score, 0.2f, 0.5f, 1f, 0, WordCandidate.Source.MERGED, "en",
    )

    private val list = listOf(cand("word", 0.9f), cand("world", 0.7f), cand("wold", 0.4f))

    @Test
    fun theRejectedWordGoesLastAndTheRunnerUpLeads() {
        val out = demoteRejected(list, "word")
        assertEquals(listOf("world", "wold", "word"), out.map { it.word })
    }

    @Test
    fun theRejectedWordIsStillReachable() {
        // The hazard: a retype aimed at fixing a space, not a word, must keep the word it
        // had. Demoted, never dropped.
        val out = demoteRejected(list, "word")
        assertEquals(list.size, out.size)
        assertTrue("word must survive the demotion", out.any { it.word == "word" })
    }

    @Test
    fun nothingArmedLeavesTheListUntouched() {
        // Identity, not equality: the caller reads `!==` to decide whether to trace.
        assertSame(list, demoteRejected(list, null))
    }

    @Test
    fun aWordThatIsNotInTheListLeavesItUntouched() {
        assertSame(list, demoteRejected(list, "elephant"))
    }

    @Test
    fun aSingleCandidateIsNeverDemoted() {
        // Demoting the only candidate would promote nothing and empty the lead.
        val one = listOf(cand("word", 0.9f))
        assertSame(one, demoteRejected(one, "word"))
    }

    @Test
    fun caseDoesNotHideTheRejectedWord() {
        val capitalised = listOf(cand("Word", 0.9f), cand("world", 0.7f))
        assertEquals(listOf("world", "Word"), demoteRejected(capitalised, "word").map { it.word })
    }

    @Test
    fun anEmptyListIsReturnedAsItIs() {
        val none = emptyList<WordCandidate>()
        assertSame(none, demoteRejected(none, "word"))
    }
}
