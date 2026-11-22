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

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reloaded word is filed without leading Follow's filing, so the buffer is read before the
 * commit clears it.
 *
 * Read after the commit, every buffer is empty and no word is ever seeded: an autocorrected
 * reload that only the other list holds would lead again, and the next word no list holds would
 * follow it there. The commit path needs a live editor, so the guard is a source scan.
 *
 * Read off disk ([ImeSources]), so a fail-first check here needs `--rerun-tasks`.
 */
class SeededFilingSourceTest {

    @Test
    fun theSeededReadComesBeforeTheCommitAndReachesTheFiling() {
        val src = ImeSources.declaring("private fun commitWordInternal(").text
        val start = src.indexOf("private fun commitWordInternal(")
        val body = src.substring(start, src.indexOf("\n    }\n", start))
        val read = body.indexOf("val seeded = ime.composer?.bufferSeeded == true")
        val commit = body.indexOf("ime.composer?.commitWord(")
        assertTrue("the buffer is no longer read for a reload", read >= 0)
        assertTrue("the buffer is read after the commit cleared it", commit >= 0 && read < commit)
        // Every word a commit learns, an elision's head too, is filed through learnOne with the read.
        assertTrue("the head no longer hears of a reload", body.contains("learnOne(head, proposal?.take(head.length), textBefore, lang, seeded, headContext)"))
        assertTrue("the word no longer hears of a reload", body.contains("learnOne(last, tailProposal, before, lang, seeded, ime.composer?.contextSnapshot())"))
        val learn = src.substring(src.indexOf("private fun learnOne("), src.indexOf("\n    }\n", src.indexOf("private fun learnOne(")))
        assertTrue("the filing no longer hears of a reload", learn.contains("ime.filedLanguage(word, lang, seeded)"))
    }
}
