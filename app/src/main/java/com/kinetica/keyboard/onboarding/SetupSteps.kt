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

/**
 * The guided setup's order and where a launch lands in it. Pure, so every rule has a JVM test;
 * [MainActivity] only reads the system state and draws the step.
 */
object SetupSteps {

    /** In order. [DONE] is the page after the numbered steps: the test field and the way back in. */
    enum class Step(val prefScreen: String?) {
        ENABLE(null),
        SELECT(null),
        LANGUAGES("setup_languages"),
        HEIGHT("setup_height"),
        SPACING("setup_spacing"),
        THEME("setup_theme"),
        TUTOR(null),
        DONE(null),
    }

    val NUMBERED: List<Step> = Step.entries.filter { it != Step.DONE }

    /** Where Run setup again starts: the keyboard is on and chosen by then. */
    val FIRST_CHOICE = Step.LANGUAGES

    /**
     * The step a launch opens on.
     *
     * A keyboard that is off or not chosen comes first, whatever was saved, since nothing else can
     * be tried without it. An upgrading user counts as [finished] and lands on [Step.DONE], which
     * offers the setup again.
     */
    fun start(enabled: Boolean, selected: Boolean, saved: String?, finished: Boolean, upgraded: Boolean): Step =
        when {
            !enabled -> Step.ENABLE
            !selected -> Step.SELECT
            finished(saved, finished, upgraded) -> Step.DONE
            saved != null -> settle(parse(saved), enabled, selected, finished = false)
            else -> FIRST_CHOICE
        }

    /**
     * Finished or skipped, or [upgraded] from a build without the setup and never opened since.
     * The activity stores the answer, so turning the keyboard on again later skips the choices too.
     */
    fun finished(saved: String?, finished: Boolean, upgraded: Boolean): Boolean =
        finished || (saved == null && upgraded)

    /**
     * Moves past enable and select once the system says they are done. The keyboard picker is a
     * dialog over the setup, so the step moves without the user pressing Next. A finished setup,
     * there because the keyboard was turned off since, goes back to its last page.
     */
    fun settle(step: Step, enabled: Boolean, selected: Boolean, finished: Boolean): Step = when {
        step == Step.ENABLE && enabled -> settle(Step.SELECT, enabled, selected, finished)
        step == Step.SELECT && selected -> if (finished) Step.DONE else FIRST_CHOICE
        else -> step
    }

    /** Next waits on the two system steps; the choices are optional, their defaults are fine. */
    fun canGoNext(step: Step, enabled: Boolean, selected: Boolean): Boolean = when (step) {
        Step.ENABLE -> enabled
        Step.SELECT -> selected
        Step.DONE -> false
        else -> true
    }

    fun next(step: Step): Step = Step.entries[minOf(step.ordinal + 1, Step.DONE.ordinal)]

    /** The step before, skipping a system step already done, which would only bounce forward; null at the start. */
    fun back(step: Step, enabled: Boolean, selected: Boolean): Step? {
        var i = step.ordinal - 1
        while (i >= 0) {
            val s = Step.entries[i]
            if (settle(s, enabled, selected, finished = false) == s) return s
            i--
        }
        return null
    }

    /** 1-based, for "Step 3 of 7"; 0 for [Step.DONE]. */
    fun number(step: Step): Int = NUMBERED.indexOf(step) + 1

    /** A saved name from an older or newer build may not exist: the first choice, never a crash. */
    fun parse(name: String): Step = Step.entries.firstOrNull { it.name == name } ?: FIRST_CHOICE
}
