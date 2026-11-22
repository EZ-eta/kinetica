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

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.kinetica.keyboard.engine.DecodeTrace
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The clipboard history for the whole process: the keyboard keeps and pastes, the settings clear.
 * One copy in memory, so a clear from settings cannot be undone by the keyboard's next save. The
 * list is the main thread's; the disk is one background thread's, in the order the changes came.
 */
object ClipVault {

    @Volatile
    private var short = false
    private val history = ClipHistory { ClipRules.ttl(short) }
    private var store: ClipStore? = null
    private var loadAsked = false

    private val main = Handler(Looper.getMainLooper())
    private val disk: ExecutorService = Executors.newSingleThreadExecutor { r ->
        // The Keystore is a call to another process: never on the main thread, never behind a
        // dictionary load on the database thread.
        Thread(r, "kinetica-clips").apply { isDaemon = true }
    }

    private fun store(context: Context): ClipStore =
        store ?: ClipStore(ClipStore.file(context.applicationContext)).also { store = it }

    /**
     * The switches as set. On, the stored clips are read once per process and merged behind any
     * kept since. Off, memory and disk are cleared ([why] for the trace), the file too if one was
     * left by an earlier run.
     */
    fun configure(context: Context, enabled: Boolean, shortLife: Boolean, why: String) {
        short = shortLife
        val s = store(context)
        if (!enabled) {
            val had = s.enabled || history.size > 0
            s.enabled = false
            history.clear()
            loadAsked = false
            disk.execute {
                // Checked on the disk thread: a file left by an earlier run goes too.
                if (had || s.exists()) {
                    s.wipe()
                    DecodeTrace.log { "  clip wipe why=$why" }
                }
            }
            return
        }
        s.enabled = true
        if (loadAsked) return
        loadAsked = true
        disk.execute {
            val loaded = s.load()
            main.post {
                if (!s.enabled) return@post
                history.restore(loaded, System.currentTimeMillis())
                DecodeTrace.log { "  clip load n=${loaded.size} live=${history.size} expired=${history.expiredLast}" }
                save(s)
            }
        }
    }

    /** Keeps [text] copied at [now]; false when the history is off or the text is not kept. */
    fun keep(context: Context, text: String, now: Long): Boolean {
        val s = store(context)
        if (!s.enabled || !history.add(text, now)) return false
        save(s)
        return true
    }

    /** The clips alive at [now], newest first; the expired ones deleted, on disk too. */
    fun live(context: Context, now: Long): List<Clip> {
        val clips = history.live(now)
        if (history.expiredLast > 0) {
            val n = history.expiredLast
            DecodeTrace.log { "  clip expired n=$n" }
            save(store(context))
        }
        return clips
    }

    fun delete(context: Context, id: Long) {
        if (history.delete(id)) save(store(context))
    }

    /** Every clip gone, from memory and disk; the history stays on. */
    fun clear(context: Context, why: String) {
        history.clear()
        val s = store(context)
        disk.execute {
            s.wipe()
            DecodeTrace.log { "  clip wipe why=$why" }
        }
    }

    private fun save(s: ClipStore) {
        val snapshot = history.live(System.currentTimeMillis())
        disk.execute { s.save(snapshot) }
    }
}
