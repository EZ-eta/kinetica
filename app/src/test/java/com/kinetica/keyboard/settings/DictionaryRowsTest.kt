package com.kinetica.keyboard.settings

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Dictionary screen lists languages by name. */
class DictionaryRowsTest {

    @Test
    fun anAccentedNameSortsWithItsLetter() {
        val names = listOf("English", "Italiano", "Español", "Polski", "Čeština", "Nederlands", "Deutsch", "Français", "Norsk")
        assertEquals(
            listOf("Čeština", "Deutsch", "English", "Español", "Français", "Italiano", "Nederlands", "Norsk", "Polski"),
            DictionaryRows.byName(names, { it }, Locale.ENGLISH),
        )
    }
}
