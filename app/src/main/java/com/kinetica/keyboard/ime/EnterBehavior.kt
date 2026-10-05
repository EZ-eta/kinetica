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

    /** [followApp] is the setting; off gives the old rule, a newline in any multi-line field. */
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
    fun labelKind(state: EditorState, followApp: Boolean): Label? {
        val r = resolve(state, followApp) as? Result.Action ?: return null
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
