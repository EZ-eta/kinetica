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
import org.junit.Test

/**
 * Which drags across the suggestion bar are pages.
 *
 * A page swipe can start anywhere on the bar, so only arithmetic separates it from
 * the tap, the upward flick and the long-press slide that share the strip. The travel is in
 * pixels; the view multiplies by density.
 */
class BarPagingTest {

    private val travel = 30f

    @Test
    fun aLeftwardDragGoesForward() {
        assertEquals(1, BarPaging.pageFor(page = 0, pageCount = 3, dx = -40f, dy = 0f, travelPx = travel))
    }

    @Test
    fun aRightwardDragGoesBack() {
        // Without it, reaching page one from page two meant cycling forward through every page.
        assertEquals(0, BarPaging.pageFor(page = 1, pageCount = 3, dx = 40f, dy = 0f, travelPx = travel))
    }

    @Test
    fun bothDirectionsWrap() {
        assertEquals(0, BarPaging.pageFor(page = 2, pageCount = 3, dx = -40f, dy = 0f, travelPx = travel))
        assertEquals(2, BarPaging.pageFor(page = 0, pageCount = 3, dx = 40f, dy = 0f, travelPx = travel))
    }

    @Test
    fun oneRowOfSuggestionsHasNowhereToGo() {
        assertEquals(-1, BarPaging.pageFor(page = 0, pageCount = 1, dx = -80f, dy = 0f, travelPx = travel))
        assertEquals(-1, BarPaging.pageFor(page = 0, pageCount = 0, dx = -80f, dy = 0f, travelPx = travel))
    }

    @Test
    fun aTapThatWandersStillCommitsItsWord() {
        // Every word zone is also a page-swipe start, so only the travel threshold keeps a
        // drifting thumb from paging.
        assertEquals(-1, BarPaging.pageFor(page = 0, pageCount = 3, dx = -29f, dy = 0f, travelPx = travel))
        assertEquals(1, BarPaging.pageFor(page = 0, pageCount = 3, dx = -30f, dy = 0f, travelPx = travel))
    }

    @Test
    fun aVerticalGestureIsNotAPage() {
        // The upward flick that commits a word and the long-press weight slide are both
        // vertical, and both travel much further than 30dp. Without this they would page
        // on the way up.
        assertEquals(-1, BarPaging.pageFor(page = 0, pageCount = 3, dx = 0f, dy = -120f, travelPx = travel))
        assertEquals(-1, BarPaging.pageFor(page = 0, pageCount = 3, dx = -40f, dy = -80f, travelPx = travel))
    }

    @Test
    fun aDiagonalGoesToWhicheverAxisIsLonger() {
        // Equal travel on both axes is refused, so an ambiguous gesture does nothing instead of
        // guessing.
        assertEquals(-1, BarPaging.pageFor(page = 0, pageCount = 3, dx = -40f, dy = 40f, travelPx = travel))
        assertEquals(1, BarPaging.pageFor(page = 0, pageCount = 3, dx = -40f, dy = 39f, travelPx = travel))
    }
}
