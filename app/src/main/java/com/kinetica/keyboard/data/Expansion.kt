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
 * One text expansion: typing [trigger] and firing the expandify action replaces it with
 * [target].
 *
 * Keyed by text, unlike [ChordShortcut], which is keyed by a key: the trigger is whatever is
 * already written at the cursor, so `.`, `vv`, `^^` and `(-.-)'` are ordinary rows.
 *
 * Chaining, loops and shared targets need no code. The lookup reads the cursor each time, so
 * firing twice on `^^` gives `^_^` then `^__^`, and a loop is a row pointing back.
 *
 * [position] orders several targets for one trigger. Only position 0 is read today; the
 * column exists so the picker can arrive without a second migration.
 */
@Entity(tableName = "expansions", primaryKeys = ["triggerText", "position"])
data class Expansion(
    /**
     * Stored as `triggerText`: TRIGGER is a SQL keyword and the migration's CREATE TABLE is
     * hand-written for SQLite 3.18 on minSdk 26.
     */
    @ColumnInfo(name = "triggerText") val trigger: String,
    val position: Int,
    val target: String,
)
