package com.kinetica.keyboard.keys

import com.kinetica.keyboard.ime.AlphaLayouts
import com.kinetica.keyboard.layout.Key
import com.kinetica.keyboard.layout.KeyType
import com.kinetica.keyboard.layout.KeyboardLayout
import com.kinetica.keyboard.settings.MapPrefs
import com.kinetica.keyboard.settings.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The long-press lists follow the enabled languages: `ß` on `s` while German is on, without the
 * Default button, and a list the user wrote keeps its own cells.
 */
class LanguageAccentsTest {

    private val cap = 14

    @Test
    fun eachLanguageLetterLandsOnTheKeyItFoldsTo() {
        assertEquals(listOf("ß"), LanguageAccents.of('s', listOf("de")))
        assertEquals(listOf("ä"), LanguageAccents.of('a', listOf("de")))
        assertEquals(listOf("ș", "ś"), LanguageAccents.of('s', listOf("ro", "pl")))
        assertTrue(LanguageAccents.of('s', listOf("en", "it")).isEmpty())
        // Every letter of every language reaches a key of the Latin board.
        for (lang in NativeLetters.languages) {
            for (c in NativeLetters.of(lang)) {
                val hits = ('a'..'z').filter { c.toString() in LanguageAccents.of(it, listOf(lang)) }
                assertEquals("$lang $c", 1, hits.size)
            }
        }
    }

    @Test
    fun aBuiltInListGainsTheLettersAfterItsLast() {
        assertEquals(listOf("š", "ß", "#"), LanguageAccents.added(listOf("š", "#"), 's', listOf("it", "de")))
        // No letter in it: at the end, so the plain long-press stays `#`.
        assertEquals(listOf("#", "ß"), LanguageAccents.added(listOf("#"), 's', listOf("de")))
        // Nothing to add: the same list, so the mutation copies no key.
        val same = listOf("ß", "#")
        assertSame(same, LanguageAccents.added(same, 's', listOf("de")))
    }

    @Test
    fun aUserListKeepsItsCellsAndFollowsTheLanguages() {
        val stored = listOf("#", "$", "ß")
        assertEquals(stored, LanguageAccents.followed(stored, 's', listOf("it", "de"), cap))
        assertEquals(listOf("#", "$"), LanguageAccents.followed(stored, 's', listOf("it"), cap))
        assertEquals(listOf("#", "$", "š"), LanguageAccents.followed(stored, 's', listOf("it", "cs"), cap))
        assertEquals(listOf("#", "$", "ß"), LanguageAccents.followed(listOf("#", "$"), 's', listOf("de"), cap))
    }

    @Test
    fun aUserLetterNoLanguageNamesStays() {
        // The schwa and a typed word are the user's, not a language's.
        val stored = listOf("ə", "è", "ok", ":paste")
        assertEquals(listOf("ə", "ok", ":paste"), LanguageAccents.followed(stored, 'e', listOf("en"), cap))
        assertEquals(listOf("ə", "è", "ok", "é", ":paste"), LanguageAccents.followed(stored, 'e', listOf("it"), cap))
    }

    @Test
    fun aFollowedListStaysUnderTheCap() {
        val full = (1..14).map { it.toString() }
        assertEquals(full, LanguageAccents.followed(full, 's', listOf("de"), cap))
    }

    private fun italian() = KeyboardLayout(
        name = "qwerty_it", locale = "it_IT",
        keys = listOf(
            Key("s", KeyType.CHAR, "s", "s", 0.15f, 0.25f, 0.1f, 0.25f, alternates = listOf("š", "#")),
            Key("a", KeyType.CHAR, "a", "a", 0.05f, 0.25f, 0.1f, 0.25f, alternates = listOf("à", "@")),
        ),
        nativeAccents = true,
    )

    private fun cells(prefs: Map<String, Any>, id: String): List<String> =
        AlphaLayouts.build(italian(), MapPrefs.config(prefs + (Prefs.NUMBER_PRIORITY to false)))
            .keys.first { it.id == id }.alternates

    @Test
    fun enablingGermanPutsItsLettersOnTheItalianBoard() {
        val it = mapOf<String, Any>(Prefs.LANGUAGE to "it", Prefs.ENABLED_LANGUAGES to setOf("it"))
        val de = mapOf<String, Any>(Prefs.LANGUAGE to "it", Prefs.ENABLED_LANGUAGES to setOf("it", "de"))
        assertEquals(listOf("š", "#"), cells(it, "s"))
        assertEquals(listOf("š", "ß", "#"), cells(de, "s"))
        assertEquals(listOf("à", "ä", "@"), cells(de, "a"))
        // A stored list: German off drops its letter, on adds it back.
        val stored = mapOf<String, Any>(Prefs.letterAlternatesKey('s') to "# $ ß")
        assertEquals(listOf("#", "$"), cells(it + stored, "s"))
        assertEquals(listOf("#", "$", "ß"), cells(de + stored, "s"))
    }
}
