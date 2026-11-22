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

package com.kinetica.keyboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clipboard history's rules on a fake wall clock: twenty clips, a day each or five minutes under
 * the switch, expired rows deleted from the list itself.
 */
class ClipHistoryTest {

    private var now = 1_700_000_000_000L
    private var short = false
    private val history = ClipHistory { ClipRules.ttl(short) }

    private fun texts() = history.live(now).map { it.text }

    @Test
    fun theTwentyFirstClipDropsTheOldest() {
        for (i in 1..21) {
            assertTrue(history.add("clip $i", now))
            now += 1_000
        }
        val kept = texts()
        assertEquals(20, kept.size)
        assertEquals("clip 21", kept.first())
        assertEquals("clip 2", kept.last())
        assertFalse("clip 1" in kept)
    }

    @Test
    fun theSameTextAgainMovesUpAsOneRow() {
        history.add("address", now)
        now += 1_000
        history.add("phone", now)
        now += 1_000
        history.add("address", now)
        assertEquals(listOf("address", "phone"), texts())
        assertEquals(now, history.live(now).first().copiedAt)
    }

    @Test
    fun aClipLivesADayAndNotAMillisecondMore() {
        history.add("one", now)
        now += ClipRules.DAY_MS - 1
        assertEquals(listOf("one"), texts())
        now += 1
        assertEquals(emptyList<String>(), texts())
        assertEquals(1, history.expiredLast)
    }

    @Test
    fun underTheSwitchAClipLivesFiveMinutes() {
        short = true
        history.add("one", now)
        now += ClipRules.SHORT_MS - 1
        assertEquals(listOf("one"), texts())
        now += 1
        assertEquals(emptyList<String>(), texts())
    }

    @Test
    fun theSwitchShortensClipsAlreadyKept() {
        history.add("old", now)
        now += 10 * 60 * 1000L
        history.add("new", now)
        now += 60 * 1000L
        assertEquals(listOf("new", "old"), texts())
        short = true
        assertEquals(listOf("new"), texts())
    }

    @Test
    fun anExpiredRowIsDeletedNotHidden() {
        history.add("gone", now)
        now += ClipRules.DAY_MS
        history.live(now)
        assertEquals(0, history.size)
        // The clock back again does not bring it back.
        now -= ClipRules.DAY_MS
        assertEquals(emptyList<String>(), texts())
    }

    @Test
    fun aClipCopiedAfterNowIsDeleted() {
        history.add("future", now + 60_000)
        history.add("present", now)
        assertEquals(listOf("present"), texts())
        assertEquals(1, history.expiredLast)
    }

    @Test
    fun blankAndOverLongTextsAreNotKept() {
        assertFalse(history.add("   \n", now))
        assertFalse(history.add("x".repeat(ClipRules.MAX_CLIP_CHARS + 1), now))
        assertTrue(history.add("x".repeat(ClipRules.MAX_CLIP_CHARS), now))
        assertEquals(1, history.size)
    }

    @Test
    fun deleteOneAndClearAll() {
        history.add("a", now)
        history.add("b", now + 1)
        now += 2
        val b = history.live(now).first()
        assertTrue(history.delete(b.id))
        assertEquals(listOf("a"), texts())
        history.clear()
        assertEquals(0, history.size)
    }

    @Test
    fun aLoadMergesBehindTheClipsKeptMeanwhile() {
        val loaded = listOf(
            Clip(7, "disk new", now - 2_000),
            Clip(8, "both", now - 3_000),
            Clip(9, "disk expired", now - ClipRules.DAY_MS - 1),
        )
        history.add("both", now - 500)
        history.add("live", now - 100)
        history.restore(loaded, now)
        assertEquals(1, history.expiredLast)
        assertEquals(listOf("live", "both", "disk new"), texts())
    }

    @Test
    fun aLoadIsCappedToo() {
        val loaded = (1..20).map { Clip(it.toLong(), "disk $it", now - 10_000 + it) }
        history.add("live", now)
        history.restore(loaded, now)
        val kept = texts()
        assertEquals(20, kept.size)
        assertEquals("live", kept.first())
        assertFalse("disk 1" in kept)
    }

    @Test
    fun idsStayDistinctAcrossALoad() {
        history.add("live", now)
        history.restore(listOf(Clip(1, "disk", now - 1)), now)
        val ids = history.live(now).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
