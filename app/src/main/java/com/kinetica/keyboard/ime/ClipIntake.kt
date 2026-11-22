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
 * Whether a new clipboard content is kept in the history, with no Android in it so the rule runs
 * on the JVM. Returns null to keep it, or the reason it is not kept (for the trace, never the text).
 */
internal object ClipIntake {

    /**
     * `ClipDescription.EXTRA_IS_SENSITIVE`, by its value: the constant is API 33, but password
     * managers set the extra on older phones too.
     */
    const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    fun skipReason(
        enabled: Boolean,
        teachesNothing: Boolean,
        sensitive: Boolean,
        items: Int,
        text: String?,
    ): String? = when {
        !enabled -> "off"
        // A password field, or one that asks for no learning (an incognito tab): nothing copied
        // while it is open is kept.
        teachesNothing -> "private"
        sensitive -> "sensitive"
        // One plain text only, as the keyboard's paste reads: several items are the app's to join,
        // and a URI would be read here on the main thread.
        items != 1 || text == null -> "notext"
        text.isBlank() -> "empty"
        else -> null
    }
}
