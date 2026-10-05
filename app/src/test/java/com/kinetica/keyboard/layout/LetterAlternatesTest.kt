package com.kinetica.keyboard.layout

import com.kinetica.keyboard.settings.KeyboardConfig
import com.kinetica.keyboard.settings.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A letter's own long-press list (#8): `y` holding `6 ^` instead of `ý`, and `6` in its corner.
 * Hand-built layouts, because the JVM runtime stubs org.json.
 */
class LetterAlternatesTest {

    private fun layout(): KeyboardLayout = KeyboardLayout(
        name = "qwerty", locale = "en_US",
        keys = listOf(
            Key("y", KeyType.CHAR, "y", "y", 0.5f, 0f, 0.1f, 0.25f, alternates = listOf("6", "ý", "ÿ")),
            Key("z", KeyType.CHAR, "z", "z", 0.15f, 0.5f, 0.1f, 0.25f, hint = "ž", alternates = listOf("ž", "ź", "ż")),
            Key("period", KeyType.CHAR, ".", ".", 0.75f, 0.75f, 0.1f, 0.25f, alternates = listOf("…")),
        ),
    )

    private fun key(l: KeyboardLayout, output: String): Key = l.keys.first { it.output == output }

    @Test
    fun aLetterHoldsTheListTheUserWrote() {
        val out = LayoutMutations.withLetterAlternates(layout(), mapOf('y' to listOf("6", "^")))
        val y = key(out, "y")
        assertEquals(listOf("6", "^"), y.alternates)
        // The first entry is the corner character and the plain long-press.
        assertEquals("6", y.hintChar)
    }

    @Test
    fun aLayoutHintGivesWayToTheUsersFirstEntry() {
        val out = LayoutMutations.withLetterAlternates(layout(), mapOf('z' to listOf("ż", "7")))
        val z = key(out, "z")
        assertNull(z.hint)
        assertEquals("ż", z.hintChar)
    }

    @Test
    fun otherKeysAndPunctuationAreLeftAlone() {
        val out = LayoutMutations.withLetterAlternates(layout(), mapOf('y' to listOf("^")))
        assertEquals(key(layout(), "z"), key(out, "z"))
        assertEquals(key(layout(), "."), key(out, "."))
        val same = layout()
        assertSame(same, LayoutMutations.withLetterAlternates(same, emptyMap()))
    }

    @Test
    fun theListIsKeyedByTheLetterTypedNotTheKeyId() {
        // Bundled layouts name a key after its letter, but nothing requires it: a key id is a
        // position as far as a layout file is concerned. The user wrote a list for a letter.
        val positional = KeyboardLayout(
            name = "t", locale = "en_US",
            keys = listOf(Key("k6", KeyType.CHAR, "y", "y", 0.5f, 0f, 0.1f, 0.25f, alternates = listOf("6"))),
        )
        val out = LayoutMutations.withLetterAlternates(positional, mapOf('y' to listOf("^")))
        assertEquals(listOf("^"), out.keys.single().alternates)
    }

    @Test
    fun theSettingIsReadPerLetterAndBlankMeansBuiltIn() {
        val stored = mapOf(
            Prefs.letterAlternatesKey('y') to "6  ^ ",
            Prefs.letterAlternatesKey('o') to "   ",
            Prefs.letterAlternatesKey('a') to (1..20).joinToString(" "),
        )
        val lists = KeyboardConfig.letterAlternatesFrom { stored[it] }
        assertEquals(listOf("6", "^"), lists['y'])
        assertTrue("a blank list is no list", 'o' !in lists)
        assertEquals(KeyboardConfig.MAX_LETTER_ALTERNATES, lists['a']?.size)
        assertEquals(setOf('y', 'a'), lists.keys)
    }

    @Test
    fun aListIsReadForAnyBoardsLetter() {
        // Lists were once read for a-z alone, so a Cyrillic or Hebrew list was never used.
        val stored = mapOf(
            Prefs.letterAlternatesKey('й') to "ї 1",
            Prefs.letterAlternatesKey('ש') to "!",
            Prefs.letterAlternatesKey('ґ') to "x",
        )
        val lists = KeyboardConfig.letterAlternatesFrom { stored[it] }
        assertEquals(listOf("ї", "1"), lists['й'])
        assertEquals(listOf("!"), lists['ש'])
        // `ґ` is no key of its own: it sits on г's list, so it has none.
        assertTrue('ґ' !in lists)
    }

    @Test
    fun aCyrillicKeyTakesItsList() {
        val board = KeyboardLayout(
            name = "jcuken_uk", locale = "uk_UA",
            keys = listOf(Key("й", KeyType.CHAR, "й", "й", 0f, 0f, 0.1f, 0.25f, alternates = listOf("1"), alphabet = com.kinetica.keyboard.engine.Alphabet.UKRAINIAN)),
        )
        val out = LayoutMutations.withLetterAlternates(board, mapOf('й' to listOf("ї", "1")))
        assertEquals(listOf("ї", "1"), out.keys.single().alternates)
    }
}
