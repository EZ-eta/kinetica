package com.kinetica.keyboard.settings

import com.kinetica.keyboard.R
import com.kinetica.keyboard.keys.EditorAction

/**
 * The name a user sees for each [EditorAction], in one place, so every picker words an action the
 * same way and a new action needs one label.
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
        EditorAction.TOGGLE_NEXT_WORD -> R.string.action_toggle_next_word
        EditorAction.TOGGLE_RECENT_WORDS -> R.string.action_toggle_recent_words
        EditorAction.TOGGLE_NUMBER_ROW -> R.string.action_toggle_number_row
        EditorAction.TOGGLE_TIDY_SPACES -> R.string.action_toggle_tidy_spaces
        EditorAction.TOGGLE_TYPING_SPEED -> R.string.action_toggle_typing_speed
        EditorAction.TOGGLE_PECK_MODE -> R.string.action_toggle_peck_mode
        EditorAction.BACKSPACE -> R.string.action_backspace
        EditorAction.CTRL_NEXT -> R.string.action_ctrl_next
        EditorAction.TAB -> R.string.action_tab
        EditorAction.ESCAPE -> R.string.action_escape
        EditorAction.FORWARD_DELETE -> R.string.action_forward_delete
        EditorAction.HOME -> R.string.action_home
        EditorAction.END -> R.string.action_end
        EditorAction.ARROW_UP -> R.string.action_arrow_up
        EditorAction.ARROW_DOWN -> R.string.action_arrow_down
        EditorAction.ARROW_LEFT -> R.string.action_arrow_left
        EditorAction.ARROW_RIGHT -> R.string.action_arrow_right
        EditorAction.PAGE_UP -> R.string.action_page_up
        EditorAction.PAGE_DOWN -> R.string.action_page_down
    }

    /** The setting a toggle flips, as the spacebar names it. Null for anything else. */
    fun toggleNameRes(action: EditorAction): Int? = when (action) {
        EditorAction.TOGGLE_NEXT_WORD -> R.string.notice_name_next_word
        EditorAction.TOGGLE_RECENT_WORDS -> R.string.notice_name_recent_words
        EditorAction.TOGGLE_NUMBER_ROW -> R.string.notice_name_number_row
        EditorAction.TOGGLE_TIDY_SPACES -> R.string.notice_name_tidy_spaces
        EditorAction.TOGGLE_TYPING_SPEED -> R.string.notice_name_typing_speed
        EditorAction.TOGGLE_PECK_MODE -> R.string.notice_name_peck_mode
        EditorAction.CTRL_NEXT -> R.string.notice_name_ctrl
        else -> null
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
        EditorAction.TAB -> R.string.notice_tab
        EditorAction.ESCAPE -> R.string.notice_escape
        EditorAction.FORWARD_DELETE -> R.string.notice_forward_delete
        EditorAction.HOME -> R.string.notice_home
        EditorAction.END -> R.string.notice_end
        EditorAction.ARROW_UP -> R.string.notice_arrow_up
        EditorAction.ARROW_DOWN -> R.string.notice_arrow_down
        EditorAction.ARROW_LEFT -> R.string.notice_arrow_left
        EditorAction.ARROW_RIGHT -> R.string.notice_arrow_right
        EditorAction.PAGE_UP -> R.string.notice_page_up
        EditorAction.PAGE_DOWN -> R.string.notice_page_down
        EditorAction.BACKSPACE -> R.string.notice_backspace
        EditorAction.SETTINGS, EditorAction.NEXT_LANGUAGE, EditorAction.TOGGLE_AUTOSPACE,
        EditorAction.ONE_HANDED, EditorAction.TOGGLE_NEXT_WORD, EditorAction.TOGGLE_RECENT_WORDS,
        EditorAction.TOGGLE_NUMBER_ROW, EditorAction.TOGGLE_TIDY_SPACES,
        EditorAction.TOGGLE_TYPING_SPEED, EditorAction.TOGGLE_PECK_MODE, EditorAction.CTRL_NEXT,
        -> null
    }
}
