package com.kinetica.keyboard.ui

/**
 * The arithmetic of the suggestion bar's long-press weight slide, including the
 * step past the bottom that blocks a word outright.
 *
 * The slide already ran off the end of its own scale: the personal count clamps
 * at zero downstream (KineticaIME.learnWord, and again in SQL), so every
 * downward step after the count reached zero did nothing at all. That unused
 * travel is what "block" is bound to, which is why blocking needs no second
 * gesture and no timer competing with the long press.
 *
 * Reaching zero is not enough on its own. A word is blocked only one full step
 * BELOW the step that zeroed it, so a user pushing a word down to nothing
 * cannot fall into a block by overshooting by a pixel.
 *
 * Pure, so the rule is testable without a view or a device.
 */
object BarAdjust {

    /**
     * Signed weight change for [steps] of slide at the configured [increment].
     * A release in place is +1 step, so upward travel starts at +2; downward
     * travel is symmetric per step.
     */
    fun delta(steps: Int, increment: Int): Int =
        if (steps >= 0) (steps + 1) * increment else steps * increment

    /** The count a word would end up with, floored at zero as the store floors it. */
    fun effectiveCount(count: Int, steps: Int, increment: Int): Int =
        (count + delta(steps, increment)).coerceAtLeast(0)

    /**
     * Minimum downward steps before a block can arm, whatever the word's
     * weight. Without it a word with no personal weight would block on the
     * FIRST step down, since there is nothing to take away first, and one
     * stray downward slide on a fresh junk suggestion would blacklist it.
     * At REINFORCE_STEP_DP this is about 48dp of deliberate travel.
     */
    const val MIN_BLOCK_STEPS = -2

    /**
     * The step at which a block arms: one below the first step whose effective
     * count is zero, and never sooner than [MIN_BLOCK_STEPS]. Always negative,
     * and always strictly past the step that merely zeroes the word.
     */
    fun blockStep(count: Int, increment: Int): Int {
        val inc = if (increment <= 0) 1 else increment
        // Steps needed to reach zero, then one more.
        val toZero = if (count <= 0) 0 else -((count + inc - 1) / inc)
        return minOf(toZero - 1, MIN_BLOCK_STEPS)
    }

    /** True when [steps] of downward slide has reached the blocking step. */
    fun blockArmed(count: Int, steps: Int, increment: Int): Boolean {
        if (steps >= 0) return false
        return steps <= blockStep(count, increment)
    }

    /**
     * Whether an armed slide applies its delta on lift, or hands the lift back to the
     * tap it was armed on top of.
     *
     * A hold that never slid, in correction mode, is a slow tap and not a gesture. The
     * strip's primary action is swapping a word in, a deliberate aim at one is slower
     * than a typing tap, and the arm's only feedback is a badge preview under the thumb -
     * so the gesture the strip exists for was being eaten silently. Nothing is lost:
     * picking a correction learns the word it picks, and one step of slide in either
     * direction still adjusts and still blocks.
     *
     * Composition mode is untouched. There a release in place is the plain long-press
     * reinforce that shipped first, and the tap means commit rather than correct.
     */
    fun appliesOnLift(correctionMode: Boolean, steps: Int): Boolean =
        !(correctionMode && steps == 0)

    /**
     * [steps] clamped so the slide cannot travel past the blocking step.
     * Further thumb travel then changes nothing, which is what makes the armed
     * state readable: the badge stops moving and shows the block instead.
     */
    fun clampSteps(count: Int, steps: Int, increment: Int): Int =
        if (steps >= 0) steps else steps.coerceAtLeast(blockStep(count, increment))
}
