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
 * The curve arithmetic behind a swipe trail: one quadratic per sample, laid out so
 * consecutive pieces meet exactly.
 *
 * Straight segments read as facets on a fast swipe. Each piece runs from the midpoint of the
 * previous pair, bends through the sample, and ends at the midpoint of the next pair, so the
 * sample is a control point, not a corner. One piece per sample keeps the per-sample colour
 * and width taper that a single path with one paint would lose.
 *
 * No Android imports, so the joins are testable without a device.
 */
object TrailPath {

    /** Floats one quadratic needs: start, control, end. */
    const val SIZE = 6

    /**
     * Writes the piece centred on (`curX`, `curY`) into [out] as
     * `[startX, startY, ctrlX, ctrlY, endX, endY]`.
     *
     * A run's first piece starts at the previous sample and its last ends at the current one,
     * so the trail reaches its own tail and the finger. Every piece in between shares its
     * endpoints with its neighbours, so the curve is continuous.
     */
    fun quadInto(
        out: FloatArray,
        prevX: Float,
        prevY: Float,
        curX: Float,
        curY: Float,
        nextX: Float,
        nextY: Float,
        isFirst: Boolean,
        isLast: Boolean,
    ) {
        out[0] = if (isFirst) prevX else (prevX + curX) / 2f
        out[1] = if (isFirst) prevY else (prevY + curY) / 2f
        out[2] = curX
        out[3] = curY
        out[4] = if (isLast) curX else (curX + nextX) / 2f
        out[5] = if (isLast) curY else (curY + nextY) / 2f
    }
}
