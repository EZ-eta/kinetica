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
    }

    @Test
    fun textIsCutToTheStartOfIt() {
        assertEquals("=]", ChordLabels.of("=]"))
        assertEquals("mail", ChordLabels.of("mail"))
        assertEquals("mai…", ChordLabels.of("mail2@domain.org"))
    }
}
