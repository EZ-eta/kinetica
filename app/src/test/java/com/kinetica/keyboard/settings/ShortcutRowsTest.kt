package com.kinetica.keyboard.settings

import com.kinetica.keyboard.keys.ActionRow
import com.kinetica.keyboard.keys.EditorAction
import com.kinetica.keyboard.keys.ShortcutSet
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The two shortcut sets and the screen that edits them.
 *
 * The screen lists [ActionRow.ALL] itself, so a new action needs no array; this pins that both
 * preference rows open it for their own set, and the list arithmetic behind the checkboxes and
 * arrows. The XML is read off disk like [PreferenceTreeTest], so a fail-first here needs
 * `--rerun-tasks`.
 */
class ShortcutRowsTest {

    private fun prefsXml(): String {
        val direct = Paths.get("src/main/res/xml/keyboard_prefs.xml")
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/src/main/res/xml/keyboard_prefs.xml")
        assumeTrue("keyboard_prefs.xml not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }

    @Test
    fun bothRowsOpenTheScreenForTheirOwnSet() {
        val xml = prefsXml()
        for ((key, set) in listOf("pref_bar_actions" to "bar", "pref_menu_actions" to "menu")) {
            val row = Regex("""<Preference\s[^>]*android:key="$key"[^>]*>(.*?)</Preference>""", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1)
            requireNotNull(row) { "$key is not a row that opens a screen" }
            assertTrue(key, row.contains("ShortcutSetActivity"))
            assertTrue(key, row.contains("""android:value="$set""""))
        }
        assertEquals(ShortcutSetActivity.SET_BAR, "bar")
        assertEquals(ShortcutSetActivity.SET_MENU, "menu")
    }

    @Test
    fun theChosenComeFirstInTheirOrderAndTheRestAfter() {
        val s = ShortcutSet.from(setOf("PASTE", "SETTINGS"), "PASTE,SETTINGS")
        val rows = s.rows()
        assertEquals(EditorAction.PASTE to true, rows[0])
        assertEquals(EditorAction.SETTINGS to true, rows[1])
        assertEquals(ActionRow.ALL.size, rows.size)
        assertTrue(rows.drop(2).none { it.second })
        assertEquals(ActionRow.ALL.filter { it != EditorAction.PASTE && it != EditorAction.SETTINGS }, rows.drop(2).map { it.first })
    }

    @Test
    fun tickingAppendsAndUntickingRemoves() {
        val s = ShortcutSet.from(setOf("SETTINGS"), null)
        s.toggle(EditorAction.TIME)
        assertEquals("SETTINGS,TIME", s.order())
        s.toggle(EditorAction.SETTINGS)
        assertEquals(setOf("TIME"), s.selection())
        assertEquals("TIME", s.order())
    }

    @Test
    fun theArrowsMoveOnlyWithinTheChosen() {
        val s = ShortcutSet.from(setOf("SETTINGS", "UNDO", "PASTE"), null)
        assertEquals("SETTINGS,UNDO,PASTE", s.order())
        s.move(EditorAction.PASTE, -1)
        assertEquals("SETTINGS,PASTE,UNDO", s.order())
        assertFalse(s.canMove(EditorAction.SETTINGS, -1))
        assertFalse(s.canMove(EditorAction.UNDO, +1))
        assertFalse(s.canMove(EditorAction.TIME, -1))
        s.move(EditorAction.SETTINGS, -1)
        s.move(EditorAction.TIME, +1)
        assertEquals("SETTINGS,PASTE,UNDO", s.order())
    }

    @Test
    fun whatTheScreenWritesIsWhatTheKeyboardReads() {
        val s = ShortcutSet.from(ActionRow.DEFAULT, null)
        s.move(EditorAction.TOGGLE_AUTOSPACE, -1)
        assertEquals(
            ActionRow.ordered(s.selection(), ActionRow.decodeOrder(s.order())),
            s.rows().filter { it.second }.map { it.first },
        )
    }
}
