package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageSelectionTest {

    @Test
    fun coldStartHonorsAndroidWhenLocalLanguageHasNotChanged() {
        assertEquals("it", languageOnInputStart("en", "en", "it"))
    }

    @Test
    fun settingsChangeWhileInactiveWinsOverOldAndroidSubtype() {
        assertEquals("pl", languageOnInputStart("pl", "it", "it"))
    }

    @Test
    fun firstRunUsesTheSelectedAndroidSubtype() {
        assertEquals("it", languageOnInputStart(null, null, "it"))
    }

    @Test
    fun firstSyncPreservesAnExistingLanguagePreference() {
        assertEquals("pl", languageOnInputStart("pl", null, "en"))
    }

    @Test
    fun absentSubtypeKeepsTheConfiguredLanguageOrDefault() {
        assertEquals("pl", languageOnInputStart("pl", "pl", null))
        assertEquals("en", languageOnInputStart(null, null, null))
    }
    // ---------------------------------------------- when the write is worth doing
    //
    // synchronizeLanguageOnStart runs at every input start, and in the steady state where
    // Android and Kinetica already agree the answer is always no.

    @Test
    fun nothingIsWrittenWhenEverythingAlreadyAgrees() {
        assertFalse(languageSyncNeedsWrite("it", "it", "it"))
    }

    @Test
    fun aLanguageChangeIsWritten() {
        assertTrue(languageSyncNeedsWrite("it", "it", "pl"))
    }

    @Test
    fun anUnacknowledgedChoiceIsWritten() {
        // Settings was edited while another IME was selected, so the two disagree and the
        // synced value has to catch up even though the language itself is unchanged.
        assertTrue(languageSyncNeedsWrite("pl", "it", "pl"))
    }

    @Test
    fun aFirstRunIsWritten() {
        assertTrue(languageSyncNeedsWrite(null, null, "en"))
    }

}
