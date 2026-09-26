package com.kinetica.keyboard.settings

import com.kinetica.keyboard.R
import com.kinetica.keyboard.keys.EditorAction

/**
 * The name a user sees for each [EditorAction], in one place.
 *
 * It was in two: the chord picker and the edge-swipe picker each had their own exhaustive
 * `when`, which meant adding an action was two compile errors and two chances to word the
 * same thing differently. The suggestion bar's action chooser would have been a third.
 */
object ActionLabels {

    /** String resource naming [action]. Exhaustive, so a new action cannot skip a label. */
    fun labelRes(action: EditorAction): Int = when (action) {
        EditorAction.PASTE -> R.string.chord_kind_paste
        EditorAction.COPY -> R.string.chord_kind_copy
        EditorAction.CUT -> R.string.chord_kind_cut
        EditorAction.SELECT_ALL -> R.string.chord_kind_select_all
        EditorAction.RETYPE -> R.string.chord_kind_retype
        EditorAction.EXPANDIFY -> R.string.chord_kind_expandify
        EditorAction.UNDO -> R.string.action_undo
        EditorAction.REDO -> R.string.action_redo
        EditorAction.SETTINGS -> R.string.action_settings
        EditorAction.NEXT_LANGUAGE -> R.string.action_next_language
        EditorAction.TOGGLE_AUTOSPACE -> R.string.action_toggle_autospace
        EditorAction.ONE_HANDED -> R.string.action_one_handed
        EditorAction.DATE -> R.string.action_date
        EditorAction.TIME -> R.string.action_time
        EditorAction.ENTER -> R.string.action_enter
        EditorAction.COPY_LINE -> R.string.action_copy_line
    }

    /**
     * The short name the spacebar shows after [action] ran from a shortcut, or null for an
     * action that says its new state instead (see `ActionRow.notice`). The labels above are
     * sentences and do not fit on a key.
     */
    fun noticeRes(action: EditorAction): Int? = when (action) {
        EditorAction.UNDO -> R.string.notice_undo
        EditorAction.REDO -> R.string.notice_redo
        EditorAction.PASTE -> R.string.notice_paste
        EditorAction.COPY -> R.string.notice_copy
        EditorAction.CUT -> R.string.notice_cut
        EditorAction.SELECT_ALL -> R.string.notice_select_all
        EditorAction.RETYPE -> R.string.notice_retype
        EditorAction.EXPANDIFY -> R.string.notice_expand
        EditorAction.DATE -> R.string.notice_date
        EditorAction.TIME -> R.string.notice_time
        EditorAction.ENTER -> R.string.notice_enter
        EditorAction.COPY_LINE -> R.string.notice_copy_line
        EditorAction.SETTINGS, EditorAction.NEXT_LANGUAGE, EditorAction.TOGGLE_AUTOSPACE,
        EditorAction.ONE_HANDED,
        -> null
    }
}
