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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.preference.PreferenceManager
import com.kinetica.keyboard.R
import com.kinetica.keyboard.data.Expansion
import com.kinetica.keyboard.data.KineticaDb
import com.kinetica.keyboard.ime.EditorState
import com.kinetica.keyboard.ime.MAX_TRIGGER_CHARS
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The text-expansion list: trigger -> target, with add, edit, delete and a filter.
 *
 * An expansion fires on whatever is written at the cursor, so a trigger is free text of any
 * length, where a chord is one key.
 *
 * Nothing is bound to a gesture by default: the action is offered in the edge-swipe and chord
 * editors, and the note at the top of this screen says so, because a shipped default on a letter
 * key can misfire mid-word.
 *
 * The keyboard reloads this table only when [Prefs.EXPANSION_GENERATION] moves, not at every
 * input start as chords do, since the list can run to hundreds of rows.
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
        // Under the filter, not below the list, so with hundreds of rows adding one needs no
        // scroll (#19).
        root.addView(
            Button(this).apply {
                text = getString(R.string.expansion_add)
                setOnClickListener { showEditor(existing = null) }
            },
        )
        emptyHint = TextView(this).apply {
            text = getString(R.string.expansion_empty)
            setPadding(0, pad, 0, pad)
        }
        root.addView(emptyHint)
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)
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
     * A counter instead of a re-read at every input start as chords do: that is cheap for a few
     * dozen chords and grows with the table here.
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
                        // This row only: a trigger with several targets keeps the rest.
                        val keep = ExpansionRows.afterDelete(rows, row)
                        io.execute {
                            dao().assign(row.trigger, keep)
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
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            // Tells Kinetica this field holds one token, so it writes no automatic space here.
            privateImeOptions = EditorState.ONE_TOKEN_OPTION
            setText(existing?.trigger.orEmpty())
            // Shown under the field as it is typed, not in a toast after OK.
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val t = ExpansionRows.cleanTrigger(s?.toString().orEmpty())
                    error = if (t.length > MAX_TRIGGER_CHARS) getString(R.string.expansion_bad_trigger) else null
                }

                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit

                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            })
        }
        // What the expansion does, from ShortcutKinds, shared with the chord and edge-swipe editors
        // (#19: text, Ctrl + a key, or an action), minus the actions an expansion must not fire.
        val kinds = ShortcutKinds.expansions
        val (wasKind, wasField) = ShortcutKinds.decode(existing?.target)
        val target = EditText(this).apply {
            // Multi-line: a target may be a bullet block.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(wasField)
        }
        fun showFieldFor(kind: ShortcutKind) {
            target.visibility = if (ShortcutKinds.usesField(kind)) View.VISIBLE else View.GONE
            target.hint = getString(ShortcutKinds.hintRes(kind, R.string.expansion_target_hint))
        }
        showFieldFor(wasKind)
        val kindSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ExpansionSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                kinds.map { getString(ShortcutKinds.labelRes(it)) },
            )
            setSelection(kinds.indexOf(wasKind).coerceAtLeast(0))
        }
        kindSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                showFieldFor(kinds[pos])
                target.error = null
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
            if (existing == null) {
                addView(TextView(context).apply { text = getString(R.string.expansion_more_targets_note) })
            }
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
                val t = ExpansionRows.cleanTrigger(trigger.text.toString())
                val kind = kinds[kindSpinner.selectedItemPosition]
                if (!ExpansionRows.isValidTrigger(t, MAX_TRIGGER_CHARS)) {
                    trigger.error = getString(R.string.expansion_bad_trigger)
                    trigger.requestFocus()
                    return@setOnClickListener
                }
                val v = ShortcutKinds.encode(kind, target.text.toString())
                if (v == null) {
                    if (kind == ShortcutKind.CtrlKey) target.error = getString(R.string.combo_key_unsendable)
                    return@setOnClickListener
                }
                dialog.dismiss()
                // A new row on an existing trigger adds a target (#19); an edit touches its own
                // row; a changed trigger moves that one target.
                val writes = ExpansionRows.afterSave(rows, existing, t, v)
                io.execute {
                    for ((trig, targets) in writes) dao().assign(trig, targets)
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
