package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
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
}
