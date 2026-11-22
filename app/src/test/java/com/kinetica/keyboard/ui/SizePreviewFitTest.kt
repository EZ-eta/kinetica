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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The size picture stays inside the settings screen. Pixel 7: 1080 x 2400, a portrait keyboard
 * of about 1 000 px, the settings 1 600 px wide sideways.
 */
class SizePreviewFitTest {

    private val band = 84f

    @Test
    fun thePortraitPictureOnALandscapeScreenIsCappedAtHalfItsHeight() {
        val maxH = 1080f * SizePreviewFit.MAX_SCREEN_SHARE
        val w = SizePreviewFit.widthPx(1500f, 1080f, 1000f, band, maxH)
        assertTrue("not narrowed: $w", w < 1500f)
        assertEquals(maxH, SizePreviewFit.heightPx(w, 1080f, 1000f, band), 0.5f)
    }

    @Test
    fun aPictureThatFitsKeepsTheFullWidth() {
        // The landscape phone, drawn upright: short, so nothing to narrow.
        val maxH = 2400f * SizePreviewFit.MAX_SCREEN_SHARE
        assertEquals(1000f, SizePreviewFit.widthPx(1000f, 2400f, 500f, band, maxH), 0f)
    }

    @Test
    fun narrowingKeepsTheKeyboardsProportions() {
        val maxH = 600f
        val w = SizePreviewFit.widthPx(2000f, 1080f, 1000f, band, maxH)
        // Width over keyboard height is the phone's, the band aside.
        assertEquals(1080f / 1000f, w / (SizePreviewFit.heightPx(w, 1080f, 1000f, band) - band), 1e-3f)
    }

    @Test
    fun aDegenerateSpecDrawsAtTheViewWidth() {
        assertEquals(800f, SizePreviewFit.widthPx(800f, 0f, 1000f, band, 500f), 0f)
        assertEquals(0f, SizePreviewFit.widthPx(800f, 1080f, 1000f, band, 10f), 0f)
    }
}
