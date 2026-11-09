package com.kinetica.keyboard.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** `parseEnterAlternates` is pure `String? -> List<String>`, testable without SharedPreferences. */
class KeyboardConfigTest {

    private val default = listOf("?", "!", ",")

    @Test
    fun theNoSpaceListDropsSpacesAndABlankFieldIsTheDefault() {
        assertEquals(Prefs.DEFAULT_NO_SPACE_BEFORE, KeyboardConfig.parseNoSpaceBefore(null))
        assertEquals(Prefs.DEFAULT_NO_SPACE_BEFORE, KeyboardConfig.parseNoSpaceBefore("  "))
        assertEquals(".,!?", KeyboardConfig.parseNoSpaceBefore(" . , ! ?"))
    }

    @Test
    fun blankOrNullFallsBackToDefault() {
        assertEquals(default, KeyboardConfig.parseEnterAlternates(null))
        assertEquals(default, KeyboardConfig.parseEnterAlternates(""))
        assertEquals(default, KeyboardConfig.parseEnterAlternates("   "))
    }

    @Test
    fun parsesTheDefaultString() {
        assertEquals(default, KeyboardConfig.parseEnterAlternates("? ! ,"))
    }

    @Test
    fun parsesCustomSymbolsAndTrimsWhitespace() {
        assertEquals(listOf(";", ".", "?"), KeyboardConfig.parseEnterAlternates("  ;   .   ?  "))
    }

    @Test
    fun capsAtThreeSymbols() {
        assertEquals(listOf("(", ")", ":"), KeyboardConfig.parseEnterAlternates("( ) : ; ."))
    }

    @Test
    fun fewerThanThreeSymbolsKept() {
        assertEquals(listOf(":"), KeyboardConfig.parseEnterAlternates(":"))
        assertEquals(listOf("(", ")"), KeyboardConfig.parseEnterAlternates("( )"))
    }

    @Test
    fun theBackspaceStepIsClampedToTheSlider() {
        assertEquals(40f, MapPrefs.config().backspaceStepDp, 0f)
        assertEquals(24f, MapPrefs.config(mapOf(Prefs.BACKSPACE_STEP_DP to 24)).backspaceStepDp, 0f)
        assertEquals(20f, MapPrefs.config(mapOf(Prefs.BACKSPACE_STEP_DP to 3)).backspaceStepDp, 0f)
        assertEquals(80f, MapPrefs.config(mapOf(Prefs.BACKSPACE_STEP_DP to 500)).backspaceStepDp, 0f)
    }

    @Test
    fun keyBordersAreOffUntilTurnedOnAndTheWidthIsClamped() {
        assertEquals(0, MapPrefs.config().keyBorderDp)
        assertEquals(0, MapPrefs.config(mapOf(Prefs.KEY_BORDER_WIDTH to 3)).keyBorderDp)
        assertEquals(1, MapPrefs.config(mapOf(Prefs.KEY_BORDERS to true)).keyBorderDp)
        assertEquals(3, MapPrefs.config(mapOf(Prefs.KEY_BORDERS to true, Prefs.KEY_BORDER_WIDTH to 3)).keyBorderDp)
        assertEquals(4, MapPrefs.config(mapOf(Prefs.KEY_BORDERS to true, Prefs.KEY_BORDER_WIDTH to 9)).keyBorderDp)
        assertEquals(1, MapPrefs.config(mapOf(Prefs.KEY_BORDERS to true, Prefs.KEY_BORDER_WIDTH to 0)).keyBorderDp)
    }

    @Test
    fun theTypedThemeColourIsReadAndACorruptOneIsTheDefault() {
        assertEquals(0xFF2E7D32.toInt(), MapPrefs.config(mapOf(Prefs.THEME_COLOR_HEX to "#2E7D32")).themeColor)
        assertEquals(0xFF475DFF.toInt(), MapPrefs.config().themeColor)
        assertEquals(0xFF475DFF.toInt(), MapPrefs.config(mapOf(Prefs.THEME_COLOR_HEX to "#GG")).themeColor)
    }

    @Test
    fun aTypedTrailColourKeepsItsOwnToneAndThePresetsKeepTheirs() {
        val custom = MapPrefs.config(mapOf(Prefs.TRAIL_COLOR to "custom", Prefs.TRAIL_COLOR_HEX to "#804020"))
        assertEquals(20f, custom.trailBaseHue, 0.5f)
        assertEquals(0.75f, custom.trailSaturation, 0.01f)
        assertEquals(0x80 / 255f, custom.trailValue, 0.001f)
        // The hex stays, unused, when a preset is picked.
        val red = MapPrefs.config(mapOf(Prefs.TRAIL_COLOR to "red", Prefs.TRAIL_COLOR_HEX to "#804020"))
        assertEquals(0f, red.trailBaseHue, 0f)
        assertEquals(0.85f, red.trailSaturation, 0f)
        assertEquals(1f, red.trailValue, 0f)
    }

    @Test
    fun barWordsCarryNoMarkUntilChosen() {
        assertEquals(false, MapPrefs.config().barWordMarks)
        assertEquals(true, MapPrefs.config(mapOf(Prefs.BAR_WORD_MARKS to true)).barWordMarks)
    }

    @Test
    fun theFirstBarWordIsBoldAndTheLettersLastUntilChosen() {
        assertEquals(false, MapPrefs.config().barSpaceBold)
        assertEquals(true, MapPrefs.config(mapOf(Prefs.BAR_SPACE_BOLD to true)).barSpaceBold)
    }

    @Test
    fun aSpaceKeepsTheSymbolPageUntilChosen() {
        assertEquals(false, MapPrefs.config().lettersAfterSpace)
        assertEquals(true, MapPrefs.config(mapOf(Prefs.LETTERS_AFTER_SPACE to true)).lettersAfterSpace)
    }

    @Test
    fun shiftSwapsNoSlideUntilChosen() {
        assertEquals(false, MapPrefs.config().shiftSwapsSlides)
        assertEquals(true, MapPrefs.config(mapOf(Prefs.SHIFT_SWAPS_SLIDES to true)).shiftSwapsSlides)
    }

    @Test
    fun theShortcutsButtonIsOffUntilChosen() {

        assertEquals(false, MapPrefs.config().shortcutsButton)
        assertEquals(true, MapPrefs.config(mapOf(Prefs.SHORTCUTS_BUTTON to true)).shortcutsButton)
    }


    @Test
    fun barWordsAreCutAtTheirEndUntilTheMiddleCutIsChosen() {
        assertEquals(false, MapPrefs.config().barMiddleCut)
        assertEquals(true, MapPrefs.config(mapOf(Prefs.BAR_MIDDLE_CUT to true)).barMiddleCut)
    }

    @Test
    fun theSwipeLookIsTheTrailUntilChosen() {
        assertEquals(com.kinetica.keyboard.ui.SwipeLook.TRAIL, MapPrefs.config().swipeLook)
        assertEquals(com.kinetica.keyboard.ui.SwipeLook.KEYS, MapPrefs.config(mapOf(Prefs.SWIPE_LOOK to "keys")).swipeLook)
    }
}
