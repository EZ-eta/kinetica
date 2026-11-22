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
 * The keyboard draws the night look while the phone is dark: the config is read with the phone's
 * night bit, again when it flips, and the image is the look's own. The service has no JVM reach,
 * so the guard is a source scan.
 *
 * Read off disk ([ImeSources]), so a fail-first check here needs `--rerun-tasks`.
 */
class NightLookSourceTest {

    @Test
    fun theConfigFollowsThePhonesNightThemeAndTheImageItsLook() {
        val ime = ImeSources.declaring("class KineticaIME").text
        val changed = ime.substring(ime.indexOf("override fun onConfigurationChanged("))
        assertTrue(
            "a flip of the phone's dark theme must pick the look",
            changed.substring(0, changed.indexOf("\n    }\n")).contains("LookSets.systemNight(newConfig.uiMode)"),
        )
        assertTrue(ime.contains("KeyboardConfig.from(prefs, landscape, systemNight)"))
        assertTrue(ime.contains("systemNight: Boolean = LookSets.systemNight(resources.configuration.uiMode)"))
        val views = ImeSources.declaring("class KeyboardViews").text
        assertTrue(
            "the image must be read for the look drawn",
            views.contains("backdrop.refresh(ime.config.backdropStamp, ime.config.nightLook)"),
        )
        val loader = ImeSources.declaring("class BackdropLoader").text
        assertTrue(loader.contains("BackdropStore.load(context, night)"))
    }
}
