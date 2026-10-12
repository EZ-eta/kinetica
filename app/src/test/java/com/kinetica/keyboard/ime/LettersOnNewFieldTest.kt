package com.kinetica.keyboard.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A new field opens on the letters; a restart of the same field keeps the page. */
class LettersOnNewFieldTest {

    @Test
    fun aNewFieldLeftOnSymbolsComesBackToLetters() {
        assertTrue(opensOnLetters(restarting = false, enabled = true, onLetters = false))
    }

    @Test
    fun aRestartedFieldKeepsItsPage() {
        // An app restarting its own field mid-edit is not a new field.
        assertFalse(opensOnLetters(restarting = true, enabled = true, onLetters = false))
    }

    @Test
    fun theSettingOffKeepsThePage() {
        assertFalse(opensOnLetters(restarting = false, enabled = false, onLetters = false))
    }

    @Test
    fun lettersAlreadyShownAreNotRebuilt() {
        assertFalse(opensOnLetters(restarting = false, enabled = true, onLetters = true))
    }
}
