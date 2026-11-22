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

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import com.kinetica.keyboard.settings.BackdropStore
import java.util.concurrent.Executors

/**
 * Holds the background image the keyboard draws, read again only when its stamp changes: a
 * screen-sized decode is tens of ms, too slow for every settings change or input view.
 */
internal class BackdropLoader(private val context: Context, private val main: Handler) {

    // Its own thread: the decode thread serves typing and the db thread Room.
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "kinetica-backdrop") }

    private var stamp: String? = null

    /** The image for the current stamp, or null for none, a missing file or a read in flight. */
    var bitmap: Bitmap? = null
        private set

    /**
     * Reads the image again if [current] or the look ([night]) differs from the last; [onReady]
     * runs on main. The night look starts with the day's stamp, so the stamp alone is not enough.
     */
    fun refresh(current: String, night: Boolean, onReady: () -> Unit) {
        val key = if (current.isEmpty()) "" else "$night/$current"
        if (key == stamp) return
        stamp = key
        if (key.isEmpty()) {
            bitmap = null
            onReady()
            return
        }
        io.execute {
            val read = BackdropStore.load(context, night)
            main.post {
                // A newer pick while this one was read wins.
                if (stamp == key) {
                    bitmap = read
                    onReady()
                }
            }
        }
    }

    fun shutdown() {
        io.shutdown()
    }
}
