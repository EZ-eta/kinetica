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

package com.kinetica.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a commit is learned with no primary language, when the resident languages are passed and
 * [SharedWordFiling]'s held map reads the bundled lists alone.
 */
class FollowFilingTest {

    private val residents = listOf("en", "it")

    @Test
    fun aWordOneListHoldsIsFiledThere() {
        // Provenance English: the decode drew it from a copy the user's learning put there.
        assertEquals("it", SharedWordFiling().languageFor(mapOf("it" to 179), "en", residents))
    }

    @Test
    fun aWordOneListHoldsKeepsItsProvenanceWithAPrimary() {
        assertEquals("en", SharedWordFiling().languageFor(mapOf("it" to 179), "en"))
    }

    @Test
    fun anUnheldWordGoesWhereTheLastSingleListWordWent() {
        val f = SharedWordFiling()
        f.languageFor(mapOf("it" to 170), "it", residents)
        // Provenance English: a tapped word no candidate list holds defaults to the active one.
        assertEquals("it", f.languageFor(emptyMap(), "en", residents))
    }

    @Test
    fun anUnheldWordKeepsItsProvenanceWithAPrimary() {
        val f = SharedWordFiling()
        f.languageFor(mapOf("it" to 170), "it")
        assertEquals("en", f.languageFor(emptyMap(), "en"))
    }

    @Test
    fun anUnheldWordWithNoSingleListWordKeepsItsProvenance() {
        assertEquals("en", SharedWordFiling().languageFor(emptyMap(), "en", residents))
    }

    @Test
    fun aLastSingleLanguageThatLeftIsIgnored() {
        val f = SharedWordFiling()
        f.languageFor(mapOf("pl" to 170), "pl", listOf("pl", "en"))
        assertEquals("en", f.languageFor(emptyMap(), "en", residents))
    }

    @Test
    fun aReloadedOneListWordDoesNotLead() {
        val f = SharedWordFiling()
        f.languageFor(mapOf("it" to 170), "it", residents)
        // `ecom` reloaded and autocorrected to `econ`, which only the English list holds (byte 80).
        assertEquals("en", f.languageFor(mapOf("en" to 80), "en", residents, anchors = false))
        // An unheld Italian word after it follows the Italian before it.
        assertEquals("it", f.languageFor(emptyMap(), "en", residents))
    }
}
