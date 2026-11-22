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

package com.kinetica.keyboard.ime

/**
 * The smallest rewrite of the word in hand: the letters it shares in front are left alone.
 *
 * A merge that adds the other thumb's letter used to delete the whole word and write it again,
 * 10 ms after the swipe's own write. A WebView rich editor (ProseMirror) answers a delete with
 * a 50 ms check and, finding no change it could read, deletes one more character: `the` became
 * `th` 8 times in 8. Growing the word is now a plain insert, with no delete to answer.
 */
internal object TentativeEdit {

    /** Delete [drop] characters before the cursor, then write [add]. */
    data class Edit(val drop: Int, val add: String) {
        val writesNothing: Boolean get() = drop == 0 && add.isEmpty()
    }

    /** The rewrite of [old], just before the cursor, into [new]. */
    fun between(old: String, new: String): Edit {
        var keep = 0
        val most = minOf(old.length, new.length)
        while (keep < most && old[keep] == new[keep]) keep++
        // Never cut between the halves of an emoji: the editor would hold a lone surrogate.
        if (keep > 0 && Character.isHighSurrogate(old[keep - 1])) keep--
        return Edit(old.length - keep, new.substring(keep))
    }
}
