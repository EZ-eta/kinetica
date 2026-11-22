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

/**
 * Tells the editor's cursor reports for the keyboard's own edits from the user's moves.
 *
 * Each own edit records the position it leaves, and a report is the keyboard's only when it
 * lands on one. A count of reports to swallow went wrong because an edit that leaves the cursor
 * in place (a same-length rewrite, as most two-thumb merges are) gets no report: the count crept
 * up and swallowed eight user moves in 99 commits. A position never reported is never matched.
 *
 * Pure, so the rule is testable without an editor. Main thread only.
 */
class SelectionLedger(
    /** An unmatched report this soon after an own edit is still taken as the edit's. */
    private val mismatchWindowMs: Long = OWN_REPORT_WINDOW_MS,
    /** How long an edit whose position cannot be known (paste, undo) claims the next report. */
    private val wildcardMs: Long = WILDCARD_MS,
    /** How long any expectation lives. */
    private val lifeMs: Long = LIFE_MS,
) {
    enum class Verdict { OWN, FOREIGN, USER }

    private class Entry(val start: Int, val end: Int, val at: Long, val any: Boolean)

    private val entries = ArrayDeque<Entry>()
    private var lastEditAt = Long.MIN_VALUE

    /** Expectations still open. */
    val pending: Int get() = entries.size

    /** The position the last expected own edit leaves, or null with none open. */
    fun lastExpected(): Pair<Int, Int>? = entries.lastOrNull { !it.any }?.let { it.start to it.end }

    /** An own edit leaves the selection at [start]..[end]. */
    fun expect(start: Int, end: Int, now: Long) {
        add(Entry(start, end, now, any = false))
    }

    /** An own edit whose resulting position cannot be known. */
    fun expectAny(now: Long) {
        add(Entry(-1, -1, now, any = true))
    }

    private fun add(e: Entry) {
        entries.addLast(e)
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
        lastEditAt = e.at
    }

    /** Whether the report of [start]..[end] at [now] is the keyboard's own; it consumes what it matches. */
    fun judge(start: Int, end: Int, now: Long): Verdict {
        entries.removeAll { now - it.at > (if (it.any) wildcardMs else lifeMs) }
        val i = entries.indexOfFirst { it.any || (it.start == start && it.end == end) }
        if (i >= 0) {
            repeat(i + 1) { entries.removeFirst() }
            return Verdict.OWN
        }
        val recent = lastEditAt != Long.MIN_VALUE && now - lastEditAt <= mismatchWindowMs
        entries.clear()
        return if (recent) Verdict.FOREIGN else Verdict.USER
    }

    fun clear() {
        entries.clear()
        lastEditAt = Long.MIN_VALUE
    }

    companion object {
        // An own edit's report arrives within a frame or two; a user's tap on the text cannot
        // follow a keystroke this closely.
        const val OWN_REPORT_WINDOW_MS = 150L
        const val WILDCARD_MS = 500L
        const val LIFE_MS = 3_000L
        const val MAX_ENTRIES = 16
    }
}
