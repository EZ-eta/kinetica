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

import com.kinetica.keyboard.ime.strokeApostrophes
import com.kinetica.keyboard.ui.TouchRules
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The apostrophe in strokes off reads every gate try through or onto the apostrophe key as the keyboard
 * did before the key was a place: the board the switch hands the engine and predictors set by it, against
 * the board without the key and a predictor never configured. Word and score, every candidate.
 */
class ApostropheStrokesOffReplayTest {

    private fun rows(): List<List<String>> {
        val f = listOf(Paths.get("src/test/resources/apostrophe_key_rows.tsv"), Paths.get("app/src/test/resources/apostrophe_key_rows.tsv"))
            .firstOrNull { Files.exists(it) }
        assumeTrue("apostrophe rows not found", f != null)
        return Files.readAllLines(f!!).filter { !it.startsWith("#") && it.isNotBlank() }.map { it.split("\t") }
    }

    private fun lexicon(lang: String): WordPredictor {
        val direct = Paths.get("src/main/assets/dictionaries/${lang}_wordlist.txt")
        val p = if (Files.exists(direct)) direct else Paths.get("app/src/main/assets/dictionaries/${lang}_wordlist.txt")
        assumeTrue("$lang wordlist not found", Files.exists(p))
        val d = Files.newBufferedReader(p).use { DictionaryLoader.load(it) }
        return WordPredictor(d.trie, BigramTable.EMPTY, TestData.qwertyGeometry(), d.forms, language = lang)
    }

    private fun decode(p: WordPredictor, board: TraceReplay.Board, row: List<String>): List<Pair<String, Float>> {
        p.geometry = board.g
        return p.decode(TraceReplay.tokens(row[5], board, TraceReplay.paths(row[7])), emptyList()).map { it.word to it.score }
    }

    @Test
    fun withStrokesOffEveryGateTryReadsAsWithoutTheKey() {
        val switched = HashMap<String, WordPredictor>()
        val plain = HashMap<String, WordPredictor>()
        var rows = 0
        var onDiffers = 0
        val differs = ArrayList<String>()
        for (row in rows()) {
            val lang = row[2]
            // The board line without the key: the letters the layout drew.
            val letters = TraceReplay.board(row[6].replace(Regex(";'[^;\\s]*"), ""))!!
            assertTrue(!letters.g.hasApostropheKey)
            val traced = TraceReplay.board(row[6])!!.g
            val a = traced.alphabet.apostrophe
            // The key where the capture's board had it; the earliest captures drew it but did not trace it.
            val keyKw = if (traced.hasApostropheKey) traced.rectKw(a) else TestData.withApostropheKey(letters.g).rectKw(a)
            val off = TraceReplay.Board(TouchRules.engineBoard(letters.g, keyKw, strokes = false), letters.tapDispKw)
            val on = TraceReplay.Board(TouchRules.engineBoard(letters.g, keyKw, strokes = true), letters.tapDispKw)
            // Turned on and off again, as a user would: nothing of on stays behind.
            val p = switched.getOrPut(lang) { lexicon(lang) }
            strokeApostrophes(p, true)
            val readOn = decode(p, on, row)
            strokeApostrophes(p, false)
            val readOff = decode(p, off, row)
            val keyless = decode(plain.getOrPut(lang) { lexicon(lang) }, letters, row)
            if (readOff != keyless) differs.add("${row[0]}:${readOff.firstOrNull()} vs ${keyless.firstOrNull()}")
            if (readOn != keyless) onDiffers++
            rows++
        }
        assertEquals(304, rows)
        assertEquals(emptyList<String>(), differs)
        // The same rows with the switch on read otherwise: the comparison is not empty.
        assertTrue("on differs on $onDiffers of $rows", onDiffers > rows / 2)
        println("APOS-OFF rows=$rows off-equal=$rows on-differs=$onDiffers")
    }
}
