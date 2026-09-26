package com.kinetica.keyboard.settings

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.preference.PreferenceManager
import com.kinetica.keyboard.R
import com.kinetica.keyboard.data.Expansion
import com.kinetica.keyboard.data.KineticaDb
import com.kinetica.keyboard.ime.MAX_TRIGGER_CHARS
import com.kinetica.keyboard.keys.EditorAction
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The text-expansion list: trigger -> target, with add, edit, delete and a filter.
 *
 * An expansion fires on whatever is written at the cursor, so a trigger is free text
 * rather than a letter picked from a list. That is the difference from the chord screen
 * beside it and it is also why this feature does not inherit the chord identity limit: a
 * chord is a 0-25 letter index, an expansion is a string.
 *
 * Nothing is bound to a gesture by default. The action is offered in the edge-swipe and
 * chord editors, and the note at the top of this screen says so, because a shipped default
 * on a letter key is the class of thing that produced the mid-word misfire report.
 *
 * The keyboard reloads this table only when [Prefs.EXPANSION_GENERATION] moves, not at
 * every input start the way chords do: the list is expected to run to hundreds of rows.
 */
class ExpansionSettingsActivity : AppCompatActivity() {

    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var listContainer: LinearLayout
    private lateinit var emptyHint: TextView
    private var rows: List<Expansion> = emptyList()
    private var query: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad)
        }
        root.addView(
            TextView(this).apply {
                text = getString(R.string.expansion_intro)
                textSize = 13f
                setPadding(0, 0, 0, pad)
            },
        )
        root.addView(
            EditText(this).apply {
                hint = getString(R.string.expansion_filter_hint)
                inputType = InputType.TYPE_CLASS_TEXT
                addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) {
                        query = s?.toString().orEmpty()
                        render()
                    }

                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit

                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                })
            },
        )
        emptyHint = TextView(this).apply {
            text = getString(R.string.expansion_empty)
            setPadding(0, pad, 0, pad)
        }
        root.addView(emptyHint)
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)
        root.addView(
            Button(this).apply {
                text = getString(R.string.expansion_add)
                setOnClickListener { showEditor(existing = null) }
            },
        )
        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun dao() = KineticaDb.get(this).expansions()

    private fun refresh() {
        io.execute {
            val all = ExpansionRows.sortedForDisplay(dao().all())
            main.post {
                if (!isDestroyed) {
                    rows = all
                    render()
                }
            }
        }
    }

    /**
     * Tells the running keyboard to re-read the table.
     *
     * A counter rather than a re-read at every input start, which is what chords do: that
     * is free for at most twenty-six rows and grows with the table here.
     */
    private fun bumpGeneration() {
        val p = PreferenceManager.getDefaultSharedPreferences(this)
        p.edit()
            .putInt(Prefs.EXPANSION_GENERATION, p.getInt(Prefs.EXPANSION_GENERATION, 0) + 1)
            .apply()
    }

    private fun render() {
        val pad = (8 * resources.displayMetrics.density).toInt()
        listContainer.removeAllViews()
        val shown = ExpansionRows.filtered(rows, query)
        emptyHint.text = when {
            rows.isEmpty() -> getString(R.string.expansion_empty)
            shown.isEmpty() -> getString(R.string.expansion_no_match)
            else -> ""
        }
        emptyHint.visibility = if (shown.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        for (row in shown) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pad, 0, pad)
            }
            line.addView(
                TextView(this).apply {
                    text = getString(
                        R.string.expansion_row,
                        row.trigger,
                        ExpansionRows.shown(row.target, PREVIEW_CHARS) {
                            getString(ActionLabels.labelRes(it))
                        },
                    )
                    textSize = 16f
                    maxLines = 2
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
                            dao().deleteByTrigger(row.trigger)
                            main.post {
                                if (!isDestroyed) {
                                    bumpGeneration()
                                    refresh()
                                }
                            }
                        }
                    }
                },
            )
            listContainer.addView(line)
        }
    }

    private fun showEditor(existing: Expansion?) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val trigger = EditText(this).apply {
            hint = getString(R.string.expansion_trigger_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(existing?.trigger.orEmpty())
        }
        // What the expansion DOES, as the chord editor asks it (#19: text, or an action).
        // Built from the enum so a new action appears without being listed here, minus the
        // ones an expansion must not fire.
        val existingAction = existing?.target?.let { EditorAction.of(it) }
        val kinds = listOf<EditorAction?>(null) +
            EditorAction.entries.filter { it !in EditorAction.NOT_EXPANSION_TARGETS }
        val kindSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ExpansionSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                kinds.map { if (it == null) getString(R.string.chord_kind_text) else getString(ActionLabels.labelRes(it)) },
            )
            setSelection(kinds.indexOfFirst { it == existingAction }.coerceAtLeast(0))
        }
        val target = EditText(this).apply {
            hint = getString(R.string.expansion_target_hint)
            // Multi-line: a target may be a bullet block, which is the example its
            // reporter leads with.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(if (existingAction == null) existing?.target.orEmpty() else "")
            visibility = if (existingAction == null) View.VISIBLE else View.GONE
        }
        kindSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                target.visibility = if (kinds[pos] == null) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad)
            addView(TextView(context).apply { text = getString(R.string.expansion_trigger_label) })
            addView(trigger)
            addView(TextView(context).apply { text = getString(R.string.expansion_target_label) })
            addView(kindSpinner)
            addView(target)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.expansion_add else R.string.chord_edit)
            .setView(content)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        // Wired after show so a refused trigger keeps the dialog and what was typed in it;
        // the builder's own listener always dismisses.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val t = trigger.text.toString()
                val action = kinds[kindSpinner.selectedItemPosition]
                val v = action?.output ?: target.text.toString()
                if (!ExpansionRows.isValidTrigger(t, MAX_TRIGGER_CHARS)) {
                    Toast.makeText(this, R.string.expansion_bad_trigger, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (v.isEmpty()) return@setOnClickListener
                dialog.dismiss()
                io.execute {
                    // Editing to a different trigger frees the old one, the same way the
                    // chord editor frees a letter it moved away from.
                    if (existing != null && existing.trigger != t) {
                        dao().deleteByTrigger(existing.trigger)
                    }
                    dao().assign(t, listOf(v))
                    main.post {
                        if (!isDestroyed) {
                            bumpGeneration()
                            refresh()
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private companion object {
        /** Characters of a target shown on a list row before it is elided. */
        const val PREVIEW_CHARS = 40
    }
}
