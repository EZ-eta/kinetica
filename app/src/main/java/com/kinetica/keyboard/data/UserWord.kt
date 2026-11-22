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

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * One learned word for one language. Keyed on (word, lang): the same spelling
 * learned in two languages carries two independent counts, so English commits
 * can never inflate or evict Italian personal weights (and vice versa).
 *
 * [word] is always lowercase, the key every count, block and pair is read by. [display] is the
 * capital the user writes it with, `Dennis` or `GitHub`, and [caseVotes] how settled that is:
 * one up for each mid-sentence commit with the capital, one down for each without it. Both
 * added in v7; an older row has no capital and no votes.
 */
@Entity(tableName = "user_words", primaryKeys = ["word", "lang"])
data class UserWord(
    val word: String,
    val lang: String,
    val frequency: Int,
    val updatedAt: Long,
    val display: String? = null,
    @ColumnInfo(defaultValue = "0") val caseVotes: Int = 0,
)
