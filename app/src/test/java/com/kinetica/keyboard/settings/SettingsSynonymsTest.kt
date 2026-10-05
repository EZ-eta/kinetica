package com.kinetica.keyboard.settings

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words that have to reach a setting the tree walk cannot describe.
 *
 * Two invariants. The first decays silently: a setting with no preference row and no synonyms
 * cannot be found at all.
 */
class SettingsSynonymsTest {

    @Test
    fun everySettingWithNoRowOfItsOwnCarriesTerms() {
        // The chord lead-in, the language cycle key and the peck chord key live on
        // ChordSettingsActivity with no entry in keyboard_prefs.xml, so only their terms let a
        // search reach them.
        for (key in SettingsSynonyms.EXTRA_KEYS) {
            assertTrue("$key has no synonyms and nothing else can find it",
                SettingsSynonyms.termsFor(key).isNotEmpty())
        }
    }

    @Test
    fun theWordsPeopleActuallyTypeFindTheirSetting() {
        // Spot checks: none of these words appears in the title of the setting it has to find.
        assertTrue("size" in SettingsSynonyms.termsFor(Prefs.KEYBOARD_HEIGHT_PCT))
        assertTrue("dark" in SettingsSynonyms.termsFor(Prefs.THEME_MODE))
        assertTrue("margin" in SettingsSynonyms.termsFor(Prefs.SIDE_PAD_DP))
        assertTrue("haptic" in SettingsSynonyms.termsFor(Prefs.VIBRATION))
        assertTrue("split" in SettingsSynonyms.termsFor(Prefs.LAYOUT_MODE))
        assertTrue("chord" in SettingsSynonyms.termsFor(Prefs.CHORD_ARM_MS))
    }

    @Test
    fun anUnknownKeyHasNoTermsRatherThanNoAnswer() {
        assertTrue(SettingsSynonyms.termsFor("pref_does_not_exist").isEmpty())
    }
}
