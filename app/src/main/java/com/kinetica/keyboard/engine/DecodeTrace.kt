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

package com.kinetica.keyboard.engine

/**
 * Optional, default-off tracing of the decode path: token buffer, merge-split decisions,
 * candidates. Two-thumb timing and geometry can only be captured on a device, so the DEV
 * build's TraceRecorder points [sink] at Logcat and a file (`adb logcat -s KineticaTrace`).
 *
 * Engine-pure, so the sink is a plain function reference. Messages are built lazily, so a disabled
 * trace costs one null check per call site. The decode thread writes; the sink must be
 * thread-safe (Logcat is).
 */
object DecodeTrace {

    @Volatile
    var sink: ((String) -> Unit)? = null

    val enabled: Boolean get() = sink != null

    /**
     * Bumped when the sink's file starts over (cleared or rotated), so a line written once per
     * board is written again where the next capture reads it.
     */
    @Volatile
    var epoch: Int = 0

    /** This thread's held lines while it decodes beside another thread, else null. */
    @PublishedApi
    internal val heldLines = ThreadLocal<ArrayList<String>?>()

    inline fun log(message: () -> String) {
        val s = sink ?: return
        val held = heldLines.get()
        if (held != null) held.add(message()) else s(message())
    }

    /**
     * Runs [block] with this thread's lines held, not written, and returns them with its result.
     * A second language decodes on its own thread; writing its lines after the first language's
     * keeps one block per decode for the corpus tools.
     */
    fun <T> holding(block: () -> T): Pair<T, List<String>> {
        if (sink == null) return block() to emptyList()
        val held = ArrayList<String>()
        heldLines.set(held)
        try {
            return block() to held
        } finally {
            heldLines.set(null)
        }
    }

    /** Writes lines [holding] kept back, in order. */
    fun write(lines: List<String>) {
        val s = sink ?: return
        for (l in lines) s(l)
    }
}
