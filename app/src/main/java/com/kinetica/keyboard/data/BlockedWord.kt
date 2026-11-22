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

package com.kinetica.keyboard.data

import androidx.room.Entity

/**
 * One word the user never wants offered, for one language.
 *
 * Keyed on (word, lang) like [UserWord]: the bundled lists overlap, so blocking a stray
 * spelling in English must not remove a real Italian word spelled the same.
 *
 * Sliding a suggestion down only lowers the personal count, clamped at zero, and leaves the
 * corpus frequency in place. A blocked word is dropped while the trie is built, so it cannot
 * be decoded, completed or suggested at all.
 */
@Entity(tableName = "blocked_words", primaryKeys = ["word", "lang"])
data class BlockedWord(
    val word: String,
    val lang: String,
    val addedAt: Long,
)
