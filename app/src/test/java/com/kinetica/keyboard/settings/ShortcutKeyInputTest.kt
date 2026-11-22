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

package com.kinetica.keyboard.settings

import com.kinetica.keyboard.settings.ShortcutKeyInput.Result
import org.junit.Assert.assertEquals
import org.junit.Test

/** The trigger key, typed into a one-character field instead of picked from a list. */
class ShortcutKeyInputTest {

    @Test
    fun oneKeyOfAnyBoard() {
        assertEquals(Result.One('a'), ShortcutKeyInput.parse("a"))
        assertEquals(Result.One('й'), ShortcutKeyInput.parse("й"))
        assertEquals(Result.One('1'), ShortcutKeyInput.parse("1"))
        assertEquals(Result.One(','), ShortcutKeyInput.parse(","))
    }

    @Test
    fun spacesAreDroppedAndALetterIsItsLowercase() {
        assertEquals(Result.One('v'), ShortcutKeyInput.parse("  V "))
        assertEquals(Result.One('ж'), ShortcutKeyInput.parse("\tЖ\n"))
    }

    @Test
    fun nothingAndMoreThanOneAreRefused() {
        assertEquals(Result.Blank, ShortcutKeyInput.parse(""))
        assertEquals(Result.Blank, ShortcutKeyInput.parse("   "))
        assertEquals(Result.Blank, ShortcutKeyInput.parse(null))
        assertEquals(Result.TooMany("ab"), ShortcutKeyInput.parse("a b"))
        assertEquals(Result.TooMany(":)"), ShortcutKeyInput.parse(":)"))
    }
}
