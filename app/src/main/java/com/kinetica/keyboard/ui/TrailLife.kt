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
 * How long a trail point lasts. It fades over [LIFE_MS] from when it was laid; kept while its word
 * is in progress, it stays whole and fades from the moment the word ends.
 */
object TrailLife {
    const val LIFE_MS = 250L

    /** From 1, newly laid or kept, down to 0, gone: the scale of the point's alpha and width. */
    fun strength(kept: Boolean, t: Long, now: Long): Float =
        if (kept) 1f else 1f - (now - t).coerceAtLeast(0) / LIFE_MS.toFloat()

    fun expired(kept: Boolean, t: Long, now: Long): Boolean = !kept && now - t > LIFE_MS
}
