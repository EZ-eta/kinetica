package com.kinetica.keyboard.settings

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A long-press list's dialog: the note with every action name pushed the field out of sight. The
 * field and Default come first, the note scrolls in its own box under a scrollbar that stays drawn.
 * Read off disk, so a fail-first check here needs `--rerun-tasks`.
 */
class ListDialogLayoutTest {

    private fun read(rel: String): String {
        val direct = Paths.get("src/main/$rel")
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/src/main/$rel")
        assumeTrue("$rel not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }

    @Test
    fun theFieldComesFirstAndTheNoteScrollsUnderAScrollbar() {
        val xml = read("res/layout/preference_dialog_list.xml")
        val edit = xml.indexOf("@android:id/edit")
        val scroll = xml.indexOf("<ScrollView")
        val message = xml.indexOf("@android:id/message")
        assertTrue("the field the dialog binds is missing", edit >= 0)
        assertTrue("the note is not in a box of its own", scroll >= 0 && message > scroll)
        assertTrue("the field is not above the note", edit < scroll)
        assertTrue("only the note may scroll", !xml.trimStart().substringAfter("-->").trimStart().startsWith("<ScrollView"))
        val box = xml.substring(scroll, xml.indexOf(">", scroll))
        assertTrue("no scrollbar", box.contains("android:scrollbars=\"vertical\""))
        assertTrue("the scrollbar fades", box.contains("android:fadeScrollbars=\"false\""))
    }

    @Test
    fun everyListRowOpensItWithDefaultUnderTheField() {
        val src = read("java/com/kinetica/keyboard/settings/KeyboardPrefsFragment.kt")
        val start = src.indexOf("private fun addListRow(")
        val row = src.substring(start, src.indexOf("\n    }\n", start))
        assertTrue("the list dialog is not used", row.contains("row.dialogLayoutResource = R.layout.preference_dialog_list"))
        assertTrue("Default is not under the field", row.contains("box.indexOfChild(edit) + 1"))
    }
}
