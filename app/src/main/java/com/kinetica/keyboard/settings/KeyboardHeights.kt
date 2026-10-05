package com.kinetica.keyboard.settings

import com.kinetica.keyboard.engine.KineticaConstants
import com.kinetica.keyboard.layout.LayoutMutations

/**
 * Keyboard height bounds in pixels: the percentage the user chose, the absolute
 * dp floor, and the screen-percentage ceiling.
 *
 * Pure and separate from the service so it can be tested: an inverted `coerceIn` range here, the
 * dp floor above the percentage ceiling on a short screen, crashes the keyboard process the moment
 * it opens. [minPx] is the guard.
 */
object KeyboardHeights {

    /**
     * Absolute floor for the keyboard's own height, chrome excluded.
     *
     * 96dp = four key rows (every row is `h: 0.25`) at 24dp, and 24dp is 2 x
     * [KineticaConstants.TAP_MAX_DISP_DP]: a tap may drift 12dp, so at a 24dp row a tap from a
     * key's centre reaches its row edge and no further. Below that a legal tap can leave its row,
     * and tap classification and row discrimination lose their margin.
     *
     * 180dp was too tall on an unfolded foldable. Cost: `kw` comes from key width alone, so row
     * pitch in kw falls with this value, to ~0.3 kw on a wide screen at this floor against the
     * 1.5 the geometric constants were tuned on. No single-thumb decode flips,
     * but the margin to the nearest wrong word shrinks about 3x.
     */
    const val MIN_KEYBOARD_DP = 96f

    /**
     * Widest the resize handle may be. 20dp is the original size; the strip is pure chrome and
     * its pill is a quarter of its height, so a narrower strip still shows a grip.
     */
    const val MAX_HANDLE_DP = 20

    /**
     * The handle's height in dp.
     *
     * [storedDp] is null until the height setting is first written, as on an upgrade from the
     * old on/off switch. Those users keep what they had: full height when the switch was on,
     * zero (no strip) when it was off. Zero as the off state, not a separate flag, keeps the
     * strip a child of the container, so it resizes live without rebuilding the input view.
     */
    fun handleDp(storedDp: Int?, legacyHandleOn: Boolean): Int =
        (storedDp ?: if (legacyHandleOn) MAX_HANDLE_DP else 0).coerceIn(0, MAX_HANDLE_DP)

    fun maxPx(screenHeightPx: Int): Int = screenHeightPx * Prefs.MAX_HEIGHT_PCT / 100

    /**
     * The larger of the two floors, held under [maxPx]: on short screens (landscape phones,
     * split-screen) half the screen is less than the dp floor, and the ceiling has to win or the
     * range inverts.
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

    /** How much taller the bar is while recent words share it: a column of three at half size. */
    const val RECENT_BAR_TALL = 1.5f

    /** The board for a letter area of [letterPx]: a row taller with the numbers row. */
    fun boardPx(letterPx: Int, numberRow: Boolean): Int =
        if (numberRow) (letterPx * LayoutMutations.NUMBER_ROW_GROWTH).toInt() else letterPx

    /** The letter area a dragged board of [boardPx] stands for; the setting stores this. */
    fun letterPx(boardPx: Int, numberRow: Boolean): Int =
        if (numberRow) (boardPx / LayoutMutations.NUMBER_ROW_GROWTH).toInt() else boardPx

    /** The suggestion bar's height in dp: one row, or taller with recent words on. */
    fun barDp(settingDp: Int, recentWords: Boolean): Float =
        settingDp * if (recentWords) RECENT_BAR_TALL else 1f
}
