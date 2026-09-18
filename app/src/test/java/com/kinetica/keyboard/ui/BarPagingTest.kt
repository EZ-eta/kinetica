package com.kinetica.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which drags across the suggestion bar are pages.
 *
 * The start zone used to be 36dp of the bar's right corner, which is what kept this
 * gesture away from the tap, the upward flick and the long-press slide that share the
 * strip. R54 widened it to the whole bar, so the separation is now arithmetic and this is
 * where it is checked. The travel is in pixels; the view multiplies by density.
 */
class BarPagingTest {

    private val travel = 30f

    @Test
    fun aLeftwardDragGoesForward() {
        assertEquals(1, BarPaging.pageFor(page = 0, pageCount = 3, dx = -40f, dy = 0f, travelPx = travel))
    }

    @Test
    fun aRightwardDragGoesBack() {
        // The half of the request the old gesture had no answer for: reaching page one
        // from page two meant cycling forward through every other page.
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
        // The named cost of widening the start zone: every word zone is now a page-swipe
        // start, so the travel threshold is the only thing between a drifting thumb and a
        // page it did not ask for.
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
        // Equal travel on both axes is refused rather than guessed at, so the ambiguous
        // gesture does nothing instead of doing the wrong one of two things.
        assertEquals(-1, BarPaging.pageFor(page = 0, pageCount = 3, dx = -40f, dy = 40f, travelPx = travel))
        assertEquals(1, BarPaging.pageFor(page = 0, pageCount = 3, dx = -40f, dy = 39f, travelPx = travel))
    }
}
