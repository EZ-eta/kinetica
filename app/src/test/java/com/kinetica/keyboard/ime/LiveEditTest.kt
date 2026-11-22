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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No edit lands away from the cursor. Reported on 2.1.3: text changed somewhere else in a
 * paragraph after a selection was deleted, and a spacebar word step after a tap in the text
 * landed inside a word. Both were placed by offsets cached from the editor's last report, which
 * lag it. Edits now act on the editor's live selection, or read its offsets fresh.
 *
 * The edit paths need a live editor, so most guards scan the source ([ImeSources]): a
 * fail-first check here needs `--rerun-tasks`.
 */
class LiveEditTest {

    @Test
    fun theSlideDeletesOnlyTheSpanItShowed() {
        assertTrue(stagedStillSelected("is a ", "is a ", unreadableTail = 0))
        // The selection moved or shrank since the highlight: nothing is deleted.
        assertFalse(stagedStillSelected("other", "is a ", unreadableTail = 0))
        assertFalse(stagedStillSelected("is a", "is a ", unreadableTail = 0))
        assertFalse(stagedStillSelected("", "is a ", unreadableTail = 0))
    }

    @Test
    fun anUnreadableSelectionIsComparedByItsCount() {
        // `word ` before the user's selection, then three characters the editor would not give.
        assertTrue(stagedStillSelected("word abc", "word ···", unreadableTail = 3))
        assertFalse(stagedStillSelected("ward abc", "word ···", unreadableTail = 3))
        assertFalse(stagedStillSelected("word ab", "word ···", unreadableTail = 3))
    }

    @Test
    fun anEditorThatReportsNoTextKeepsItsOwnHighlight() {
        assertTrue(stagedStillSelected(null, "is a ", unreadableTail = 0))
    }

    @Test
    fun aReloadedWordNotTypedOnIsKeptAsWritten() {
        // Reported: the cursor at a word's end, then a comma, and a prediction replaced it.
        assertTrue(keepsAsWritten("Ghidra", "Ghidra"))
        // Typed on since the reload: the word is the user's new input, the gate applies.
        assertFalse(keepsAsWritten("Ghidra", "Ghidrax"))
        assertFalse(keepsAsWritten(null, "teh"))
    }

    @Test
    fun theCommitGateSkipsAReloadedWord() {
        val src = ImeSources.declaring("fun finalizePendingWord(").text
        val at = src.indexOf("keepsAsWritten(")
        assertTrue("the commit gate does not ask keepsAsWritten", at >= 0)
        assertTrue(src.substring(at, src.indexOf("tapAutocorrect(", at)).contains("!asWritten"))
    }

    @Test
    fun noEditIsPlacedByTheCachedOffsets() {
        // An edit or a cursor placement built from selStart/selEnd lands wherever the cache
        // says, which is where the editor was, not where it is.
        val bad = Regex("""(setSelection|replaceSelection|deleteEndingAt)\([^)]*\b(selStart|selEnd)\b""")
        val cachedAt = Regex("""val at = minOf\(ime\.selStart""")
        for (source in ImeSources.all()) {
            for (m in bad.findAll(source.text)) {
                throw AssertionError("${source.name}:${source.lineOf(m.range.first)} places an edit by the cached offsets")
            }
            assertFalse("${source.name} re-selects from the cached start", cachedAt.containsMatchIn(source.text))
        }
    }

    @Test
    fun aSelectionIsDeletedWhereTheEditorHoldsIt() {
        val backspace = ImeSources.declaring("internal class Backspace").text
        assertFalse(backspace.contains("deleteEndingAt("))
        assertEquals(2, Regex("""deleteLiveSelection\(\)""").findAll(backspace).count())
        assertTrue(backspace.contains("stageAnchor = live?.second"))
        // The slide deletes only after checking the live selection is still what it showed.
        val commit = backspace.substring(backspace.indexOf("fun commitStaged("))
        assertTrue(Regex("""if \(!stagedStillSelected\(live, staged""").containsMatchIn(commit))
        assertTrue(commit.indexOf("stagedStillSelected(") < commit.indexOf("deleteLiveSelection()"))
    }

    @Test
    fun theWordStepStartsFromTheLiveCursor() {
        val ime = ImeSources.declaring("private fun moveCursorByWord(").text
        val at = ime.indexOf("private fun moveCursorByWord(")
        val body = ime.substring(at, ime.indexOf("\n    }\n", at))
        assertTrue(body.contains("ich.liveSelection() ?: return false"))
        assertFalse("the word step reads the cached offsets", Regex("""\b(selStart|selEnd)\b""").containsMatchIn(body))
    }
}
