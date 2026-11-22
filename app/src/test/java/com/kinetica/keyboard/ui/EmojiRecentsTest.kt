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

package com.kinetica.keyboard.ui

import com.kinetica.keyboard.ui.EmojiRecents.Use
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiRecentsTest {

    @Test
    fun ordersByCountNotByRecency() {
        // The daily emoji is the older one; ordering by recency would put the one-off first.
        val daily = Use("😀", count = 40, updatedAt = 1_000L)
        val onceEver = Use("🦄", count = 1, updatedAt = 9_000L)
        assertEquals(listOf(daily.emoji, onceEver.emoji), EmojiRecents.ordered(listOf(onceEver, daily)))
    }

    @Test
    fun recencyBreaksACountTie() {
        val older = Use("🍕", count = 5, updatedAt = 1_000L)
        val newer = Use("🍔", count = 5, updatedAt = 2_000L)
        assertEquals(listOf(newer.emoji, older.emoji), EmojiRecents.ordered(listOf(older, newer)))
    }

    @Test
    fun spellingBreaksAFullTieSoTheOrderIsStable() {
        val a = Use("☀", count = 3, updatedAt = 7L)
        val b = Use("☁", count = 3, updatedAt = 7L)
        assertEquals(EmojiRecents.ordered(listOf(a, b)), EmojiRecents.ordered(listOf(b, a)))
    }

    @Test
    fun countsAtOrBelowZeroAreDropped() {
        val used = Use("👍", count = 2, updatedAt = 5L)
        val spent = Use("👎", count = 0, updatedAt = 9L)
        assertEquals(listOf(used.emoji), EmojiRecents.ordered(listOf(used, spent)))
    }

    @Test
    fun theCapIsThreeFullRows() {
        assertEquals(0, EmojiRecents.MAX % 8)
        val many = (1..60).map { Use("e$it", count = it, updatedAt = it.toLong()) }
        assertEquals(EmojiRecents.MAX, EmojiRecents.ordered(many).size)
        // Best first: the highest count survives the cap, the lowest does not.
        assertEquals("e60", EmojiRecents.ordered(many).first())
        assertTrue(EmojiRecents.ordered(many).none { it == "e1" })
    }

    @Test
    fun anEmptyStoreYieldsAnEmptyTab() {
        assertEquals(emptyList<String>(), EmojiRecents.ordered(emptyList()))
    }

    @Test
    fun aMultiCodepointEmojiSurvivesWhole() {
        // U+2708 U+FE0F: why the store is keyed on the string, not on a single Int codepoint.
        val plane = "✈️"
        assertEquals(listOf(plane), EmojiRecents.ordered(listOf(Use(plane, 1, 1L))))
    }

    @Test
    fun theEmptySearchStripIsNeverEmpty() {
        // A fresh install, or the DEV build's own database: no picks yet.
        val catalog = List(60) { "e$it" }
        assertEquals(catalog.take(EmojiRecents.MAX), EmojiRecents.strip(emptyList(), catalog))
    }

    @Test
    fun recentsLeadTheStripAndAreNotRepeated() {
        val catalog = List(60) { "e$it" }
        val strip = EmojiRecents.strip(listOf("🦄", "e3"), catalog)
        assertEquals(listOf("🦄", "e3", "e0", "e1", "e2", "e4"), strip.take(6))
        assertEquals(EmojiRecents.MAX, strip.size)
        assertEquals(strip.size, strip.distinct().size)
    }
}
