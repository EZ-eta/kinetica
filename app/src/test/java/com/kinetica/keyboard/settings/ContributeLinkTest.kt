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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The Contribute screen opens its page in the browser, so the app needs no network permission. */
class ContributeLinkTest {

    private fun read(rel: String): String {
        val direct = Paths.get(rel.removePrefix("app/"))
        val p: Path = if (Files.exists(direct)) direct else Paths.get(rel)
        assumeTrue("$rel not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }

    @Test
    fun theManifestStillDeclaresNoNetworkPermission() {
        val manifest = read("app/src/main/AndroidManifest.xml")
        assertFalse(manifest.contains("android.permission.INTERNET"))
        assertFalse(manifest.contains("ACCESS_NETWORK_STATE"))
    }

    @Test
    fun theLinkIsTheOneTheReadmePublishes() {
        val readme = Paths.get("../README.md").takeIf { Files.exists(it) } ?: Paths.get("README.md")
        assumeTrue("README.md not found", Files.exists(readme))
        val text = Files.newBufferedReader(readme).use { it.readText() }
        assertTrue(ContributeActivity.KO_FI_URL.startsWith("https://"))
        assertTrue("README does not carry ${ContributeActivity.KO_FI_URL}", text.contains("(${ContributeActivity.KO_FI_URL})"))
    }
}
