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

/** One copied text and when it was copied, wall clock ms: a clip must age across a restart. */
data class Clip(val id: Long, val text: String, val copiedAt: Long)

/** The clipboard history's limits (#58). */
object ClipRules {
    /** Twenty clips: a working set, not an archive. The oldest goes first. */
    const val MAX_CLIPS = 20

    /** A day's clips: enough to paste yesterday's address, short enough not to become a record. */
    const val DAY_MS = 24L * 60 * 60 * 1000

    /** Under the short switch, five minutes: a copy lives for the paste it was made for. */
    const val SHORT_MS = 5L * 60 * 1000

    /** Longer copies are not kept: a whole document is not a clip, and the store stays small. */
    const val MAX_CLIP_CHARS = 20_000

    fun ttl(short: Boolean): Long = if (short) SHORT_MS else DAY_MS
}

/**
 * The clipboard history's rules, with no Android in them so they run on a fake clock. Main thread
 * only. An expired row is deleted from the list itself, never hidden, so the next save drops it
 * from the disk too.
 *
 * The time to live is read at each check, so turning the short switch on shortens the clips
 * already kept.
 */
class ClipHistory(private val ttlMs: () -> Long) {

    private val rows = ArrayList<Clip>() // newest first
    private var nextId = 1L

    /** How many rows the last [live] or [restore] deleted; above 0, the caller saves. */
    var expiredLast = 0
        private set

    val size: Int get() = rows.size

    /**
     * Keeps [text] copied at [now], newest first; the same text again moves up as one row with
     * the new time. Returns false for a text not kept (blank or over [ClipRules.MAX_CLIP_CHARS]).
     */
    fun add(text: String, now: Long): Boolean {
        if (text.isBlank() || text.length > ClipRules.MAX_CLIP_CHARS) return false
        rows.removeAll { it.text == text }
        rows.add(0, Clip(nextId++, text, now))
        cap()
        return true
    }

    /**
     * The clips still alive at [now], newest first. Deletes every expired row first: one aged
     * [ttlMs] or more, and one copied after [now], since a clock set back must not stretch a clip's
     * life.
     */
    fun live(now: Long): List<Clip> {
        expire(now)
        return rows.toList()
    }

    fun delete(id: Long): Boolean = rows.removeAll { it.id == id }

    fun clear() {
        rows.clear()
    }

    /**
     * Merges [loaded] (the rows read from disk) behind the ones kept since the load began, then
     * caps and expires. A text both kept and loaded stays as the newer row.
     */
    fun restore(loaded: List<Clip>, now: Long) {
        val kept = rows.map { it.text }.toHashSet()
        for (c in loaded.sortedByDescending { it.copiedAt }) {
            if (c.text in kept || c.text.isBlank() || c.text.length > ClipRules.MAX_CLIP_CHARS) continue
            kept.add(c.text)
            rows.add(Clip(nextId++, c.text, c.copiedAt))
        }
        rows.sortByDescending { it.copiedAt }
        cap()
        expire(now)
    }

    private fun cap() {
        while (rows.size > ClipRules.MAX_CLIPS) rows.removeAt(rows.size - 1)
    }

    private fun expire(now: Long) {
        val ttl = ttlMs()
        val before = rows.size
        rows.removeAll { now - it.copiedAt >= ttl || it.copiedAt > now }
        expiredLast = before - rows.size
    }
}
