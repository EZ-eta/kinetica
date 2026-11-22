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

package com.kinetica.keyboard.ime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The clipboard history's Android half, read off disk: the listener, the private-field gates, the
 * pane's lifecycle and the trace. None of it runs on the JVM; a fail-first check needs a clean
 * test task.
 */
class ClipSourceTest {

    private val keeper by lazy { ImeSources.declaring("internal class ClipKeeper(").text }
    private val ime by lazy { ImeSources.declaring("class KineticaIME").text }
    private val vault by lazy { read("app/src/main/java/com/kinetica/keyboard/data/ClipVault.kt") }

    private fun body(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("$signature not found", start >= 0)
        return src.substring(start, src.indexOf("\n    }\n", start))
    }

    @Test
    fun theListenerIsAddedOnlyWithTheSwitchOnAndRemovedOnDestroy() {
        val configure = body(keeper, "fun configure(why: String)")
        assertTrue(configure.contains("val on = ime.config.clipHistory"))
        val add = configure.indexOf("addPrimaryClipChangedListener(listener)")
        assertTrue(add > configure.indexOf("if (on) {"))
        assertTrue(add < configure.indexOf("} else {"))
        assertTrue(configure.substringAfter("} else {").contains("stop()"))
        assertTrue(body(keeper, "fun stop()").contains("removePrimaryClipChangedListener(listener)"))
        assertEquals(1, Regex("addPrimaryClipChangedListener").findAll(ImeSources.all().joinToString { it.text }).count())
        assertTrue(body(ime, "override fun onDestroy()").contains("clips.stop()"))
        assertTrue(ime.contains("config.clipHistory != previous.clipHistory || config.clipShort != previous.clipShort"))
        assertTrue(body(ime, "override fun onCreate()").contains("clips.configure(\"create\")"))
    }

    @Test
    fun aCopyPassesTheFieldAndTheAppsMarkBeforeItIsKept() {
        val changed = body(keeper, "private fun onClipChanged()")
        assertTrue(changed.contains("getBoolean(ClipIntake.EXTRA_IS_SENSITIVE, false)"))
        assertTrue(changed.contains("teachesNothing = ime.editorState.teachesNothing"))
        assertTrue(changed.contains("enabled = ime.config.clipHistory"))
        val skip = changed.indexOf("ClipIntake.skipReason(")
        val keep = changed.indexOf("ClipVault.keep(")
        assertTrue(skip in 0 until keep)
        assertTrue(changed.substring(skip, keep).contains("if (skip != null || text == null)"))
    }

    @Test
    fun noUriIsEverRead() {
        for (src in ImeSources.all() + listOf(ImeSources.Source("ClipVault.kt", vault))) {
            assertFalse(src.name, src.text.contains("coerceToText"))
            assertFalse(src.name, src.text.contains(".uri"))
        }
    }

    @Test
    fun noPaneAndNoPasteInAPasswordField() {
        val open = body(keeper, "fun open(src: String)")
        val refuse = open.indexOf("if (ime.editorState.privateMode)")
        assertTrue(refuse >= 0)
        assertTrue(refuse < open.indexOf("container.showClipPane("))
        assertTrue(open.indexOf("if (!ime.config.clipHistory)") < open.indexOf("container.showClipPane("))
        assertTrue(body(keeper, "private fun paste(clip: Clip)").contains("ime.editorState.privateMode ||"))
    }

    @Test
    fun theTraceNeverHoldsAClip() {
        for ((name, src) in listOf("ClipKeeper" to keeper, "ClipVault" to vault)) {
            val lines = Regex("DecodeTrace\\.log \\{ \"[^\"]*\" \\}").findAll(src).map { it.value }.toList()
            assertTrue(name, lines.isNotEmpty())
            for (line in lines) {
                val named = line.replace("\${text.length}", "")
                assertFalse("$name: $line", Regex("\\$\\{?text\\b").containsMatchIn(named))
                assertFalse("$name: $line", named.contains("clip.text"))
                assertFalse("$name: $line", named.contains("\$clip"))
            }
        }
    }

    @Test
    fun thePaneGivesTheBarBack() {
        assertTrue(body(ime, "override fun onStartInputView(").contains("clips.close(\"field\")"))
        assertTrue(body(ime, "override fun onStartInput(").contains("clips.configure(\"start\")"))
        assertTrue(body(ime, "override fun onCreateInputView()").contains("clips.reset()"))
        assertTrue(ime.contains("            clips.close(\"key\")"))
        assertTrue(body(keeper, "fun open(src: String)").contains("ime.emojiSearch.close(\"clips\")"))
        assertTrue(ImeSources.declaring("internal class EmojiSearchMode(").text.contains("ime.clips.close(\"search\")"))
    }

    @Test
    fun aClipPastesThroughTheKeyboardsPaste() {
        val paste = body(keeper, "private fun paste(clip: Clip)")
        assertTrue(paste.contains("ime.commands.pasteText(text)"))
        assertTrue(paste.contains("ClipVault.live(ime, now).none { it.id == clip.id }"))
        val pasteText = body(ImeSources.declaring("internal class EditorCommands(").text, "fun pasteText(text: String)")
        assertTrue(pasteText.contains("ime.commit.finalizePendingWord()"))
        assertTrue(pasteText.contains("ime.ich.commitText(text)"))
    }

    private fun read(rel: String): String {
        val direct = Paths.get(rel.removePrefix("app/"))
        val p: Path = if (Files.exists(direct)) direct else Paths.get(rel)
        assumeTrue("$rel not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }
}
