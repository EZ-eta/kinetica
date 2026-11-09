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
