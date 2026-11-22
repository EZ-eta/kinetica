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

package com.kinetica.keyboard.layout

import com.kinetica.keyboard.engine.Alphabet

data class KeyboardLayout(
    val name: String,
    val locale: String,
    val keys: List<Key>,
    /**
     * True when the accented letters in this layout's long-press alternates belong to its own
     * language, so trimming them would remove letters the user needs (Italian "è", Spanish "ñ",
     * Polish "ą", Czech "ř").
     *
     * Declared in the JSON, not inferred from [locale]: it describes the alternates an author
     * chose, and the English layout carries eight accented forms on "a" and needs none. Absent
     * means false, right for the plain "qwerty" fallback too. Read only by
     * [LayoutMutations.withoutForeignAlternates]; the swipe engine never sees alternates.
     */
    val nativeAccents: Boolean = false,
    /**
     * True when the letter arrangement is the point of the file and must not be permuted.
     * AZERTY moves M to the home row and runs rows of 10/10/6 against QWERTY's 10/9/7, so a
     * QWERTZ or QZERTY swap on top would leave it neither.
     *
     * Read only by [LayoutMutations.withLetterArrangement]. Absent means false, right for every
     * QWERTY-derived layout.
     */
    val fixedArrangement: Boolean = false,
    /**
     * The script the board's letters are written in (`"script"` in the JSON), Latin when
     * absent. Every key of the layout carries it, so [Key.isLetter] reads the right letters.
     */
    val alphabet: Alphabet = Alphabet.LATIN,
)
