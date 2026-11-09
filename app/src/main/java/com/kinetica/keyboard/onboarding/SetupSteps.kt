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
