package com.kinetica.keyboard.ui

/**
 * Suggestion-bar ornament sizes as a function of the bar's own height.
 *
 * The text scales with the bar (`textSize = h * TEXT_FRACTION`), and so do the correction
 * chip, the personal-weight badge and the page dots, authored in dp against a 44dp bar. The
 * reason is proportion, not collision: the dots reach the word only below a 16.7dp bar, but at
 * 24dp a fixed badge spans 11.6px against 25px of text where at 44dp it spans 11.6 against 46.
 * [scale] is 1.0 at [REFERENCE_DP], so the 44dp rendering is unchanged to the pixel.
 *
 * Touch thresholds stay out: the weight slide travels up out of the bar and the page swipe is
 * horizontal, so shrinking them with the bar would only make the gestures harder.
 *
 * Pure, so both claims are testable (BarMetricsTest); the view supplies its measured height.
 */
object BarMetrics {

    /** The height every ornament dp value was chosen against. */
    const val REFERENCE_DP = 44f

    /**
     * Floor for the height setting. At 24dp the text is 9.6dp and the badge ring plus its dots
     * span 3.5dp, which still reads; below it the bar is thinner than its word.
     */
    const val MIN_DP = 24f

    /** Ceiling, roughly two default bars; past that it is wasted screen. */
    const val MAX_DP = 72f

    /** Word text height as a fraction of the bar. */
    const val TEXT_FRACTION = 0.40f

    fun textSize(heightPx: Float): Float = heightPx * TEXT_FRACTION

    /**
     * Multiplier turning a dp ornament size into its size on a bar of [heightPx]; 1.0 when the
     * bar is [REFERENCE_DP] tall.
     */
    fun scale(heightPx: Float, density: Float): Float {
        val reference = REFERENCE_DP * density
        if (reference <= 0f) return 1f
        return heightPx / reference
    }

    /** An ornament authored as [dp] at [REFERENCE_DP], in pixels for this bar. */
    fun ornament(dp: Float, heightPx: Float, density: Float): Float =
        dp * density * scale(heightPx, density)

    /**
     * Retype button width, in dp. A thumb target, so not scaled with the bar; it lives here
     * as the one bar dimension a user can set, beside the other bounds and their test.
     *
     * - [RETYPE_DEFAULT_DP]: small, as the button was first asked for; the slider serves users
     *   who need a target they can hit with a case on.
     * - [RETYPE_MIN_DP]: below about 24dp the glyph is no reliable target at any bar height.
     * - [RETYPE_MAX_DP]: the view's quarter-width cap; wider would crowd the page-flip zone.
     */
    const val RETYPE_DEFAULT_DP = 34
    const val RETYPE_MIN_DP = 24
    const val RETYPE_MAX_DP = 72

    /** [dp] clamped to the settable range. */
    fun retypeDp(dp: Int): Int = dp.coerceIn(RETYPE_MIN_DP, RETYPE_MAX_DP)
}
