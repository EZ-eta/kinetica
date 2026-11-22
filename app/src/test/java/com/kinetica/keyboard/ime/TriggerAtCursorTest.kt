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

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The expandify trigger walk.
 *
 * Its own walk, not trailingLetterRun: the reporter's triggers are `.`, `x`, `vv`, `^^` and
 * `(-.-)'`, and the letter walk returns nothing for three of them. Whitespace is the only
 * boundary here.
 */
class TriggerAtCursorTest {

    private fun at(before: String) = triggerAtCursor(before, MAX_TRIGGER_CHARS)

    @Test
    fun theReportersOwnTriggersAllSurvive() {
        assertEquals("x", at("x").trigger)
        assertEquals("vv", at("vv").trigger)
        assertEquals("^^", at("^^").trigger)
        assertEquals(".", at(".").trigger)
        assertEquals("(-.-)'", at("(-.-)'").trigger)
        assertEquals("Laziness", at("Laziness").trigger)
    }

    @Test
    fun aTriggerIsTakenFromTheEndOfARealSentence() {
        assertEquals("vv", at("shopping list vv").trigger)
        assertEquals("^^", at("feeling ^^").trigger)
    }

    @Test
    fun oneTrailingSpaceIsSkippedAndReportedInTheSpan() {
        // The autospace writes one after almost every word; a trigger that stopped working once
        // it arrived could never be fired.
        val found = at("done vv ")
        assertEquals("vv", found.trigger)
        assertEquals(3, found.span)
    }

    @Test
    fun twoSpacesAreABoundaryNotALicence() {
        // A finished thought, not a word waiting to be expanded.
        assertEquals("", at("done vv  ").trigger)
        assertEquals("", at("done vv\n").trigger)
    }

    @Test
    fun theSpanIsTheTriggerWhenNoSpaceFollows() {
        val found = at("hello ^^")
        assertEquals("^^", found.trigger)
        assertEquals(2, found.span)
    }

    @Test
    fun anEmptyOrBlankContextYieldsNothing() {
        assertEquals("", at("").trigger)
        assertEquals("", at(" ").trigger)
        assertEquals(0, at("").span)
    }

    @Test
    fun theWalkIsBounded() {
        // Long enough to matter: without the bound a paragraph with no space in it would
        // be read back in full on every fire.
        val long = "a".repeat(MAX_TRIGGER_CHARS + 20)
        assertEquals(MAX_TRIGGER_CHARS, at(long).trigger.length)
    }

    @Test
    fun aNewlineBoundsItLikeASpace() {
        assertEquals("vv", at("first line\nvv").trigger)
    }

    // ---- what gets written into a field that holds one line ---------------------------

    @Test
    fun aMultiLineTargetIsFlattenedInASingleLineField() {
        assertEquals("a b c", expansionForField("a\nb\nc", multiline = false))
        assertEquals("a b", expansionForField("a\r\nb", multiline = false).replace("  ", " "))
    }

    @Test
    fun aMultiLineTargetIsKeptWhereTheFieldTakesOne() {
        assertEquals("a\nb\nc", expansionForField("a\nb\nc", multiline = true))
    }
}
