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

package com.kinetica.keyboard.ui

/**
 * How wide the size picture is drawn. Scaled to the view's width alone, the portrait phone drawn
 * across a landscape settings screen came out taller than the screen.
 */
object SizePreviewFit {

    /** The picture never takes more than this share of the settings screen's height. */
    const val MAX_SCREEN_SHARE = 0.5f

    /**
     * The view's width, narrowed until the picture is no taller than [maxH]. [contentH] is the
     * keyboard's height in the phone's pixels on a screen [screenW] wide; [bandPx] the app strip
     * above it, drawn at a fixed size.
     */
    fun widthPx(viewW: Float, screenW: Float, contentH: Float, bandPx: Float, maxH: Float): Float {
        if (viewW <= 0f || screenW <= 0f || contentH <= 0f) return viewW.coerceAtLeast(0f)
        if (contentH * viewW / screenW + bandPx <= maxH) return viewW
        return ((maxH - bandPx).coerceAtLeast(0f) * screenW / contentH).coerceAtMost(viewW)
    }

    /** The picture's height at [drawW], band included. */
    fun heightPx(drawW: Float, screenW: Float, contentH: Float, bandPx: Float): Float =
        if (screenW <= 0f) bandPx else contentH * drawW / screenW + bandPx
}
