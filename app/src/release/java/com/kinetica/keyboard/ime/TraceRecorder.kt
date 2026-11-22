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

/**
 * Release build: decode tracing does not exist.
 *
 * The debug build's recorder writes every decoded gesture to a file, so it lives in the debug
 * source set and this stub replaces it. A released APK contains no code at all that can write what
 * was typed to disk, not even disabled code, and no crash handler.
 */
object TraceRecorder {
    /** Never read in a release build; the service sets it for the debug build's crash lines. */
    @Volatile
    var crashState: (() -> String)? = null

    /** No trace sink is installed in a release build. */
    fun install(context: Context) = Unit
}
