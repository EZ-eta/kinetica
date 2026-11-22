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
 * A learn reads its stored row back on every build and corrects a live count that reads short; a
 * disagreement is traced, so a word a load left out shows at the commit that misses its boost. The
 * learn path needs a service, so the guard is a source scan.
 *
 * Read off disk ([ImeSources]), so a fail-first check here needs `--rerun-tasks`.
 */
class LearnCountTraceSourceTest {

    @Test
    fun aLearnReadsItsStoredCountBackAfterTheWrite() {
        val src = ImeSources.declaring("fun learnWord(").text
        val start = src.indexOf("fun learnWord(")
        val body = src.substring(start, src.indexOf("\n    }\n", start))
        val write = body.indexOf(".upsertAdd(w, lang, amount, now)")
        val read = body.indexOf(".storedCount(w, lang)")
        assertTrue("the learn no longer writes its row", write >= 0)
        assertTrue("the stored count is not read back after the write", read > write)
        // Every build: the read comes before the trace switch, not under it.
        assertTrue("the read is not on every build", read < body.indexOf("DecodeTrace.enabled"))
        assertTrue("the trace line is gone", body.contains("learn count word=\$w lang=\$lang live=\$after stored="))
    }

    @Test
    fun theCorrectedCountsAskTheReload() {
        val src = ImeSources.declaring("fun learnWord(").text
        val start = src.indexOf("fun learnWord(")
        val body = src.substring(start, src.indexOf("\n    }\n", start))
        assertTrue(body.contains("val gap = learnedCountGap(after, stored)"))
        assertTrue(body.contains("residents.correctLearn(mark, lang, w, gap)"))
        assertTrue(body.contains("askUserDictReload(w, lang, before + gap, after + gap)"))
        assertTrue(body.contains("userDictDemoted(before + gap, after + gap)"))
        assertTrue("the mark is taken after the journal note", body.indexOf("residents.learnMark()") > body.indexOf("residents.noteLearn("))
    }

    @Test
    fun anInstallRecordsItsLoadBeforeTheComposerReadsIt() {
        val src = ImeSources.declaring("internal class ResidentDictionaries").text
        val load = src.substring(src.indexOf("fun load("), src.indexOf("fun releaseInstall("))
        val installed = load.indexOf("learnJournal.installed(journal)")
        assertTrue(installed >= 0)
        assertTrue(installed < load.indexOf("onReady(p)"))
    }
}
