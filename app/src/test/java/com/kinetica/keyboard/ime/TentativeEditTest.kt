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

import com.kinetica.keyboard.ime.TentativeEdit.Edit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The word in hand is rewritten by its changed tail only. Captured in a ProseMirror editor: a
 * merge that added the other thumb's letter deleted and rewrote the whole word 10 ms after the
 * swipe's write, and the editor's delete check then took one more letter, 8 times in 8.
 */
class TentativeEditTest {

    @Test
    fun theOtherThumbsLetterIsAPlainInsert() {
        // The captured words: the swipe wrote the head, the tap's merge added the last letter.
        assertEquals(Edit(0, "e"), TentativeEdit.between("th", "the"))
        assertEquals(Edit(0, "w"), TentativeEdit.between("ho", "how"))
        assertEquals(Edit(0, "t"), TentativeEdit.between("wha", "what"))
        assertEquals(Edit(0, "w"), TentativeEdit.between("no", "now"))
        assertEquals(Edit(0, "'t"), TentativeEdit.between("don", "don't"))
    }

    @Test
    fun aChangedLetterDeletesOnlyFromIt() {
        assertEquals(Edit(1, "he"), TentativeEdit.between("tg", "the"))
        assertEquals(Edit(1, "re"), TentativeEdit.between("then", "there"))
        assertEquals(Edit(1, ""), TentativeEdit.between("thee", "the"))
    }

    @Test
    fun theSameWordWritesNothing() {
        val edit = TentativeEdit.between("the", "the")
        assertEquals(Edit(0, ""), edit)
        assertTrue(edit.writesNothing)
    }

    @Test
    fun aRecaseRewritesFromTheFirstChangedLetter() {
        assertEquals(Edit(3, "The"), TentativeEdit.between("the", "The"))
        assertEquals(Edit(2, "HE"), TentativeEdit.between("the", "tHE"))
    }

    @Test
    fun anEmojiIsNeverCutInHalf() {
        // Two emoji sharing a high surrogate: the shared half must go with its pair.
        val grin = "😀"
        val smile = "😄"
        assertEquals(Edit(2, smile), TentativeEdit.between(grin, smile))
        assertEquals(Edit(2, smile), TentativeEdit.between("ab$grin", "ab$smile"))
    }

    @Test
    fun anEmptyWordIsAPlainInsert() {
        assertEquals(Edit(0, "th"), TentativeEdit.between("", "th"))
    }

    @Test
    fun replaceTentativeGoesThroughTheTail() {
        // The IME path needs a live editor, so the guard is a source scan ([ImeSources]): a
        // fail-first check here needs `--rerun-tasks`.
        val src = ImeSources.declaring("internal fun replaceTentative(").text
        val at = src.indexOf("internal fun replaceTentative(")
        val body = src.substring(at, src.indexOf("\n    }", at))
        assertTrue("replaceTentative writes the whole word", body.contains("TentativeEdit.between("))
        assertTrue("replaceTentative writes when nothing changed", body.contains("writesNothing"))
    }
}
