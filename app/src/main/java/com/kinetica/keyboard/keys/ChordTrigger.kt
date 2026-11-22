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
 * The key held to make the next tap a chord: `?123`, or a symbol page's page key, and the
 * spacebar, which sits in the middle of every page. Chosen per chord, so `?123`+`l` and
 * space+`l` are two chords.
 */
enum class ChordTrigger { MODE, SPACE }

/**
 * A chord's identity: the held key and the tapped one.
 *
 * Stored as the tapped key alone for `?123`, which is every chord an earlier build wrote, and as
 * `space:<key>` for the spacebar. An earlier build reads only one-character keys, so a newer row
 * is no chord there instead of a wrong one, and the table needs no migration.
 */
data class ChordKey(val trigger: ChordTrigger, val key: Char) {

    fun encode(): String = when (trigger) {
        ChordTrigger.MODE -> key.toString()
        ChordTrigger.SPACE -> SPACE_PREFIX + key
    }

    companion object {
        const val SPACE_PREFIX = "space:"

        /** The chord a stored key names, or null for one this build cannot read. */
        fun decode(stored: String): ChordKey? = when {
            stored.length == 1 -> ChordKey(ChordTrigger.MODE, stored[0].lowercaseChar())
            stored.length == SPACE_PREFIX.length + 1 && stored.startsWith(SPACE_PREFIX) ->
                ChordKey(ChordTrigger.SPACE, stored.last().lowercaseChar())
            else -> null
        }
    }
}
