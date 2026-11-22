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

package com.kinetica.keyboard.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tutor's split of a word between the thumbs, on QWERTY's halves. */
class TutorScriptTest {

    private val left = "qwertasdfzxc".toSet()
    private fun isLeft(c: Char) = c in left

    @Test
    fun eachThumbTakesTheRunOfLettersOnItsHalf() {
        assertEquals(
            listOf(TutorScript.Stroke(true, "strea"), TutorScript.Stroke(false, "m")),
            TutorScript.strokes("stream", true, ::isLeft),
        )
        assertEquals(listOf("minu", "te"), TutorScript.strokes("minute", true, ::isLeft).map { it.letters })
        assertEquals(listOf("k", "e", "ybo", "ard"), TutorScript.strokes("keyboard", true, ::isLeft).map { it.letters })
        assertTrue(TutorScript.strokes("keyboard", true, ::isLeft)[0].isTap)
    }

    @Test
    fun oneThumbSwipesTheWholeWord() {
        assertEquals(listOf("hello"), TutorScript.strokes("hello", false, ::isLeft).map { it.letters })
        assertEquals(emptyList<TutorScript.Stroke>(), TutorScript.strokes("", true, ::isLeft))
    }

    @Test
    fun noLessonWordHasALetterOnTheMidline() {
        for (l in TutorScript.LESSONS) {
            assertFalse(l.word, l.word.any { it == 'g' || it == 'v' })
        }
    }

    @Test
    fun aLessonIsDoneWhenItsWordIsTheLastOneTyped() {
        assertTrue(TutorScript.done("Hello ", "hello"))
        assertTrue(TutorScript.done("some text hello.", "hello"))
        assertFalse(TutorScript.done("hell", "hello"))
        assertFalse(TutorScript.done("", "hello"))
    }
}
