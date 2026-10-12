package com.kinetica.keyboard.ime

import com.kinetica.keyboard.keys.ActionRow
import com.kinetica.keyboard.keys.EditorAction
import com.kinetica.keyboard.layout.Key
import com.kinetica.keyboard.layout.KeyType
import com.kinetica.keyboard.layout.KeyboardLayout
import com.kinetica.keyboard.settings.MapPrefs
import com.kinetica.keyboard.settings.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A shortcut named in any long-press list a user writes becomes the cell that runs it.
 *
 * Every list maps its shortcuts, not only the letters': otherwise a comma list holding `:paste`
 * types ":paste", and the period's `⎘` types the symbol.
 */
class ListActionsTest {

    private fun board() = KeyboardLayout(
        name = "qwerty", locale = "en_US",
        keys = listOf(
            Key("a", KeyType.CHAR, "a", "a", 0.05f, 0.25f, 0.1f, 0.25f, alternates = listOf("à", "@")),
            Key("comma", KeyType.CHAR, ",", ",", 0.15f, 0.75f, 0.1f, 0.25f, alternates = listOf("_")),
            Key("space", KeyType.SPACE, "", " ", 0.25f, 0.75f, 0.5f, 0.25f),
            Key("period", KeyType.CHAR, ".", ".", 0.75f, 0.75f, 0.1f, 0.25f, alternates = listOf("…")),
        ),
    )

    private fun cells(prefs: Map<String, Any>, id: String): List<String> =
        AlphaLayouts.build(board(), MapPrefs.config(prefs)).keys.first { it.id == id }.alternates

    @Test
    fun theCommaAndPeriodListsRunTheirShortcuts() {
        val prefs = mapOf<String, Any>(
            Prefs.COMMA_ALTERNATES to ":paste _",
            Prefs.PERIOD_ALTERNATES to "action:copy :select_all …",
        )
        val comma = cells(prefs, "comma")
        val period = cells(prefs, "period")
        assertEquals(EditorAction.PASTE, ActionRow.actionForGlyph(comma[0]))
        assertEquals(
            setOf(EditorAction.COPY, EditorAction.SELECT_ALL),
            period.mapNotNull { ActionRow.actionForGlyph(it) }.toSet(),
        )
        assertTrue("…" in period)
    }

    @Test
    fun hidingAccentsKeepsAShortcutThatReadsLikeAWord() {
        // `ALL`, `wpm` and `TAP` start with a letter; hidden as accents, they would leave the comma list.
        val prefs = mapOf<String, Any>(
            Prefs.COMMA_ALTERNATES to ":select_all _",
            Prefs.PLAIN_LETTER_ALTERNATES to true,
            Prefs.ENABLED_LANGUAGES to setOf("en"),
        )
        val comma = cells(prefs, "comma")
        assertEquals(EditorAction.SELECT_ALL, ActionRow.actionForGlyph(comma[0]))
        assertEquals("_", comma[1])
    }

    @Test
    fun aLetterListTakesTheNameUsersTryForTheLanguageSwitch() {
        val a = cells(mapOf(Prefs.letterAlternatesKey('a') to ":switch_language @"), "a")
        assertEquals(EditorAction.NEXT_LANGUAGE, ActionRow.actionForGlyph(a[0]))
    }

    @Test
    fun aNumbersRowDigitTakesItsOwnList() {
        val prefs = mapOf<String, Any>(
            Prefs.NUMBER_ROW to true,
            Prefs.letterAlternatesKey('1') to ":paste ¹ ½",
        )
        val one = AlphaLayouts.build(board(), MapPrefs.config(prefs)).keys.first { it.id == "num_1" }
        assertEquals(EditorAction.PASTE, ActionRow.actionForGlyph(one.alternates[0]))
        assertEquals(listOf("¹", "½"), one.alternates.drop(1))
        // Without a list a digit holds nothing, as before.
        val two = AlphaLayouts.build(board(), MapPrefs.config(prefs)).keys.first { it.id == "num_2" }
        assertTrue(two.alternates.isEmpty())
    }
}
