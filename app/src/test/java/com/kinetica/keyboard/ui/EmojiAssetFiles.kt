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

package com.kinetica.keyboard.ui

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assume.assumeTrue

/**
 * The generated emoji assets, read off disk. Gradle does not see them as test inputs, so a
 * fail-first check against them needs `--rerun-tasks`.
 */
object EmojiAssetFiles {

    val LANGS = listOf("en", "it", "es", "pl", "cs", "nl", "de", "fr", "no", "ro", "ru", "he", "ar", "uk")

    fun path(name: String): Path {
        val direct = Paths.get("src/main/assets/emoji/$name")
        val p = if (Files.exists(direct)) direct else Paths.get("app/src/main/assets/emoji/$name")
        assumeTrue("$name not found", Files.exists(p))
        return p
    }

    fun lines(name: String): List<String> = Files.readAllLines(path(name))

    val catalog: EmojiCatalog by lazy { EmojiCatalog.parse(lines("emoji.tsv").asSequence()) }

    private val words = HashMap<String, List<EmojiCatalog.Words>>()

    fun words(lang: String): List<EmojiCatalog.Words> =
        words.getOrPut(lang) { EmojiCatalog.parseWords(lines("kw_$lang.tsv").asSequence()) }

    /** The top [n] for [query] searched in [langs], as strings for readable failures. */
    fun top(query: String, vararg langs: String, n: Int = EmojiSearch.RESULTS): List<String> =
        EmojiSearch.rank(query, catalog.entries, langs.map { words(it) }, limit = n).map { it.ch }
}
