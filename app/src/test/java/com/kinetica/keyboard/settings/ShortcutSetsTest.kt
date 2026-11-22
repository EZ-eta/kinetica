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

import com.kinetica.keyboard.keys.ActionRow
import com.kinetica.keyboard.keys.EditorAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bar and the ?123 hold menu read separate shortcut sets.
 *
 * With one shared set, taking the gear off the bar also took it off the hold, the keyboard's
 * only route to settings unless a chord or an edge swipe was bound to it.
 */
class ShortcutSetsTest {

    @Test
    fun theBarAndTheMenuReadTheirOwnSets() {
        val prefs = MapPrefs(
            mapOf(
                Prefs.BAR_ACTIONS to setOf(EditorAction.UNDO.name),
                Prefs.MENU_ACTIONS to setOf(EditorAction.SETTINGS.name),
            ),
        )
        val config = KeyboardConfig.from(prefs)
        assertEquals(setOf(EditorAction.UNDO.name), config.barActions)
        assertEquals(setOf(EditorAction.SETTINGS.name), config.menuActions)
    }

    @Test
    fun anUntouchedMenuShowsTheShippedDefaultNotTheBarsChoice() {
        // Nothing to migrate: the row and the menu's own set shipped in the same release, so no
        // published build has a customised bar set for the menu to inherit.
        val prefs = MapPrefs(mapOf(Prefs.BAR_ACTIONS to setOf(EditorAction.UNDO.name)))
        assertEquals(ActionRow.DEFAULT, KeyboardConfig.from(prefs).menuActions)
    }
}
