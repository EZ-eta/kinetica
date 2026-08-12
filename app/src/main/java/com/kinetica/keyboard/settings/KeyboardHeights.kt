package com.kinetica.keyboard.settings

import com.kinetica.keyboard.engine.KineticaConstants

/**
 * Keyboard height bounds in pixels: the percentage the user chose, the absolute
 * dp floor, and the screen-percentage ceiling.
 *
 * Pure and separate from the service because this arithmetic has a history. An
 * inverted `coerceIn` range here - the dp floor exceeding the percentage ceiling
 * on a short screen - threw the moment the keyboard opened and took the whole
 * process with it, launcher included. [minPx] is the guard, and until now it
 * lived inside `KineticaIME` where nothing could test it.
 */
object KeyboardHeights {

    /**
     * Absolute floor for the keyboard's own height, chrome excluded.
     *
     * 96dp = four key rows (the layouts give every row `h: 0.25`) at 24dp each,
     * and 24dp is 2 x [KineticaConstants.TAP_MAX_DISP_DP]: a tap may drift 12dp
     * and still classify as a tap, so at a 24dp row a tap starting on a key's
     * centre reaches its row edge and no further. Below that a legal tap can
     * leave the row it began in, and both tap classification and row
     * discrimination lose their margin - so this is where the engine stops
     * agreeing with the layout, not a matter of taste.
     *
     * Lowered from 180dp on a user report that 25% was still too tall on an
     * unfolded foldable, where 180dp is what actually bound rather than the
     * percentage. Known cost, measured: the decode's `kw` unit comes from key
     * WIDTH alone, so row pitch in kw falls linearly with this value, and on a
     * wide screen at this floor it reaches ~0.3 kw against the 1.5 every
     * geometric constant was tuned on. That does not flip a single-thumb decode
     * (swept, KNOWN_ISSUES item 14e) but it compresses the geometric margin to
     * the nearest wrong word by about 3x, so a shorter keyboard is a
     * less forgiving one.
     */
    const val MIN_KEYBOARD_DP = 96f

    fun maxPx(screenHeightPx: Int): Int = screenHeightPx * Prefs.MAX_HEIGHT_PCT / 100

    /**
     * The larger of the two floors, then held under [maxPx] - on short screens
     * (landscape phones, split-screen) half the screen really is less than the dp
     * floor, and the ceiling has to win or the range inverts.
     */
    fun minPx(screenHeightPx: Int, density: Float): Int = minOf(
        maxOf(
            (MIN_KEYBOARD_DP * density).toInt(),
            screenHeightPx * Prefs.MIN_HEIGHT_PCT / 100,
        ),
        maxPx(screenHeightPx),
    )

    /** The user's percentage, held inside the bounds. */
    fun targetPx(screenHeightPx: Int, density: Float, pct: Int): Int =
        ((screenHeightPx * pct) / 100)
            .coerceIn(minPx(screenHeightPx, density), maxPx(screenHeightPx))

    /** Inverse of [targetPx] for persisting a dragged height. */
    fun pctFor(px: Int, screenHeightPx: Int): Int {
        if (screenHeightPx <= 0) return Prefs.DEFAULT_HEIGHT_PCT
        return (px * 100f / screenHeightPx).toInt()
            .coerceIn(Prefs.MIN_HEIGHT_PCT, Prefs.MAX_HEIGHT_PCT)
    }
}
