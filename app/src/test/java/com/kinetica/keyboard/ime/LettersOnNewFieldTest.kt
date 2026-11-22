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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A new field opens on the letters; a restart of the same field keeps the page. */
class LettersOnNewFieldTest {

    @Test
    fun aNewFieldLeftOnSymbolsComesBackToLetters() {
        assertTrue(opensOnLetters(restarting = false, enabled = true, onLetters = false))
    }

    @Test
    fun aRestartedFieldKeepsItsPage() {
        // An app restarting its own field mid-edit is not a new field.
        assertFalse(opensOnLetters(restarting = true, enabled = true, onLetters = false))
    }

    @Test
    fun theSettingOffKeepsThePage() {
        assertFalse(opensOnLetters(restarting = false, enabled = false, onLetters = false))
    }

    @Test
    fun lettersAlreadyShownAreNotRebuilt() {
        assertFalse(opensOnLetters(restarting = false, enabled = true, onLetters = true))
    }
}
