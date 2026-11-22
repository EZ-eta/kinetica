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
 * One learned word pair for one language: how often [next] followed [prev].
 *
 * Keyed per language like [UserWord], so an English pair never boosts an Italian
 * continuation. Both words are lowercased and folded as the composer's context is, since the
 * decoder looks the previous word up that way.
 *
 * Written only when the phrase setting is on: a pair is a fragment of a sentence, more
 * revealing than a single-word count, so it is opt-in and left out of the personal-dictionary
 * export.
 */
@Entity(tableName = "user_bigrams", primaryKeys = ["prev", "next", "lang"])
data class UserBigram(
    val prev: String,
    val next: String,
    val lang: String,
    val count: Int,
    val updatedAt: Long,
)
