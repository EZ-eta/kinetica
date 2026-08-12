package com.kinetica.keyboard.settings

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
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
import com.kinetica.keyboard.data.ChordShortcut
import com.kinetica.keyboard.keys.EditorAction
import com.kinetica.keyboard.data.KineticaDb
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Chord management: list of letter -> expansion bindings with add/edit/delete.
 * Chords are opt-in per letter; an empty list keeps the feature inert. The
 * keyboard re-reads the table on every input start, so changes apply on the
 * next focused field without restarting the IME.
 */
class ChordSettingsActivity : AppCompatActivity() {

    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var listContainer: LinearLayout
    private lateinit var emptyHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad)
        }
        emptyHint = TextView(this).apply {
            text = getString(R.string.chord_empty_hint)
            setPadding(0, 0, 0, pad)
        }
        root.addView(emptyHint)
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)
        root.addView(
            Button(this).apply {
                text = getString(R.string.chord_add)
                setOnClickListener { showEditor(existing = null) }
            },
        )
        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun dao() = KineticaDb.get(this).chordShortcuts()

    private fun refresh() {
        io.execute {
            val rows = dao().all().sortedBy { it.chord }
            main.post { if (!isDestroyed) render(rows) }
        }
    }

    private fun render(rows: List<ChordShortcut>) {
        listContainer.removeAllViews()
        emptyHint.text = getString(
            if (rows.isEmpty()) R.string.chord_empty_hint else R.string.chord_list_hint,
        )
        val pad = (8 * resources.displayMetrics.density).toInt()
        for (row in rows) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pad, 0, pad)
            }
            line.addView(
                TextView(this).apply {
                    text = getString(R.string.chord_row, row.chord, row.expansion)
                    textSize = 16f
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            line.addView(
                Button(this).apply {
                    text = getString(R.string.chord_edit)
                    setOnClickListener { showEditor(row) }
                },
            )
            line.addView(
                Button(this).apply {
                    text = getString(R.string.chord_delete)
                    setOnClickListener {
                        io.execute {
                            dao().delete(row)
                            main.post { if (!isDestroyed) refresh() }
                        }
                    }
                },
            )
            listContainer.addView(line)
        }
    }

    private fun showEditor(existing: ChordShortcut?) {
        io.execute {
            val taken = dao().all().map { it.chord }.toSet()
            main.post { if (!isDestroyed) showEditorDialog(existing, taken) }
        }
    }

    private fun showEditorDialog(existing: ChordShortcut?, taken: Set<String>) {
        // Offer unassigned letters, plus the edited chord's own letter.
        val letters = ('a'..'z').map { it.toString() }
            .filter { it !in taken || it == existing?.chord }
        if (letters.isEmpty()) return
        val pad = (16 * resources.displayMetrics.density).toInt()

        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ChordSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                letters,
            )
            setSelection(letters.indexOf(existing?.chord).coerceAtLeast(0))
        }
        // What the chord DOES. Free text was the only option, and the reserved
        // "action:" outputs were undiscoverable - a user could only reach them by
        // guessing the magic string, which until now inserted itself as literal
        // text instead of running.
        val existingAction = existing?.expansion?.let { EditorAction.of(it) }
        val kinds = listOf(
            null to getString(R.string.chord_kind_text),
            EditorAction.PASTE to getString(R.string.chord_kind_paste),
            EditorAction.COPY to getString(R.string.chord_kind_copy),
            EditorAction.CUT to getString(R.string.chord_kind_cut),
            EditorAction.SELECT_ALL to getString(R.string.chord_kind_select_all),
        )
        val kindSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ChordSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                kinds.map { it.second },
            )
            setSelection(kinds.indexOfFirst { it.first == existingAction }.coerceAtLeast(0))
        }
        val expansion = EditText(this).apply {
            hint = getString(R.string.chord_expansion_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            // A command chord has no text, so an existing one leaves this blank
            // rather than showing its reserved output back to the user.
            setText(if (existingAction == null) existing?.expansion.orEmpty() else "")
            visibility = if (existingAction == null) View.VISIBLE else View.GONE
        }
        kindSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                expansion.visibility =
                    if (kinds[pos].first == null) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        // Reserved chords (language cycle, peck toggle) take precedence over
        // text chords on the same letter (KineticaIME.onChordTriggered), so a
        // colliding assignment would sit silently dead. Saving is still
        // allowed - the language reservation only bites while >1 language is
        // enabled - but the collision must be visible at assignment time.
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        fun reserved(key: String, default: String): String? =
            (prefs.getString(key, default) ?: default)
                .takeIf { it.length == 1 && it[0] in 'a'..'z' }
        val langLetter = reserved(Prefs.LANG_CYCLE_KEY, Prefs.DEFAULT_LANG_CYCLE_KEY)
        val peckLetter = reserved(Prefs.PECK_CHORD_KEY, Prefs.DEFAULT_PECK_CHORD_KEY)
        val warning = TextView(this).apply {
            visibility = View.GONE
            setPadding(0, pad / 2, 0, 0)
        }
        fun updateWarning(letter: String) {
            val text = when (letter) {
                langLetter -> getString(R.string.chord_reserved_lang, letter)
                peckLetter -> getString(R.string.chord_reserved_peck, letter)
                else -> null
            }
            warning.text = text.orEmpty()
            warning.visibility = if (text == null) View.GONE else View.VISIBLE
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                updateWarning(letters[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad)
            addView(TextView(context).apply { text = getString(R.string.chord_letter_label) })
            addView(spinner)
            addView(warning)
            addView(TextView(context).apply { text = getString(R.string.chord_kind_label) })
            addView(kindSpinner)
            addView(expansion)
        }
        AlertDialog.Builder(this)
            .setTitle(
                if (existing == null) R.string.chord_add else R.string.chord_edit,
            )
            .setView(content)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val letter = spinner.selectedItem as String
                val action = kinds[kindSpinner.selectedItemPosition].first
                val text = action?.output ?: expansion.text.toString()
                if (text.isEmpty()) return@setPositiveButton
                io.execute {
                    // Editing to a different letter frees the old binding.
                    if (existing != null && existing.chord != letter) {
                        dao().delete(existing)
                    }
                    dao().assign(letter, text)
                    main.post { if (!isDestroyed) refresh() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
