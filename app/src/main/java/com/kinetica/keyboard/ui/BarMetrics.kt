package com.kinetica.keyboard.ui

/**
 * Suggestion-bar ornament sizes as a function of the bar's own height.
 *
 * The bar's text always scaled with the bar (`textSize = h * TEXT_FRACTION`), but
 * everything drawn beside it - the correction chip, the personal-weight badge, the
 * page dots - was a fixed dp size, chosen to look right against a 44dp bar.
 *
 * The reason is PROPORTION, and it is worth stating precisely because the obvious
 * reason turned out to be false: nothing collided. Solving the geometry, the page
 * dots only reach the word below a 16.7dp bar and never clip the bottom edge at
 * all, so a fixed-size ornament is safe everywhere in [MIN_DP]..[MAX_DP]. What it
 * is not is proportionate - at 24dp the badge spans 11.6px of furniture against
 * 25px of text where at 44dp it spans the same 11.6 against 46. The badge is the
 * word's own annotation, so it should read at the same weight whatever the bar
 * height, which is what a constant ornament-to-text ratio buys.
 *
 * Every ornament is therefore that same dp value scaled by [scale], which is 1.0
 * at [REFERENCE_DP] by construction, so the shipped 44dp rendering is unchanged to
 * the pixel.
 *
 * Touch thresholds are deliberately NOT here. The weight-adjust slide travels
 * upward out of the bar and the page-swipe zone is horizontal, so neither is a
 * function of the bar's height and shrinking them with it would only make the
 * gestures harder.
 *
 * Pure, so both claims are testable (BarMetricsTest); the view supplies its own
 * measured height.
 */
object BarMetrics {

    /** The height every ornament dp value was chosen against. */
    const val REFERENCE_DP = 44f

    /**
     * Floor for the height setting. At 24dp the text is 9.6dp and the badge ring
     * plus its dots span 3.5dp, which still reads; below it the bar is thinner
     * than the word it is drawing.
     */
    const val MIN_DP = 24f

    /** Ceiling, roughly two shipped bars - past that it is just wasted screen. */
    const val MAX_DP = 72f

    /** Word text height as a fraction of the bar. Unchanged from the first ship. */
    const val TEXT_FRACTION = 0.40f

    fun textSize(heightPx: Float): Float = heightPx * TEXT_FRACTION

    /**
     * Multiplier turning a dp ornament size into its size on a bar of
     * [heightPx]. Exactly 1.0 when the bar is [REFERENCE_DP] tall.
     */
    fun scale(heightPx: Float, density: Float): Float {
        val reference = REFERENCE_DP * density
        if (reference <= 0f) return 1f
        return heightPx / reference
    }

    /** An ornament authored as [dp] at [REFERENCE_DP], in pixels for this bar. */
    fun ornament(dp: Float, heightPx: Float, density: Float): Float =
        dp * density * scale(heightPx, density)
}
