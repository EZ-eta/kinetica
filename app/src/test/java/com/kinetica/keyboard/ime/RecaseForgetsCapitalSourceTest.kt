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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shift-down takes a learned capital off by hand: it forgets it, and the word in hand keeps the
 * lowercase through its commit, where `Ma` used to come back over `ma`.
 *
 * The rules are `CaseVote.FORGET` and `learnedCapital(chosen)`, covered by `LearnedCapitalsTest`;
 * the calls need a live editor, so the guard is a source scan. Read off disk ([ImeSources]), so a
 * fail-first check here needs `--rerun-tasks`.
 */
class RecaseForgetsCapitalSourceTest {

    private fun body(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("$signature not found", start >= 0)
        return src.substring(start, src.indexOf("\n    }\n", start))
    }

    @Test
    fun everyWordBranchOfALowercaseRecaseForgets() {
        val src = ImeSources.declaring("fun recaseWordInHand(want: WordCase)").text
        val inHand = body(src, "fun recaseWordInHand(want: WordCase)")
        // The word in hand, the last commit, the letters before the cursor; each before its own
        // early return, since the tapped `ma` is lowercase until it commits.
        assertEquals(3, Regex("forgetsCapital\\(want, recased\\)\\n\\s+if \\(recased == ").findAll(inHand).count())
        val around = body(src, "private fun recaseAroundCursor(")
        assertTrue("inside a word does not forget", around.contains("forgetsCapital(want, head + tail)\n        if (head == word.head"))
        assertTrue("lowercase does not forget", src.contains("if (want == WordCase.LOWER) ime.learner.forgetCase(word)"))
    }

    @Test
    fun aRecasedWordInHandKeepsItsCaseAtCommit() {
        val src = ImeSources.declaring("fun recaseWordInHand(want: WordCase)").text
        val inHand = body(src, "fun recaseWordInHand(want: WordCase)")
        assertTrue("the recase does not mark the word", inHand.contains("ime.wordState.caseChosen = true"))
        val commit = ImeSources.declaring("chosen = ime.wordState.caseChosen,").text
        assertTrue("the commit re-applies i", commit.contains("val cased = if (ime.wordState.caseChosen || !rewrites) {"))
    }

    @Test
    fun theMarkEndsWithItsWord() {
        // Every place a word ends, is reloaded, or loses a gesture clears it.
        val clears = ImeSources.all().sumOf { s -> Regex("wordState\\.caseChosen = false").findAll(s.text).count() }
        assertEquals(6, clears)
        val commitPath = ImeSources.declaring("fun closeBufferKeepBar()").text
        for (f in listOf("private fun commitWordInternal(", "fun abandonWord()", "fun closeBufferKeepBar()")) {
            assertTrue("$f keeps the mark", body(commitPath, f).contains("ime.wordState.caseChosen = false"))
        }
    }
}
