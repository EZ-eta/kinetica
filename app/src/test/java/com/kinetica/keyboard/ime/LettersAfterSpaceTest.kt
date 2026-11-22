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

import com.kinetica.keyboard.keys.EditPage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A space on a symbol page goes back to the letters, under its switch (#53). */
class LettersAfterSpaceTest {

    @Test
    fun aSpaceOnEitherSymbolPageBringsTheLetters() {
        assertTrue(lettersAfterSpace(enabled = true, board = "symbols"))
        assertTrue(lettersAfterSpace(enabled = true, board = "symbols2"))
    }

    @Test
    fun theNumberPadAndTheEditingPageStay() {
        // A phone number or a run of moves goes on past a space.
        assertFalse(lettersAfterSpace(enabled = true, board = "numpad"))
        assertFalse(lettersAfterSpace(enabled = true, board = EditPage.NAME))
        // On the letters already: nothing to rebuild.
        assertFalse(lettersAfterSpace(enabled = true, board = "qwerty_it"))
        assertFalse(lettersAfterSpace(enabled = true, board = null))
    }

    @Test
    fun theSwitchOffKeepsThePage() {
        assertFalse(lettersAfterSpace(enabled = false, board = "symbols"))
        assertFalse(lettersAfterSpace(enabled = false, board = "symbols2"))
    }

    @Test
    fun theSpaceKeyAsksAfterItsSpace() {
        // The service has no JVM reach: the space is written first, then the page changes.
        val src = ImeSources.declaring("KeyType.SPACE -> {").text
        val branch = src.substringAfter("KeyType.SPACE -> {").substringBefore("KeyType.BACKSPACE ->")
        assertTrue(branch.indexOf("onSpace()") in 0 until branch.indexOf("lettersAfterSpace("))
        assertTrue(branch.contains("lettersAfterSpace(config.lettersAfterSpace, keyboardView?.shownBoard)"))
        assertTrue(branch.contains("setKeyboardLayout(views.alphaLayout())"))
    }
}
