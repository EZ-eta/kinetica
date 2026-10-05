package com.kinetica.keyboard.ui

/**
 * The arithmetic of the suggestion bar's long-press weight slide, including the
 * step past the bottom that blocks a word outright.
 *
 * The personal count clamps at zero (KineticaIME.learnWord, and again in SQL), so downward
 * travel past zero did nothing; block is bound to that travel and needs no second gesture or
 * timer. It arms one full step below the step that zeroed the word, so pushing a word down to
 * nothing cannot overshoot into a block.
 *
 * Pure, so the rule is testable without a view.
 */
object BarAdjust {

    /**
     * Whether a word's rank badge is drawn. With [whileAdjusting] on, only the word a slide is
     * adjusting shows one, as with the block mark, for a cleaner bar.
     */
    fun badgeShown(whileAdjusting: Boolean, adjustingThis: Boolean): Boolean = !whileAdjusting || adjustingThis

    /** Whether a recent column's [cell] may be slid: only the committed word in the middle row. */
    fun recentCellAdjustable(cell: Int, rows: Int): Boolean = cell >= 0 && cell % rows == rows / 2

    /** The ceiling a slide can raise a count to, the import cap: a few steps up cannot overflow. */
    const val MAX_COUNT = 10_000

    /**
     * The count a word would end up with after [steps] of slide, one badge tier per step (the
     * developer's choice). A release in place adds [increment], the plain long-press reinforce.
     * Each step up doubles that, each step down halves the count, floored at zero like the store.
     * The ranking boost grows with `ln(1 + count)`, so a halving is one steady unit of rank: a
     * word at 300 blocks in 10 steps, against 61 at a fixed 5 per step.
     */
    fun effectiveCount(count: Int, steps: Int, increment: Int): Int {
        val c = count.coerceAtLeast(0)
        if (steps < 0) return if (-steps >= 31) 0 else c shr -steps
        var v = (c + increment.coerceAtLeast(0)).toLong()
        repeat(steps.coerceAtMost(16)) { v = (v * 2).coerceAtMost(MAX_COUNT.toLong()) }
        return v.coerceAtMost(MAX_COUNT.toLong()).toInt()
    }

    /** Signed weight change for [steps] of slide on a word at [count]. */
    fun delta(count: Int, steps: Int, increment: Int): Int =
        effectiveCount(count, steps, increment) - count.coerceAtLeast(0)

    /**
     * Minimum downward steps before a block can arm, whatever the word's weight. A word with no
     * personal weight would otherwise block on the first step down, from one stray slide.
     */
    const val MIN_BLOCK_STEPS = -2

    /**
     * The step at which a block arms: one below the first step whose count is zero, and never
     * sooner than [MIN_BLOCK_STEPS]. Always negative, and always past the step that zeroes the
     * word.
     */
    fun blockStep(count: Int): Int {
        // Halvings to reach zero: one more than the count's highest bit.
        val toZero = if (count <= 0) 0 else -(32 - Integer.numberOfLeadingZeros(count))
        return minOf(toZero - 1, MIN_BLOCK_STEPS)
    }

    /** True when [steps] of downward slide has reached the blocking step. */
    fun blockArmed(count: Int, steps: Int): Boolean {
        if (steps >= 0) return false
        return steps <= blockStep(count)
    }

    /**
     * Whether an armed slide applies its delta on lift, or hands the lift back to the
     * tap it was armed on top of.
     *
     * In correction mode a hold that never slid is a slow tap: an aimed pick on the strip is
     * slower than a typing tap, and the arm's only feedback is a badge under the thumb, so
     * picks were being eaten silently. Picking learns the word anyway, and one step of slide
     * still adjusts and blocks. In composition mode a release in place stays the plain
     * long-press reinforce.
     */
    fun appliesOnLift(correctionMode: Boolean, steps: Int): Boolean =
        !(correctionMode && steps == 0)

    /**
     * [steps] clamped so the slide cannot travel past the blocking step. Further travel changes
     * nothing, so the armed state is readable: the badge stops and shows the block.
     */
    fun clampSteps(count: Int, steps: Int): Int =
        if (steps >= 0) steps.coerceAtMost(MAX_UP_STEPS) else steps.coerceAtLeast(blockStep(count))

    /** Up steps past this only repeat [MAX_COUNT]. */
    private const val MAX_UP_STEPS = 12

    /**
     * Whether a touch on a recent-word column that has not yet armed the slide is a swap: more
     * vertical than horizontal and past [swipePx]. A swap cancels the pending arm, so the
     * column's swap and the slide never read the same travel.
     */
    fun recentSwap(dx: Float, dy: Float, swipePx: Float): Boolean =
        kotlin.math.abs(dy) > swipePx && kotlin.math.abs(dy) > kotlin.math.abs(dx)
}
