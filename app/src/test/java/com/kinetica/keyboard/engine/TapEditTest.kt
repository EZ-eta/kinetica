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

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A tapped word with one tap too many or one letter never tapped, on recorded
 * buffers. The pass runs only when the letters as typed are neither a word nor the start of one.
 */
class TapEditTest {

    private val g = TestData.qwertyGeometry()

    private fun lead(lang: String, buffer: String): String? {
        val p = listOf(Paths.get("src/main/assets/dictionaries/${lang}_wordlist.txt"), Paths.get("app/src/main/assets/dictionaries/${lang}_wordlist.txt"))
            .firstOrNull { Files.exists(it) }
        assumeTrue("$lang wordlist not found", p != null)
        val d = Files.newBufferedReader(p!!).use { DictionaryLoader.load(it) }
        val w = WordPredictor(d.trie, BigramTable.EMPTY, g, d.forms, language = lang)
        return w.decode(TraceReplay.tokens("decode in[$lang]: $buffer ctx=[]", g), emptyList()).firstOrNull()?.word
    }

    @Test
    fun aStrayTapIsSkipped() {
        // A captured buffer that decoded to nothing without the edit.
        assertEquals(
            "however",
            lead(
                "en",
                "tap[h,RIGHT,t=41198557] tap[o,RIGHT,t=41198852] tap[e,LEFT,t=41199405] tap[w,LEFT,t=41199672] " +
                    "tap[e,LEFT,t=41199897] tap[v,RIGHT,t=41200061] tap[e,LEFT,t=41200154] tap[r,LEFT,t=41200356]",
            ),
        )
    }

    @Test
    fun aLetterNeverTappedIsAdded() {
        // A captured buffer that decoded to nothing without the edit.
        assertEquals(
            "happen",
            lead(
                "en",
                "tap[h,RIGHT,t=33198712] tap[a,LEFT,t=33198816] tap[p,RIGHT,t=33198990] tap[p,RIGHT,t=33199133] " +
                    "tap[n,RIGHT,t=33199429]",
            ),
        )
    }

    @Test
    fun aWordStillBeingTypedKeepsItsCompletion() {
        // A captured buffer, mid-word; an edit would read `molte` by dropping the `p`.
        assertEquals(
            "molteplici",
            lead(
                "it",
                "tap[m,LEFT,t=17430660] tap[o,LEFT,t=17430661] tap[l,LEFT,t=17430662] tap[t,LEFT,t=17430663] " +
                    "tap[e,LEFT,t=17430664] tap[p,LEFT,t=17430665]",
            ),
        )
    }
}
