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

package com.kinetica.keyboard.ui

/**
 * When the suggestion bar shows its shortcut row, and when the button that brings the row back.
 *
 * On its own the row shows only on an empty bar, which in practice is a new field. The button
 * forces it over the words until the next key. Pure, so the rule is testable without a view,
 * like [BarPaging].
 */
object BarShortcuts {

    /**
     * Whether the row is drawn. Unforced, words, the correction strip and a word in progress each
     * keep it down: a thumb aiming at a suggestion must not land on a shortcut.
     */
    fun rowShown(
        hasActions: Boolean,
        hasWords: Boolean,
        correctionMode: Boolean,
        wordPending: Boolean,
        forced: Boolean,
    ): Boolean = hasActions && (forced || (!hasWords && !correctionMode && !wordPending))

    /**
     * Whether the button is drawn: the switch on, shortcuts to show, and words for the row to
     * cover. On an empty bar the row is already up and the button would only cost width.
     */
    fun buttonShown(enabled: Boolean, hasActions: Boolean, hasWords: Boolean): Boolean =
        enabled && hasActions && hasWords
}
