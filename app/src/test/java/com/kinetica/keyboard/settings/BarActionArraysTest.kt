package com.kinetica.keyboard.settings

import com.kinetica.keyboard.keys.ActionRow
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The shortcut chooser's list, read off the shipped XML.
 *
 * The values are [com.kinetica.keyboard.keys.EditorAction] names typed by hand into
 * `arrays.xml`, so nothing in the compiler connects them to the enum: a new action would
 * work everywhere except in the one screen where a user turns it on, and a typo would be a
 * row that silently selects nothing. That is the gap this closes.
 *
 * Read straight off disk like [PreferenceTreeTest], so Gradle does not treat it as an
 * input: **`--rerun-tasks` is what makes a fail-first check here actually run.**
 */
class BarActionArraysTest {

    private fun arraysXml(): String {
        val direct = Paths.get("src/main/res/values/arrays.xml")
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/src/main/res/values/arrays.xml")
        assumeTrue("arrays.xml not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }

    private fun items(xml: String, name: String): List<String> {
        val block = Regex("""<string-array name="$name">(.*?)</string-array>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1) ?: return emptyList()
        return Regex("""<item>([^<]*)</item>""").findAll(block).map { it.groupValues[1] }.toList()
    }

    @Test
    fun theChooserOffersEveryActionInTheRowsOwnOrder() {
        val xml = arraysXml()
        assertEquals(ActionRow.ALL.map { it.name }, items(xml, "bar_action_values"))
    }

    @Test
    fun everyValueHasALabel() {
        val xml = arraysXml()
        assertEquals(
            items(xml, "bar_action_values").size,
            items(xml, "bar_action_entries").size,
        )
    }

    @Test
    fun bothChoosersOfferTheCheckedList() {
        // The checks above hold only for the arrays they read. A chooser pointed at a
        // different array would be one nothing here looks at.
        val direct = Paths.get("src/main/res/xml/keyboard_prefs.xml")
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/src/main/res/xml/keyboard_prefs.xml")
        assumeTrue("keyboard_prefs.xml not found", Files.exists(p))
        val xml = Files.newBufferedReader(p).use { it.readText() }
        for (key in listOf("pref_bar_actions", "pref_menu_actions")) {
            val row = Regex("""<MultiSelectListPreference[^>]*android:key="$key"[^>]*/>""")
                .find(xml)?.value
            requireNotNull(row) { "$key has no chooser row" }
            assertEquals(key, true, row.contains("@array/bar_action_entries"))
            assertEquals(key, true, row.contains("@array/bar_action_values"))
            assertEquals(key, true, row.contains("@array/bar_action_defaults"))
        }
    }

    @Test
    fun theXmlDefaultMatchesTheCodeDefault() {
        // Two sources of truth for what a new install shows, and they are read on
        // different paths: the XML one when the preference screen first writes itself,
        // the code one when KeyboardConfig reads an unset preference.
        assertEquals(ActionRow.DEFAULT, items(arraysXml(), "bar_action_defaults").toSet())
    }
}
