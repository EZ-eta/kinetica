package com.kinetica.keyboard.settings

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.preference.PreferenceManager
import com.kinetica.keyboard.R
import com.kinetica.keyboard.keys.ChordLabels
import com.kinetica.keyboard.keys.EditPage

/**
 * The editing page's keys, shown as the page is laid out: tap a key to change it, `+` to add one.
 * Each key is edited with the menu the chords and edge swipes use (text, Ctrl and a key, an
 * action), so there is no syntax to learn. The bottom row is shown but fixed. Stored only when it
 * differs from the D-pad, so Changed settings lists it and its reset gives the D-pad back.
 */
class EditPageActivity : AppCompatActivity() {

    private lateinit var grid: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.edit_page_hint)
            setPadding(0, 0, 0, pad)
        })
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(grid)
        root.addView(Button(this).apply {
            text = getString(R.string.edit_page_reset)
            setOnClickListener {
                AlertDialog.Builder(this@EditPageActivity)
                    .setMessage(R.string.edit_page_reset_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ -> save(EditPage.DEFAULT) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        })
        setContentView(ScrollView(this).apply { addView(root) })
        render()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun prefs() = PreferenceManager.getDefaultSharedPreferences(this)

    private fun page(): EditPage = EditPage.parse(prefs().getString(Prefs.EDIT_PAGE, null))

    private fun save(page: EditPage) {
        val edit = prefs().edit()
        if (page == EditPage.DEFAULT) edit.remove(Prefs.EDIT_PAGE) else edit.putString(Prefs.EDIT_PAGE, page.serialize())
        edit.apply()
        render()
    }

    private fun render() {
        grid.removeAllViews()
        val page = page()
        for ((r, row) in page.rows.withIndex()) {
            val line = rowView()
            for ((c, out) in row.withIndex()) {
                line.addView(keyButton(ChordLabels.of(out)) { showEditor(r, c) })
            }
            if (row.size < EditPage.MAX_KEYS) line.addView(keyButton("+") { showEditor(r, null) })
            grid.addView(line)
        }
        if (page.rows.size < EditPage.MAX_ROWS) {
            grid.addView(Button(this).apply {
                text = getString(R.string.edit_page_add_row)
                isAllCaps = false
                setOnClickListener { showEditor(page.rows.size, null) }
            })
        }
        val fixed = rowView()
        for (label in listOf("ABC", "␣", "⌫", "⏎")) {
            fixed.addView(keyButton(label) {}.apply { isEnabled = false })
        }
        grid.addView(fixed)
    }

    private fun rowView() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun keyButton(label: String, onTap: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { onTap() }
    }

    /** The key at [row], [col] changed, or a key added at [row]'s end when [col] is null. */
    private fun showEditor(row: Int, col: Int?) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val page = page()
        val existing = col?.let { page.rows.getOrNull(row)?.getOrNull(it) }
        val kinds = ShortcutKinds.editPage
        val (wasKind, wasField) = ShortcutKinds.decode(existing)
        val textField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(wasField)
        }
        fun showFieldFor(kind: ShortcutKind) {
            textField.visibility = if (ShortcutKinds.usesField(kind)) View.VISIBLE else View.GONE
            textField.hint = getString(ShortcutKinds.hintRes(kind, R.string.edit_page_text_hint))
        }
        showFieldFor(wasKind)
        val kindSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@EditPageActivity,
                android.R.layout.simple_spinner_dropdown_item,
                kinds.map { getString(ShortcutKinds.labelRes(it)) },
            )
            setSelection(kinds.indexOf(wasKind).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    showFieldFor(kinds[pos])
                    textField.error = null
                }

                override fun onNothingSelected(p: AdapterView<*>?) = Unit
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad)
            addView(kindSpinner)
            addView(textField)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(if (col == null) R.string.edit_page_add_key else R.string.edit_page_change_key)
            .setView(content)
            // Set again once shown, so a refused key keeps the dialog open with its reason.
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (col != null) builder.setNeutralButton(R.string.edit_page_delete) { _, _ -> save(page.without(row, col)) }
        val dialog = builder.create()
        if (col != null) {
            // Moves along the row, one place a tap; the dialog closes so the grid shows where it went.
            val moves = rowView()
            moves.addView(keyButton(getString(R.string.edit_page_move_left)) { save(page.moved(row, col, -1)); dialog.dismiss() })
            moves.addView(keyButton(getString(R.string.edit_page_move_right)) { save(page.moved(row, col, 1)); dialog.dismiss() })
            content.addView(moves)
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val kind = kinds[kindSpinner.selectedItemPosition]
            val output = ShortcutKinds.encode(kind, textField.text.toString())
            val refused = when {
                output == null -> if (kind == ShortcutKind.CtrlKey) R.string.combo_key_unsendable else R.string.edit_page_text_hint
                EditPage.problem(output) == EditPage.Problem.LETTER -> R.string.edit_page_letter
                EditPage.problem(output) != null -> R.string.edit_page_text_hint
                else -> null
            }
            if (output == null || refused != null) {
                textField.error = getString(refused ?: R.string.edit_page_text_hint)
                return@setOnClickListener
            }
            val next = if (col == null) page.withAdded(row, output) else page.withKey(row, col, output)
            if (next != null) save(next)
            dialog.dismiss()
        }
    }
}
