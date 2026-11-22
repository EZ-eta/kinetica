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

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings key pressed in the settings' own try field keeps the screen in use. Starting the
 * settings again with CLEAR_TOP rebuilt them at the top. The key path needs a service, so the guard
 * is a source scan.
 *
 * Read off disk ([ImeSources]), so a fail-first check here needs `--rerun-tasks`.
 */
class SettingsKeySourceTest {

    @Test
    fun theTryFieldHidesTheKeyboardInsteadOfRestartingTheSettings() {
        val src = ImeSources.declaring("fun openSettings(").text
        val start = src.indexOf("fun openSettings(")
        val body = src.substring(start, src.indexOf("\n    }\n", start))
        val hide = body.indexOf("ime.requestHideSelf(0)")
        val back = body.indexOf("if (screen == null && ime.editorState.ownTryField) return")
        assertTrue("the try field still restarts the settings", back >= 0)
        assertTrue("the keyboard must hide first", hide in 0 until back)
        assertTrue(back < body.indexOf("ime.startActivity("))
        val ime = ImeSources.declaring("class KineticaIME").text
        assertTrue("the field's state never learns the own package", ime.contains("EditorState.from(attribute, packageName)"))
    }
}
