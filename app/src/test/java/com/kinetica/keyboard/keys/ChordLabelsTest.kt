package com.kinetica.keyboard.keys

import org.junit.Assert.assertEquals
import org.junit.Test

/** A chord's label on its key while the trigger is held: short enough for a key cap. */
class ChordLabelsTest {

    @Test
    fun anActionShowsItsBarSymbol() {
        assertEquals("⎘", ChordLabels.of(EditorAction.PASTE.output))
        assertEquals("⇄", ChordLabels.of(EditorAction.NEXT_LANGUAGE.output))
    }

    @Test
    fun aCombinationShowsItsModifierSymbols() {
        assertEquals("⌃A", ChordLabels.of("combo:ctrl+a"))
        assertEquals("⌃7", ChordLabels.of("combo:ctrl+7"))
    }

    @Test
    fun aCombinationShowsItsKeyAsTheBarDrawsIt() {
        // `⌃Delete` and `⌃Page up` spilled past the key.
        assertEquals("⌃⌦", ChordLabels.of("combo:ctrl+delete"))
        assertEquals("⌃⇞", ChordLabels.of("combo:ctrl+pageup"))
        assertEquals("⌃⇧←", ChordLabels.of("combo:ctrl+shift+left"))
        assertEquals("⌃⌥⇥", ChordLabels.of("combo:ctrl+alt+tab"))
        // The modifiers in the order the settings list reads them, Ctrl+Shift+Alt.
        assertEquals("⌃⇧⌥→", ChordLabels.of("combo:ctrl+alt+shift+right"))
        assertEquals("⌃⌫", ChordLabels.of("combo:ctrl+backspace"))
        assertEquals("⇧⏎", ChordLabels.of("combo:shift+enter"))
        assertEquals("⌃␣", ChordLabels.of("combo:ctrl+space"))
        // Every key a combination can name draws as one symbol or one character.
        for (key in KeyCombo.SPECIAL.keys) {
            val label = ChordLabels.of("combo:ctrl+$key")
            assertEquals(key, 2, label.codePointCount(0, label.length))
        }
        // The settings list keeps the words.
        assertEquals("Ctrl+Delete", KeyCombo.parse("combo:ctrl+delete")!!.label())
    }

    @Test
    fun textIsCutToTheStartOfIt() {
        assertEquals("=]", ChordLabels.of("=]"))
        assertEquals("mail", ChordLabels.of("mail"))
        assertEquals("mai…", ChordLabels.of("mail2@domain.org"))
    }
}
