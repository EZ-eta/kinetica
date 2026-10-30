package com.kinetica.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.setPadding
import com.kinetica.keyboard.R
import com.kinetica.keyboard.ime.EditorState
import com.kinetica.keyboard.ime.KeyboardViews
import com.kinetica.keyboard.layout.KeyboardLayout
import com.kinetica.keyboard.layout.LayoutLoader
import com.kinetica.keyboard.layout.SymbolRows
import java.io.IOException
import org.json.JSONException

/**
 * The editor of one symbol page: its three rows as three lines, the problem under the field as it
 * is typed, Default to refill the built-in rows. A row is stored only when it differs from the
 * built-in one, so a later change to the bundled page still arrives.
 */
object SymbolRowsDialog {

    private const val TAG = "SymbolRows"

    fun show(context: Context, prefs: SharedPreferences, page: Int, onSaved: () -> Unit) {
        val base = builtIn(context, page) ?: run {
            Toast.makeText(context, R.string.symbol_rows_unreadable, Toast.LENGTH_SHORT).show()
            return
        }
        val defaults = SymbolRows.defaults(base)
        val shown = (1..SymbolRows.ROWS).map { row ->
            prefs.getString(Prefs.symbolRowKey(page, row), null) ?: defaults[row - 1]
        }
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        val field = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            // Kinetica writes no automatic space here: the spaces between keys are the user's.
            privateImeOptions = EditorState.ONE_TOKEN_OPTION
            minLines = SymbolRows.ROWS
            setText(shown.joinToString("\n"))
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad)
            addView(TextView(context).apply { text = context.getString(R.string.symbol_rows_help) })
            addView(field)
        }
        val title = if (page == 2) R.string.pref_symbol_rows_2_title else R.string.pref_symbol_rows_1_title
        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(content)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.symbol_rows_default, null)
            .create()
        // Wired after show so a refused row keeps the dialog and what was typed in it.
        dialog.setOnShowListener {
            fun showProblem(): Boolean {
                val found = SymbolRows.firstProblem(SymbolRows.lines(field.text.toString()))
                field.error = found?.let { (row, f) -> message(context, row + 1, f) }
                return found != null
            }
            showProblem()
            field.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    showProblem()
                }

                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit

                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            })
            // Refills the field and leaves the dialog open, so the rows are seen before OK.
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                field.setText(defaults.joinToString("\n"))
                field.setSelection(field.text.length)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (showProblem()) {
                    field.requestFocus()
                    return@setOnClickListener
                }
                save(prefs, page, SymbolRows.lines(field.text.toString()), defaults)
                dialog.dismiss()
                onSaved()
            }
        }
        dialog.show()
    }

    /** Built-in rows or edited, for the row's summary. */
    fun summary(context: Context, prefs: SharedPreferences, page: Int): String {
        val edited = (1..SymbolRows.ROWS).any { prefs.contains(Prefs.symbolRowKey(page, it)) }
        return context.getString(if (edited) R.string.symbol_rows_edited else R.string.symbol_rows_builtin)
    }

    private fun save(prefs: SharedPreferences, page: Int, lines: List<String>, defaults: List<String>) {
        val e = prefs.edit()
        for (row in 1..SymbolRows.ROWS) {
            val typed = SymbolRows.parse(lines.getOrNull(row - 1)).joinToString(" ")
            val key = Prefs.symbolRowKey(page, row)
            if (typed.isEmpty() || typed == defaults[row - 1]) e.remove(key) else e.putString(key, typed)
        }
        e.apply()
    }

    private fun message(context: Context, row: Int, found: SymbolRows.Found): String = when (found.problem) {
        SymbolRows.Problem.LETTER -> context.getString(R.string.symbol_rows_letter, row, found.key)
        SymbolRows.Problem.TOO_LONG ->
            context.getString(R.string.symbol_rows_too_long, row, found.key, SymbolRows.MAX_KEY_CHARS)
        SymbolRows.Problem.TOO_MANY ->
            context.getString(R.string.symbol_rows_too_many, row, SymbolRows.MAX_KEYS[row - 1])
        SymbolRows.Problem.TOO_MANY_ROWS -> context.getString(R.string.symbol_rows_too_many_rows, SymbolRows.ROWS)
    }

    private fun builtIn(context: Context, page: Int): KeyboardLayout? {
        val name = if (page == 2) KeyboardViews.SYMBOLS2 else KeyboardViews.SYMBOLS
        return try {
            LayoutLoader.load(context.assets, "layouts/$name.json")
        } catch (e: IOException) {
            Log.w(TAG, "symbol page $name", e)
            null
        } catch (e: JSONException) {
            Log.w(TAG, "symbol page $name", e)
            null
        }
    }
}
