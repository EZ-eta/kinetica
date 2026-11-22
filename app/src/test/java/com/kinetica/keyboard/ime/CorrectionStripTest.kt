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

/**
 * When a commit puts the correction strip up.
 *
 * The strip is not gated on "teaches nothing" like the learning calls beside it: DuckDuckGo,
 * Molly and Firefox Focus set IME_FLAG_NO_PERSONALIZED_LEARNING on ordinary text fields, and
 * gating on it blanked the bar at every commit there. A password field
 * still shows nothing, because the strip would put the password in the bar.
 */
class CorrectionStripTest {

    @Test
    fun aNoLearningFieldStillOffersCorrections() {
        // The field forbids learning and says nothing about the strip. Learning is refused
        // separately: learnWord, unlearnWord and learnPair each read teachesNothing.
        val field = EditorState.DEFAULT.copy(noLearning = true)
        assertTrue(field.teachesNothing)
        assertTrue(field.offersCorrections)
        assertTrue(showsCorrectionStrip("hello", field.offersCorrections, optionCount = 4))
    }

    @Test
    fun aPasswordFieldGetsNothing() {
        // pushSuggestions already offers nothing while typing in one; a strip afterwards
        // would hand the password back on screen.
        val field = EditorState.DEFAULT.copy(privateMode = true)
        assertFalse(field.offersCorrections)
        assertFalse(showsCorrectionStrip("hunter2", field.offersCorrections, optionCount = 4))
    }

    @Test
    fun anOrdinaryFieldGetsTheStrip() {
        assertTrue(showsCorrectionStrip("hello", EditorState.DEFAULT.offersCorrections, 4))
    }

    @Test
    fun aStripOfOneIsSuppressed() {
        // Its only zone is the selected one, and a tap there is a no-op, so the strip would
        // offer nothing but the word itself.
        assertFalse(showsCorrectionStrip("hello", offersCorrections = true, optionCount = 1))
        assertFalse(showsCorrectionStrip("hello", offersCorrections = true, optionCount = 0))
    }

    @Test
    fun anEmptyCommitGetsNothing() {
        assertFalse(showsCorrectionStrip("", offersCorrections = true, optionCount = 4))
    }
}
