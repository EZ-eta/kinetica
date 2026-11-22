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
 * The keys a swipe has crossed, lit and fading behind the thumb: the swipe drawn on the board
 * itself instead of as a line over it.
 *
 * Per key, when it was last crossed and in which hue; a key crossed again starts over. Arrays
 * are sized once per layout, so marking and drawing allocate nothing. No Android imports, so
 * the fade is testable without a device.
 */
class KeyWake {

    private var markedAt = LongArray(0)
    private var hues = FloatArray(0)
    private var lastMark = NEVER

    val size: Int
        get() = markedAt.size

    /** One slot per key of a new layout; whatever was lit goes. */
    fun resize(keys: Int) {
        if (keys != markedAt.size) {
            markedAt = LongArray(keys)
            hues = FloatArray(keys)
        }
        clear()
    }

    fun clear() {
        markedAt.fill(NEVER)
        lastMark = NEVER
    }

    /** [key] was crossed at [now] by a thumb drawing in [hue]. */
    fun mark(key: Int, hue: Float, now: Long) {
        if (key !in markedAt.indices) return
        markedAt[key] = now
        hues[key] = hue
        lastMark = now
    }

    /** 1 at the crossing, falling to 0 over [FADE_MS]. */
    fun lightAt(key: Int, now: Long): Float {
        if (key !in markedAt.indices || markedAt[key] == NEVER) return 0f
        val age = (now - markedAt[key]).coerceAtLeast(0L)
        if (age >= FADE_MS) return 0f
        return 1f - age / FADE_MS.toFloat()
    }

    fun hueOf(key: Int): Float = if (key in hues.indices) hues[key] else 0f

    /** True while any key is still lit. */
    fun anyLit(now: Long): Boolean = lastMark != NEVER && now - lastMark < FADE_MS

    companion object {
        /**
         * Longer than the trail's 250 ms: a key lights once per crossing, not per sample, so a
         * shorter fade leaves little more than the key under the thumb. Set by eye, a look.
         */
        const val FADE_MS = 400L

        /** The edge's width when lit and when nearly gone, in dp. */
        const val WIDTH_LIT_DP = 4f
        const val WIDTH_FADED_DP = 1.5f

        /** The edge narrows as it fades, so the newest keys read as the front of the swipe. */
        fun widthDp(light: Float): Float =
            WIDTH_FADED_DP + (WIDTH_LIT_DP - WIDTH_FADED_DP) * light.coerceIn(0f, 1f)

        private const val NEVER = Long.MIN_VALUE / 2
    }
}
