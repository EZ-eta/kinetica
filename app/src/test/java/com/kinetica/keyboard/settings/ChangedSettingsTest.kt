package com.kinetica.keyboard.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangedSettingsTest {

    private fun entry(key: String) = SettingsIndex.Entry(key, key, "", "pref_group_typing", "Typing")

    @Test
    fun aValueEqualToItsDefaultIsNotAChange() {
        val tree = listOf(entry("a"), entry("b"), entry("c"))
        val defaults = mapOf("a" to true, "b" to 44, "c" to "normal")
        val stored = mapOf("a" to true, "b" to 52, "c" to "normal")
        assertEquals(listOf("b"), ChangedSettings.changed(tree, defaults, stored).map { it.key })
    }

    @Test
    fun anAbsentKeyIsAtItsDefault() {
        // Never written: the row shows its default and nothing was moved.
        assertTrue(ChangedSettings.changed(listOf(entry("a")), mapOf("a" to false), emptyMap<String, Any>()).isEmpty())
    }

    @Test
    fun setsCompareAsSetsAndColoursWithoutCase() {
        assertFalse(ChangedSettings.differs(setOf("it", "en"), setOf("en", "it")))
        assertTrue(ChangedSettings.differs(setOf("en"), setOf("en", "it")))
        assertFalse(ChangedSettings.differs("#3f51b5", "#3F51B5"))
        assertTrue(ChangedSettings.differs("#FF0000", "#3F51B5"))
    }

    @Test
    fun aRowWithNoDefaultIsChangedOnlyByText() {
        // The comma's own text and the like start empty; a number with no default cannot be judged.
        assertTrue(ChangedSettings.differs(";", null))
        assertFalse(ChangedSettings.differs("", null))
        assertFalse(ChangedSettings.differs(7, null))
    }

    @Test
    fun aColourWithNoXmlDefaultUsesTheCodes() {
        val default = ChangedSettings.defaultOf(Prefs.THEME_COLOR_HEX, emptyMap())
        assertEquals(Prefs.colorHexDefault(Prefs.THEME_COLOR_HEX), default)
        val tree = listOf(entry(Prefs.THEME_COLOR_HEX))
        assertTrue(ChangedSettings.changed(tree, emptyMap(), mapOf(Prefs.THEME_COLOR_HEX to default)).isEmpty())
        assertEquals(1, ChangedSettings.changed(tree, emptyMap(), mapOf(Prefs.THEME_COLOR_HEX to "#000000")).size)
    }

    @Test
    fun theLanguageSettingsAreListedButNeverReset() {
        // The keyboard writes the arrangement with the language; a reset of one alone could leave
        // a language active that is not enabled.
        val tree = listOf(entry(Prefs.LANGUAGE), entry(Prefs.KEY_ARRANGEMENT))
        val changed = ChangedSettings.changed(tree, mapOf(Prefs.LANGUAGE to "en", Prefs.KEY_ARRANGEMENT to "qwerty"), mapOf(Prefs.LANGUAGE to "de", Prefs.KEY_ARRANGEMENT to "qwertz"))
        assertEquals(2, changed.size)
        assertTrue(changed.none { ChangedSettings.resettable(it.key) })
        assertTrue(ChangedSettings.resettable(Prefs.AUTOCORRECT_LEVEL))
    }

    @Test
    fun aKeyListedTwiceInTheTreeIsOneRow() {
        // A row can be reached twice when the tree is walked from the root and from a screen.
        val tree = listOf(entry("a"), entry("a"))
        assertEquals(1, ChangedSettings.changed(tree, mapOf("a" to 1), mapOf("a" to 2)).size)
    }
}
