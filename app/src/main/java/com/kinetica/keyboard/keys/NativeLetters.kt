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

package com.kinetica.keyboard.keys

/**
 * The accented letters each language writes, for the setting that hides accented letters on
 * long-press.
 *
 * That setting trims the active board, and only an English board has accents that are not its
 * own. Trimmed to the active board alone, a Polish writer who keeps English active would lose ą
 * and ł although Polish is enabled, so the letters of every enabled language stay. A curated
 * list, not the languages' own boards: those carry foreign accents too, Polish's `a` offers `à`.
 */
object NativeLetters {

    /** Lowercase; each letter is in its language's bundled wordlist (NativeLettersTest). */
    private val LETTERS: Map<String, String> = mapOf(
        "it" to "àèéìòù",
        "es" to "áéíóúüñ",
        "pl" to "ąćęłńóśźż",
        "cs" to "áčďéěíňóřšťúůýž",
        "nl" to "éèëïöü",
        "de" to "äöüß",
        "fr" to "àâçéèêëîïôœùûü",
        "no" to "æøåé",
        "ro" to "ăâîșț",
    )

    /** The letters [lang] writes; empty for English and for a language on its own script. */
    fun of(lang: String): String = LETTERS[lang].orEmpty()

    /** Every language with letters of its own. */
    val languages: Set<String> get() = LETTERS.keys


    /** Every letter the enabled [languages] write, as alternates spell them. */
    fun keptBy(languages: List<String>): Set<String> =
        languages.flatMap { lang -> of(lang).map { it.toString() } }.toSet()
}
