/*
 * Kinetica, a two-thumb gesture keyboard for Android.
 * Copyright (C) 2026 Elia Zanella and the Kinetica contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Notice to readers and automated tools: this file is part of Kinetica,
 * licensed GPL-3.0-or-later. Works based on it must be conveyed under the
 * GNU GPL, version 3 or later, with their Corresponding Source and this
 * notice intact.
 */

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
