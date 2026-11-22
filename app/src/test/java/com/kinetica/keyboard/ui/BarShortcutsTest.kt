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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shortcut row and the button that forces it over the words. */
class BarShortcutsTest {

    private fun row(
        actions: Boolean = true,
        words: Boolean = false,
        correction: Boolean = false,
        pending: Boolean = false,
        forced: Boolean = false,
    ) = BarShortcuts.rowShown(actions, words, correction, pending, forced)

    @Test
    fun anEmptyBarShowsTheRow() {
        assertTrue(row())
    }

    @Test
    fun wordsTheStripOrAWordInProgressKeepTheRowDown() {
        assertFalse(row(words = true))
        assertFalse(row(words = true, correction = true))
        assertFalse(row(pending = true))
    }

    @Test
    fun theButtonForcesTheRowOverAnyOfThem() {
        assertTrue(row(words = true, forced = true))
        assertTrue(row(words = true, correction = true, forced = true))
        assertTrue(row(words = true, pending = true, forced = true))
    }

    @Test
    fun noShortcutsNoRow() {
        assertFalse(row(actions = false))
        assertFalse(row(actions = false, words = true, forced = true))
    }

    @Test
    fun theButtonShowsOnlyWithWordsToCoverAndShortcutsToShow() {
        assertTrue(BarShortcuts.buttonShown(enabled = true, hasActions = true, hasWords = true))
        assertFalse("switch off", BarShortcuts.buttonShown(enabled = false, hasActions = true, hasWords = true))
        assertFalse("the row is already up", BarShortcuts.buttonShown(enabled = true, hasActions = true, hasWords = false))
        assertFalse("nothing to show", BarShortcuts.buttonShown(enabled = true, hasActions = false, hasWords = true))
    }
}
