package com.kinetica.keyboard.settings

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.preference.PreferenceManager
import com.kinetica.keyboard.R
import com.kinetica.keyboard.keys.ActionRow
import com.kinetica.keyboard.keys.EditorAction
import com.kinetica.keyboard.keys.ShortcutSet

/**
 * One shortcut set, the bar's or the ?123 menu's: a checkbox per shortcut and up/down to
 * order the chosen ones. Replaces two multi-select lists that could choose but not order.
 *
 * Writes the selection and the order on every change, so there is no save step to forget and
 * the keyboard follows at once through its preference listener.
 */
class ShortcutSetActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var set: ShortcutSet
    private lateinit var selectionKey: String
    private lateinit var orderKey: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val menu = intent.getStringExtra(EXTRA_SET) == SET_MENU
        selectionKey = if (menu) Prefs.MENU_ACTIONS else Prefs.BAR_ACTIONS
        orderKey = if (menu) Prefs.MENU_ACTIONS_ORDER else Prefs.BAR_ACTIONS_ORDER
        title = getString(if (menu) R.string.pref_menu_actions_title else R.string.pref_bar_actions_title)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        set = ShortcutSet.from(
            prefs.getStringSet(selectionKey, null) ?: ActionRow.DEFAULT,
            prefs.getString(orderKey, null),
        )

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad)
        }
        root.addView(
            TextView(this).apply {
                text = getString(R.string.shortcut_set_intro)
                textSize = 13f
                setPadding(0, 0, 0, pad)
            },
        )
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        setContentView(ScrollView(this).apply { addView(root) })
        render()
    }

    private fun render() {
        list.removeAllViews()
        for ((action, on) in set.rows()) list.addView(row(action, on))
    }

    private fun row(action: EditorAction, on: Boolean): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                CheckBox(context).apply {
                    text = getString(R.string.shortcut_row, ActionRow.glyph(action), getString(ActionLabels.labelRes(action)))
                    isChecked = on
                    setOnClickListener { change { set.toggle(action) } }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(moveButton(action, -1, "↑", R.string.shortcut_move_up))
            addView(moveButton(action, +1, "↓", R.string.shortcut_move_down))
        }

    private fun moveButton(action: EditorAction, delta: Int, glyph: String, description: Int): Button =
        Button(this).apply {
            text = glyph
            contentDescription = getString(description)
            isEnabled = set.canMove(action, delta)
            minWidth = 0
            minimumWidth = (48 * resources.displayMetrics.density).toInt()
            setOnClickListener { change { set.move(action, delta) } }
        }

    private fun change(edit: () -> Unit) {
        edit()
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putStringSet(selectionKey, set.selection())
            .putString(orderKey, set.order())
            .apply()
        render()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val EXTRA_SET = "set"
        const val SET_BAR = "bar"
        const val SET_MENU = "menu"
    }
}
