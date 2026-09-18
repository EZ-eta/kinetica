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

    // ---- R83: the arrangement the language asks for ---------------------------------
    //
    // The setting is global and the AZERTY board is per-language, so a value written for
    // French and left behind would read "AZERTY" in Settings beside a German QWERTZ board.
    // Both directions are decided here, and so is the case that must not move.

    @Test
    fun frenchTakesAzertyFromTheDefault() {
        val c = arrangementOnLanguageChange("qwerty", autoApplied = false, language = "fr")
        assertEquals("azerty", c.arrangement)
        assertTrue(c.autoApplied)
    }

    @Test
    fun leavingFrenchHandsTheArrangementBack() {
        val c = arrangementOnLanguageChange("azerty", autoApplied = true, language = "de")
        assertEquals("qwerty", c.arrangement)
        assertFalse(c.autoApplied)
    }

    @Test
    fun anArrangementTheUserChoseIsNeverTouched() {
        // A QWERTZ writer who types some French keeps QWERTZ, and gets a French board that
        // LayoutMutations then declines to permute because azerty_fr is fixedArrangement.
        val toFrench = arrangementOnLanguageChange("qwertz", autoApplied = false, language = "fr")
        assertEquals(null, toFrench.arrangement)
        assertFalse(toFrench.autoApplied)
        // And a user who set AZERTY globally keeps it in every language. This is the case
        // a naive "reset on leaving French" gets wrong.
        val away = arrangementOnLanguageChange("azerty", autoApplied = false, language = "de")
        assertEquals(null, away.arrangement)
        assertFalse(away.autoApplied)
    }

    @Test
    fun stayingOnFrenchWritesNothingTwice() {
        // Already ours and already AZERTY: no write, and the marker stays set so leaving
        // still hands it back.
        val c = arrangementOnLanguageChange("azerty", autoApplied = true, language = "fr")
        assertEquals(null, c.arrangement)
        assertTrue(c.autoApplied)
    }

    @Test
    fun aMarkerIsDroppedOnceTheValueIsNoLongerOurs() {
        // The user set QWERTZ by hand while French was active. The marker is stale from
        // then on, and carrying it would hand QWERTZ back to QWERTY on the way out.
        val c = arrangementOnLanguageChange("qwertz", autoApplied = true, language = "fr")
        assertEquals(null, c.arrangement)
        assertFalse(c.autoApplied)
        val away = arrangementOnLanguageChange("qwertz", autoApplied = true, language = "de")
        assertEquals(null, away.arrangement)
        assertFalse(away.autoApplied)
    }
}
