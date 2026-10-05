package com.kinetica.keyboard.ime

import com.kinetica.keyboard.settings.Prefs
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
    // ---- The sync is opt-out, and only the inbound half stops ------------------
    //
    // Off, a stored choice always wins. Android assigns the subtype matching the system
    // locale unless the user forced one through its picker, so following it pulled an English
    // phone back to English at every cold start. The outbound half is untouched: KineticaIME
    // still asks Android to follow when the language changes inside the keyboard.

    @Test
    fun aStoredLanguageSurvivesTheSystemLocaleWhenSyncIsOff() {
        assertEquals("pl", languageOnInputStart("pl", "pl", "en", followSystem = false))
    }

    @Test
    fun aFirstRunStillTakesTheSubtypeWithSyncOff() {
        // Nothing stored is the one case Android still answers: the alternative is
        // defaulting a fresh install to English on a Polish phone.
        assertEquals("it", languageOnInputStart(null, null, "it", followSystem = false))
    }

    @Test
    fun anUnacknowledgedSettingsEditStillWinsWithSyncOff() {
        assertEquals("pl", languageOnInputStart("pl", "it", "it", followSystem = false))
    }

    // ---- The Norwegian subtype code -------------------------------------------------
    //
    // method.xml declares nb_NO, the right locale for Bokmal, while the asset and
    // ALL_LANGUAGES use "no", so the two are folded together or Norwegian never syncs.

    @Test
    fun theNorwegianSubtypeResolvesToTheKineticaCode() {
        assertEquals("no", kineticaLanguageOf("nb"))
        assertEquals("no", kineticaLanguageOf("nn"))
    }

    @Test
    fun everyOtherSubtypeCodeIsPassedThrough() {
        for (lang in Prefs.ALL_LANGUAGES) {
            if (lang == "no") continue
            assertEquals(lang, kineticaLanguageOf(lang))
        }
        assertEquals(null, kineticaLanguageOf(null))
    }

    @Test
    fun everyDeclaredSubtypeFoldsOntoALanguageWeShip() {
        // The locales in method.xml, one per declared subtype. A code that is not in
        // ALL_LANGUAGES has no wordlist, so acceptSubtypeLanguage refuses it and the
        // picker entry is dead, as nb_NO once was.
        val declared = listOf(
            "en_US", "it_IT", "es_ES", "pl_PL", "cs_CZ", "nl_NL", "de_DE", "fr_FR", "nb_NO",
            "ru_RU", "iw_IL", "ar",
        )
        for (locale in declared) {
            val code = kineticaLanguageOf(locale.substringBefore('_'))
            assertTrue("$locale -> $code", code in Prefs.ALL_LANGUAGES)
        }
    }

    @Test
    fun androidIsOfferedTheSubtypesOfTheEnabledLanguagesOnly() {
        // English off, and the picker still said English (US).
        val declared = listOf("en_US", "it_IT", "es_ES", "pl_PL", "cs_CZ", "nl_NL", "de_DE", "fr_FR", "nb_NO")
        assertEquals(listOf(1, 3), subtypesFor(declared, listOf("it", "pl")))
        assertEquals(listOf(8), subtypesFor(declared, setOf("no")))
        assertEquals(emptyList<Int>(), subtypesFor(declared, emptyList()))
        assertEquals(emptyList<Int>(), subtypesFor(listOf(null), listOf("en")))
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

    // ---- The arrangement the language asks for ---------------------------------
    //
    // The setting is global and the AZERTY board is per-language, so a value written for
    // French and left behind would read "AZERTY" in Settings beside a German QWERTZ board.
    // Both directions are decided here, and so is the user's own choice, which never moves.

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
