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

import com.kinetica.keyboard.engine.DictionaryLoader

/**
 * What one commit taught: its count ([counted] false when the learn refused the word), the
 * capital before its vote, and its pair.
 */
internal class CommitLesson(
    val word: String,
    val lang: String,
    val counted: Boolean,
    val voted: Boolean,
    val caseBefore: DictionaryLoader.LearnedCase?,
    val pair: Pair<String, String>?,
)

/**
 * The last commits by where they start, so a reload that takes the newest up again can lift it
 * out of the decode context and arm what it taught to go back if it becomes another word.
 *
 * A reload is known by its place, not its letters: the same word elsewhere is not the commit.
 * Two marks, as deep as the composer's context, because a word deleted whole drops its mark and
 * the one before is the newest again: `Comp`, a space, `a`, a space, all deleted, reopens `Comp`.
 * Moved by every own edit before them, as [CommitHistory] records are. Pure; main thread only.
 */
internal class CommitMarks {

    /** [word], lowercased, written from [start]; [spaced] while the space after it is the keyboard's. */
    data class Mark(val start: Int, val word: String, val lesson: CommitLesson?, val spaced: Boolean = false) {
        val end: Int get() = start + word.length
    }

    private val marks = ArrayDeque<Mark>()

    val newest: Mark? get() = marks.lastOrNull()

    /**
     * A commit of [word] from [start]. One of the same word in the same place that taught nothing
     * is the earlier commit again (a reopened word committed unchanged), so it keeps that lesson.
     */
    fun record(start: Int, word: String, lesson: CommitLesson?) {
        if (start < 0 || word.isEmpty()) return
        val w = word.lowercase()
        val kept = lesson ?: marks.lastOrNull { it.start == start && it.word == w }?.lesson
        marks.removeAll { it.start < start + w.length && start < it.end }
        marks.addLast(Mark(start, w, kept))
        while (marks.size > CAPACITY) marks.removeFirst()
    }

    /** The space after the newest commit, which ends at [end], is the keyboard's. */
    fun spaceAt(end: Int) = setSpaced(end, true)

    /** The user ended the newest commit, which ends at [end]: punctuation took its space. */
    fun unspaceAt(end: Int) = setSpaced(end, false)

    private fun setSpaced(end: Int, spaced: Boolean) {
        val m = marks.lastOrNull() ?: return
        if (m.end == end) marks[marks.size - 1] = m.copy(spaced = spaced)
    }

    /**
     * The newest commit, when [fragment] from [start] reopens it: its whole word, or what backspace
     * left of it. An older one is not the last commit, and the context holds the newest last.
     */
    fun reopened(start: Int, fragment: String): Mark? {
        if (fragment.isEmpty()) return null
        val m = marks.lastOrNull() ?: return null
        return m.takeIf { it.start == start && it.word.startsWith(fragment.lowercase()) }
    }

    fun drop(mark: Mark) {
        marks.remove(mark)
    }

    /** [lesson] went back: no mark may arm it again. */
    fun forget(lesson: CommitLesson) {
        marks.removeAll { it.lesson === lesson }
    }

    /**
     * An own edit at [at]: [deleted] characters gone before it and [inserted] written. A mark after
     * it moves; one whose start it crosses, or a delete back to its first letter, drops it; one it
     * edits from inside, a backspace into the word's tail or letters typed on, stays where it starts.
     */
    fun onEdit(at: Int, deleted: Int, inserted: Int) {
        val from = at - deleted
        val delta = inserted - deleted
        val i = marks.listIterator()
        while (i.hasNext()) {
            val m = i.next()
            when {
                at <= m.start -> if (delta != 0) i.set(m.copy(start = m.start + delta))
                from < m.start -> i.remove()
                deleted > 0 && inserted == 0 && from == m.start -> i.remove()
            }
        }
    }

    fun clear() = marks.clear()

    companion object {
        const val CAPACITY = 2
    }
}

/**
 * Whether reopening [mark] as [fragment] arms its lesson, so committing another word takes it back.
 * Letters deleted from it say it was not the word. Reopened whole, only a space the keyboard put
 * there says so: a space the user typed ended the user's word.
 */
internal fun armsLesson(mark: CommitMarks.Mark, fragment: String): Boolean =
    mark.lesson != null && (mark.spaced || fragment.length < mark.word.length)
