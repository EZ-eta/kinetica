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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The Learn Kinetica tips. A tip that names a setting opens it, so its target must be
 * where the tree puts it: a tip pointing at a moved setting would open the wrong screen.
 */
class LearnTipsTest {

    private val xml = PrefsXml.read()
    private val where = PrefsXml.screenOf(xml)

    /** Each tip's key and whether its row is selectable. */
    private fun tips(): Map<String, Boolean> =
        Regex("""<Preference\s+android:key="(tip_[a-z_]+)"(.*?)/>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).associate { it.groupValues[1] to !it.groupValues[2].contains("""android:selectable="false"""") }

    @Test
    fun everyTargetIsWhereTheTreePutsIt() {
        for ((tip, target) in LearnTips.TARGETS) {
            assertTrue("$tip is not in the tree", where.containsKey(tip))
            assertTrue("$tip points at ${target.key}, which is not in the tree", where.containsKey(target.key))
            assertEquals("$tip's target ${target.key} is on another screen", where[target.key], target.screenKey)
        }
    }

    @Test
    fun aTipIsSelectableExactlyWhenItLeadsSomewhere() {
        val tips = tips()
        assertTrue("the tips must be found", tips.size >= 30)
        for ((tip, selectable) in tips) {
            assertEquals("$tip selectable=$selectable", selectable, tip in LearnTips.TARGETS)
        }
    }

    private fun strings(): Map<String, String> {
        val direct = Paths.get("src/main/res/values/strings.xml")
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/src/main/res/values/strings.xml")
        assumeTrue("strings.xml not found", Files.exists(p))
        val text = Files.newBufferedReader(p).use { it.readText() }
        return Regex("""<string name="(tip_[a-z_]+)"[^>]*>([^<]*)</string>""").findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun aTipIsShort() {
        val s = strings()
        for (tip in tips().keys) {
            val title = s.getValue("${tip}_title")
            val summary = s.getValue("${tip}_summary")
            assertTrue("$tip title: $title", title.length <= 40)
            assertTrue("$tip summary: $summary", summary.length <= 130)
            assertFalse("$tip has an em dash", (title + summary).contains('—'))
        }
    }
}
