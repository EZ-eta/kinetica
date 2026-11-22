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

package com.kinetica.keyboard.onboarding

import com.kinetica.keyboard.onboarding.SetupSteps.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the guided setup opens, and how it moves. */
class SetupStepsTest {

    private fun start(
        enabled: Boolean = true,
        selected: Boolean = true,
        saved: String? = null,
        finished: Boolean = false,
        upgraded: Boolean = false,
    ) = SetupSteps.start(enabled, selected, saved, finished, upgraded)

    @Test
    fun aFreshInstallStartsAtEnable() {
        assertEquals(Step.ENABLE, start(enabled = false, selected = false))
    }

    @Test
    fun theKeyboardComesFirstWhateverWasSaved() {
        assertEquals(Step.ENABLE, start(enabled = false, selected = false, finished = true))
        assertEquals(Step.SELECT, start(selected = false, saved = "THEME"))
        assertEquals(Step.SELECT, start(selected = false, finished = true))
    }

    @Test
    fun aFinishedSetupOpensTheLastPage() {
        assertEquals(Step.DONE, start(finished = true, saved = "HEIGHT"))
    }

    @Test
    fun aSavedStepResumes() {
        assertEquals(Step.SPACING, start(saved = "SPACING"))
        // Saved while the keyboard was being turned on, both done since.
        assertEquals(Step.LANGUAGES, start(saved = "ENABLE"))
        assertEquals(Step.LANGUAGES, start(saved = "SELECT"))
    }

    @Test
    fun anUnknownSavedNameIsTheFirstChoice() {
        assertEquals(Step.LANGUAGES, start(saved = "COLOURS"))
        assertEquals(Step.LANGUAGES, SetupSteps.parse(""))
    }

    @Test
    fun anUpgradingUserIsNotWalkedThroughIt() {
        assertEquals(Step.DONE, start(upgraded = true))
        // An upgrade with the keyboard off: still turned on first.
        assertEquals(Step.ENABLE, start(enabled = false, selected = false, upgraded = true))
    }

    @Test
    fun anUpgradingUserWithAnotherKeyboardInUseSkipsTheChoicesToo() {
        assertTrue(SetupSteps.finished(saved = null, finished = false, upgraded = true))
        assertFalse(SetupSteps.finished(saved = "ENABLE", finished = false, upgraded = true))
        assertFalse(SetupSteps.finished(saved = null, finished = false, upgraded = false))
        assertEquals(Step.SELECT, start(selected = false, upgraded = true))
        assertEquals(Step.DONE, SetupSteps.settle(Step.SELECT, true, true, finished = true))
    }

    @Test
    fun keyboardOnAndChosenBeforeTheFirstOpenStartsAtTheChoices() {
        assertEquals(Step.LANGUAGES, start())
    }

    @Test
    fun enableAndSelectMoveOnTheirOwn() {
        assertEquals(Step.ENABLE, SetupSteps.settle(Step.ENABLE, false, false, false))
        assertEquals(Step.SELECT, SetupSteps.settle(Step.ENABLE, true, false, false))
        assertEquals(Step.LANGUAGES, SetupSteps.settle(Step.ENABLE, true, true, false))
        assertEquals(Step.LANGUAGES, SetupSteps.settle(Step.SELECT, true, true, false))
        assertEquals(Step.THEME, SetupSteps.settle(Step.THEME, false, false, false))
    }

    @Test
    fun aFinishedSetupTurnedBackOnReturnsToTheLastPage() {
        assertEquals(Step.DONE, SetupSteps.settle(Step.ENABLE, true, true, true))
        assertEquals(Step.SELECT, SetupSteps.settle(Step.ENABLE, true, false, true))
    }

    @Test
    fun nextWaitsOnTheSystemStepsOnly() {
        assertFalse(SetupSteps.canGoNext(Step.ENABLE, false, false))
        assertTrue(SetupSteps.canGoNext(Step.ENABLE, true, false))
        assertFalse(SetupSteps.canGoNext(Step.SELECT, true, false))
        assertTrue(SetupSteps.canGoNext(Step.SELECT, true, true))
        for (s in listOf(Step.LANGUAGES, Step.HEIGHT, Step.SPACING, Step.THEME, Step.TUTOR)) {
            assertTrue(s.name, SetupSteps.canGoNext(s, false, false))
        }
        assertFalse(SetupSteps.canGoNext(Step.DONE, true, true))
    }

    @Test
    fun theOrderIsTheScopedOne() {
        val walked = generateSequence(Step.ENABLE) { s -> SetupSteps.next(s).takeIf { it != s } }.toList()
        assertEquals(
            listOf(
                Step.ENABLE, Step.SELECT, Step.LANGUAGES, Step.HEIGHT, Step.SPACING, Step.THEME,
                Step.TUTOR, Step.DONE,
            ),
            walked,
        )
    }

    @Test
    fun backSkipsASystemStepAlreadyDone() {
        assertNull(SetupSteps.back(Step.LANGUAGES, true, true))
        assertEquals(Step.LANGUAGES, SetupSteps.back(Step.HEIGHT, true, true))
        assertEquals(Step.ENABLE, SetupSteps.back(Step.SELECT, false, false))
        assertNull(SetupSteps.back(Step.ENABLE, false, false))
    }

    @Test
    fun sevenNumberedSteps() {
        assertEquals(7, SetupSteps.NUMBERED.size)
        assertEquals(1, SetupSteps.number(Step.ENABLE))
        assertEquals(7, SetupSteps.number(Step.TUTOR))
        assertEquals(0, SetupSteps.number(Step.DONE))
    }

    @Test
    fun theFourChoiceStepsEachHaveAScreen() {
        assertEquals(
            listOf(Step.LANGUAGES, Step.HEIGHT, Step.SPACING, Step.THEME),
            Step.entries.filter { it.prefScreen != null },
        )
    }
}
