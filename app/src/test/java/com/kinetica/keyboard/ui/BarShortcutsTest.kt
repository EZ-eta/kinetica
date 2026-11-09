package com.kinetica.keyboard.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shortcut row and the button that forces it over the words. */
class BarShortcutsTest {

    private fun row(
        actions: Boolean = true,
        words: Boolean = false,
        correction: Boolean = false,
        pending: Boolean = false,
        forced: Boolean = false,
    ) = BarShortcuts.rowShown(actions, words, correction, pending, forced)

    @Test
    fun anEmptyBarShowsTheRow() {
        assertTrue(row())
    }

    @Test
    fun wordsTheStripOrAWordInProgressKeepTheRowDown() {
        assertFalse(row(words = true))
        assertFalse(row(words = true, correction = true))
        assertFalse(row(pending = true))
    }

    @Test
    fun theButtonForcesTheRowOverAnyOfThem() {
        assertTrue(row(words = true, forced = true))
        assertTrue(row(words = true, correction = true, forced = true))
        assertTrue(row(words = true, pending = true, forced = true))
    }

    @Test
    fun noShortcutsNoRow() {
        assertFalse(row(actions = false))
        assertFalse(row(actions = false, words = true, forced = true))
    }

    @Test
    fun theButtonShowsOnlyWithWordsToCoverAndShortcutsToShow() {
        assertTrue(BarShortcuts.buttonShown(enabled = true, hasActions = true, hasWords = true))
        assertFalse("switch off", BarShortcuts.buttonShown(enabled = false, hasActions = true, hasWords = true))
        assertFalse("the row is already up", BarShortcuts.buttonShown(enabled = true, hasActions = true, hasWords = false))
        assertFalse("nothing to show", BarShortcuts.buttonShown(enabled = true, hasActions = false, hasWords = true))
    }
}
