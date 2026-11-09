package com.kinetica.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.kinetica.keyboard.R
import com.kinetica.keyboard.keys.ActionRow
import com.kinetica.keyboard.keys.EditorAction

/**
 * Each editor action's icon, from Material Symbols Outlined (Apache-2.0, THIRD_PARTY_NOTICES),
 * drawn where its symbol was: the symbols came from whichever font held them, so their weights
 * and sizes differed.
 *
 * Render only. The symbol string stays the action's identity in lists, backups and chord labels,
 * and is drawn as before for an action with no icon.
 */
internal class GlyphIcons(private val context: Context) {

    private val drawables = HashMap<Int, Drawable>()
    private val tints = HashMap<Int, Int>()

    /**
     * Draws [action]'s icon, [size] px square, centred on ([cx], [cy]), in [color]. False when
     * there is no action or it has no icon: the caller draws the symbol.
     */
    fun draw(canvas: Canvas, action: EditorAction?, cx: Float, cy: Float, size: Float, color: Int): Boolean {
        val res = action?.let { resOf(it) } ?: return false
        val d = drawables.getOrPut(res) {
            ContextCompat.getDrawable(context, res)?.mutate() ?: return false
        }
        // A tint builds a colour filter: only on a change, since popups redraw every frame.
        if (tints[res] != color) {
            d.setTint(color)
            tints[res] = color
        }
        val half = size / 2f
        d.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        d.draw(canvas)
        return true
    }

    companion object {
        /** An icon's side over the text size its symbol was drawn at: a symbol's own height. */
        const val SIZE_OVER_TEXT = 1.2f

        private val BY_SYMBOL: Map<String, EditorAction> =
            EditorAction.entries.associateBy { ActionRow.glyph(it) }

        /**
         * The action a bar or `?123` menu cell names. Every cell there is an action, so a symbol
         * that is also text (`ALL`, `→`) counts; a long-press cell goes through
         * [ActionRow.actionForGlyph] instead.
         */
        fun actionOfSymbol(symbol: String): EditorAction? =
            BY_SYMBOL[if (symbol.lastOrNull() == ActionRow.ACTION_MARK) symbol.dropLast(1) else symbol]

        /** The icon an action is drawn with, or null where its symbol reads better (`abc`, `⎋`). */
        fun resOf(action: EditorAction): Int? = when (action) {
            EditorAction.SETTINGS -> R.drawable.ic_action_settings
            EditorAction.NEXT_LANGUAGE -> R.drawable.ic_action_language
            EditorAction.ONE_HANDED -> R.drawable.ic_action_dock_to_left
            EditorAction.TOGGLE_AUTOSPACE -> R.drawable.ic_action_space_bar
            EditorAction.UNDO -> R.drawable.ic_action_undo
            EditorAction.REDO -> R.drawable.ic_action_redo
            EditorAction.PASTE -> R.drawable.ic_action_content_paste
            EditorAction.COPY -> R.drawable.ic_action_content_copy
            EditorAction.CUT -> R.drawable.ic_action_content_cut
            EditorAction.SELECT_ALL -> R.drawable.ic_action_select_all
            EditorAction.RETYPE -> R.drawable.ic_action_refresh
            EditorAction.EXPANDIFY -> R.drawable.ic_action_keyboard_double_arrow_right
            EditorAction.DATE -> R.drawable.ic_action_calendar_today
            EditorAction.TIME -> R.drawable.ic_action_schedule
            EditorAction.ENTER -> R.drawable.ic_action_keyboard_return
            EditorAction.COPY_LINE -> R.drawable.ic_action_copy_all
            EditorAction.CUT_LINE_AFTER -> R.drawable.ic_action_cut_line_after
            EditorAction.CUT_LINE_BEFORE -> R.drawable.ic_action_cut_line_before
            EditorAction.CLEAN_LINKS -> R.drawable.ic_action_link_off
            EditorAction.TOGGLE_NEXT_WORD -> R.drawable.ic_action_next_plan
            EditorAction.TOGGLE_RECENT_WORDS -> R.drawable.ic_action_history
            EditorAction.TOGGLE_NUMBER_ROW -> R.drawable.ic_action_123
            EditorAction.TOGGLE_TIDY_SPACES -> R.drawable.ic_action_compress
            EditorAction.TOGGLE_TYPING_SPEED -> R.drawable.ic_action_speed
            EditorAction.TOGGLE_PECK_MODE -> R.drawable.ic_action_touch_app
            EditorAction.TAB -> R.drawable.ic_action_keyboard_tab
            EditorAction.FORWARD_DELETE -> R.drawable.ic_action_forward_delete
            EditorAction.HOME -> R.drawable.ic_action_first_page
            EditorAction.END -> R.drawable.ic_action_last_page
            EditorAction.ARROW_UP -> R.drawable.ic_action_arrow_upward
            EditorAction.ARROW_DOWN -> R.drawable.ic_action_arrow_downward
            EditorAction.ARROW_LEFT -> R.drawable.ic_action_arrow_back
            EditorAction.ARROW_RIGHT -> R.drawable.ic_action_arrow_forward
            EditorAction.PAGE_UP -> R.drawable.ic_action_keyboard_double_arrow_up
            EditorAction.PAGE_DOWN -> R.drawable.ic_action_keyboard_double_arrow_down
            EditorAction.BACKSPACE -> R.drawable.ic_action_backspace
            EditorAction.CTRL_NEXT -> R.drawable.ic_action_keyboard_control_key
            EditorAction.DICTATE -> R.drawable.ic_action_mic
            EditorAction.EMOJI_SEARCH -> R.drawable.ic_action_search
            EditorAction.EDIT_PAGE -> R.drawable.ic_action_open_with
            EditorAction.SELECT_MODE -> R.drawable.ic_action_select
            // No Material symbol for escape; the case cells read as what they do.
            EditorAction.ESCAPE,
            EditorAction.CASE_LOWER, EditorAction.CASE_TITLE, EditorAction.CASE_UPPER,
            -> null
        }
    }
}
