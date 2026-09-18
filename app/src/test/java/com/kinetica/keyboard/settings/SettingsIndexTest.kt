package com.kinetica.keyboard.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding a setting by typing what you call it.
 *
 * The rows come from the inflated preference tree, which no JVM test can build, so the
 * fixtures below are hand-written in that shape. What is decided here is which rows match
 * and in what order, which is the part that can be got wrong quietly.
 */
class SettingsIndexTest {

    private fun entry(
        key: String,
        title: String,
        summary: String = "",
        screen: String? = "pref_group_look",
        terms: List<String> = emptyList(),
    ) = SettingsIndex.Entry(key, title, summary, screen, "Look and feel", terms)

    private val tree = listOf(
        entry("pref_theme_mode", "Theme", "Light, dark or the system setting", terms = listOf("colour")),
        entry("pref_zen_mode", "Zen mode", "Hide everything but the letters", terms = listOf("minimal")),
        entry("pref_vibration", "Vibration", "Buzz on every key"),
        entry("pref_keyboard_height_pct", "Keyboard height", "How tall the keys are", terms = listOf("size")),
        entry("pref_autospace", "Automatic space", "A space after a finished word"),
    )

    @Test
    fun anEmptyQueryIsNothingRatherThanEverything() {
        // The one deliberate difference from PersonalWordRows.filtered, where blank is the
        // whole list. A search field showing all sixty rows is the settings screen with an
        // extra step, and the caller reads the empty result as "close the overlay".
        assertEquals(emptyList<SettingsIndex.Entry>(), SettingsIndex.match(tree, ""))
        assertEquals(emptyList<SettingsIndex.Entry>(), SettingsIndex.match(tree, "   "))
    }

    @Test
    fun aTitleMatchOutranksASummaryMatch() {
        // "key" is in Keyboard height's title and in Vibration's summary. The row named
        // after the word has to come first, or searching for a setting by its own name
        // buries it under rows that mention it in passing.
        val hits = SettingsIndex.match(tree, "key")
        assertEquals(listOf("pref_keyboard_height_pct", "pref_vibration"), hits.map { it.key })
    }

    @Test
    fun aSummaryMatchOutranksASynonymMatch() {
        // "hide" is in Zen mode's summary; "minimal" is only its synonym. Give another row
        // the same synonym and the summary hit still leads.
        val rows = tree + entry("pref_layout_mode", "Arrangement", "", terms = listOf("hide"))
        val hits = SettingsIndex.match(rows, "hide")
        assertEquals(listOf("pref_zen_mode", "pref_layout_mode"), hits.map { it.key })
    }

    @Test
    fun aSynonymReachesARowThatSaysTheWordNowhere() {
        // The whole point of the synonym half of R82. Nothing in "Keyboard height" or its
        // summary contains "size", and "size" is what people type.
        val hits = SettingsIndex.match(tree, "size")
        assertEquals(listOf("pref_keyboard_height_pct"), hits.map { it.key })
    }

    @Test
    fun matchingIsASubstringAndNotAPrefix() {
        // Half-remembered is the normal case: nobody types "automatic" to find the
        // autospace, they type "space".
        assertEquals(listOf("pref_autospace"), SettingsIndex.match(tree, "space").map { it.key })
        assertTrue(SettingsIndex.match(tree, "ibrat").any { it.key == "pref_vibration" })
    }

    @Test
    fun accentsAndCaseAreFoldedTheWayTheDecoderFoldsThem() {
        // Same fold as the trie, so the settings screen agrees with the keyboard about what
        // a letter is. It matters the moment a title is translated.
        val rows = listOf(entry("pref_x", "Prévisualisation", "Aperçu du thème"))
        assertEquals(1, SettingsIndex.match(rows, "previsualisation").size)
        assertEquals(1, SettingsIndex.match(rows, "APERCU").size)
    }

    @Test
    fun rowsOfEqualRankKeepTheOrderSettingsPutsThemIn() {
        // Tree order, not alphabetical and not by match position. Two rows whose titles both
        // contain the query appear in the order the user would meet them.
        val rows = listOf(
            entry("a", "Space bar"),
            entry("b", "Spaceless space"),
            entry("c", "Spacing"),
        )
        assertEquals(listOf("a", "b", "c"), SettingsIndex.match(rows, "spac").map { it.key })
    }

    @Test
    fun nothingMatchesWhenNothingMatches() {
        assertEquals(emptyList<SettingsIndex.Entry>(), SettingsIndex.match(tree, "xylophone"))
    }
}
