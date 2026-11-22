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

import android.view.inputmethod.EditorInfo

/**
 * What the enter key does in a field: write a newline, or run the app's action.
 *
 * Android's contract is [EditorInfo.IME_FLAG_NO_ENTER_ACTION]: `TextView` sets it on every
 * multi-line field and an app clears it when enter should search or send, as Google's
 * multi-line search box does. Reading `multiline` gave that box a newline. Any action but NONE
 * runs, UNSPECIFIED included, as in AOSP: an app that names no action still expects enter to
 * reach it as a key event.
 */
object EnterBehavior {

    sealed interface Result {
        data object Newline : Result
        data class Action(val id: Int) : Result
    }

    /**
     * What a tap does. [followApp] is the setting; off, a newline in any
     * multi-line field. With [holdSends] on, a field with an action takes a newline on the tap and
     * the action on the hold ([holdAction]).
     */
    fun tap(state: EditorState, followApp: Boolean, holdSends: Boolean): Result =
        if (holdAction(state, holdSends) != null) Result.Newline else resolve(state, followApp)

    /**
     * The action a hold runs with [holdSends] on, or null when the field names none. Multi-line
     * fields only: a single-line field has no use for a newline, so its tap keeps the action. The
     * no-enter-action flag is not read: it asks for a newline on enter, which the tap gives, and
     * a chat box that sets it beside Send is where a hold to send is wanted.
     */
    fun holdAction(state: EditorState, holdSends: Boolean): Result.Action? {
        if (!holdSends || !state.multiline) return null
        if (state.actionLabel != null) return Result.Action(state.customActionId)
        if (state.actionId == EditorInfo.IME_ACTION_NONE || state.actionId == EditorInfo.IME_ACTION_UNSPECIFIED) {
            return null
        }
        return Result.Action(state.actionId)
    }

    /** [followApp] is the setting; off, a newline in any multi-line field. */
    fun resolve(state: EditorState, followApp: Boolean): Result {
        if (!followApp) {
            return if (state.multiline ||
                state.actionId == EditorInfo.IME_ACTION_NONE ||
                state.actionId == EditorInfo.IME_ACTION_UNSPECIFIED
            ) Result.Newline else Result.Action(state.actionId)
        }
        if (state.noEnterAction) return Result.Newline
        if (state.actionLabel != null) return Result.Action(state.customActionId)
        if (state.actionId == EditorInfo.IME_ACTION_NONE) return Result.Newline
        return Result.Action(state.actionId)
    }

    /**
     * The action as the key shows it, or null for the plain enter glyph. An app's own label
     * wins. UNSPECIFIED shows nothing: the app named no action, so there is no word for it.
     */
    fun labelKind(state: EditorState, followApp: Boolean, holdSends: Boolean = false): Label? {
        val r = tap(state, followApp, holdSends) as? Result.Action ?: return null
        if (state.actionLabel != null && followApp) return Label.Custom(state.actionLabel)
        return when (r.id) {
            EditorInfo.IME_ACTION_SEARCH -> Label.SEARCH
            EditorInfo.IME_ACTION_SEND -> Label.SEND
            EditorInfo.IME_ACTION_GO -> Label.GO
            EditorInfo.IME_ACTION_NEXT -> Label.NEXT
            EditorInfo.IME_ACTION_DONE -> Label.DONE
            EditorInfo.IME_ACTION_PREVIOUS -> Label.PREVIOUS
            else -> null
        }
    }

    sealed interface Label {
        data class Custom(val text: String) : Label
        data object SEARCH : Label
        data object SEND : Label
        data object GO : Label
        data object NEXT : Label
        data object DONE : Label
        data object PREVIOUS : Label
    }
}
