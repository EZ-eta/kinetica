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
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.preference.PreferenceManager
import com.kinetica.keyboard.R
import com.kinetica.keyboard.data.ChordShortcut
import com.kinetica.keyboard.data.KineticaDb
import com.kinetica.keyboard.keys.ChordKey
import com.kinetica.keyboard.keys.ChordTrigger
import com.kinetica.keyboard.keys.EditorAction
import com.kinetica.keyboard.keys.KeyCombo
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Every chord setting on one screen: the two lead-ins, then the chords grouped by the key they are
 * held with, `?123` or the spacebar, with add, edit and delete.
 *
 * The language switch and peck-type are ordinary chords here, `l` and `p` by default
 * ([ChordDefaults]); there are no reserved keys. The keyboard re-reads the table on every input
 * start, so a change applies on the next focused field.
 */
class ChordSettingsActivity : AppCompatActivity() {

    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var listContainer: LinearLayout
    private lateinit var emptyHint: TextView
    private lateinit var sortButton: Button
    private var sort = ChordRows.Sort.KEY_ASC

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad)
        }
        root.addView(
            leadInRow(
                R.string.pref_chord_arm_title, R.string.pref_chord_arm_summary,
                Prefs.CHORD_ARM_MS, Prefs.DEFAULT_CHORD_ARM_MS, KeyboardConfig.CHORD_ARM_MAX_MS,
            ),
        )
        root.addView(
            leadInRow(
                R.string.pref_space_chord_arm_title, R.string.pref_space_chord_arm_summary,
                Prefs.SPACE_CHORD_ARM_MS, Prefs.DEFAULT_SPACE_CHORD_ARM_MS,
                KeyboardConfig.SPACE_CHORD_ARM_MAX_MS,
            ),
        )
        root.addView(
            TextView(this).apply {
                text = getString(R.string.chord_settings_title)
                setPadding(0, pad, 0, pad / 2)
            },
        )
        emptyHint = TextView(this).apply {
            text = getString(R.string.chord_empty_hint)
            setPadding(0, 0, 0, pad)
        }
        root.addView(emptyHint)
        sortButton = Button(this).apply {
            setOnClickListener {
                sort = ChordRows.next(sort)
                refresh()
            }
        }
        root.addView(sortButton)
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

    /**
     * How long a trigger must be held before a key tap counts as a chord. The spacebar has its own,
     * longer one: it is pressed every word, and a thumb is often still on it when the other taps.
     */
    private fun leadInRow(titleRes: Int, summaryRes: Int, prefKey: String, default: Int, max: Int): LinearLayout {
        val pad = (8 * resources.displayMetrics.density).toInt()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val value = prefs.getInt(prefKey, default).coerceIn(0, max)
        val readout = TextView(this).apply { text = getString(R.string.chord_lead_in_value, value) }
        val bar = SeekBar(this).apply {
            this.max = max
            progress = value
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    readout.text = getString(R.string.chord_lead_in_value, p)
                    if (fromUser) prefs.edit().putInt(prefKey, p).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad, 0, pad)
            addView(TextView(context).apply { setText(titleRes) })
            addView(
                TextView(context).apply {
                    setText(summaryRes)
                    textSize = 13f
                },
            )
            addView(readout)
            addView(bar)
        }
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
            // Before the read, so the old reserved keys show as the chords they now are.
            ChordDefaults.applyTo(this)
            val groups = ChordRows.grouped(dao().all(), sort)
            main.post { if (!isDestroyed) render(groups) }
        }
    }

    private fun render(groups: List<Pair<ChordTrigger, List<ChordShortcut>>>) {
        listContainer.removeAllViews()
        val count = groups.sumOf { it.second.size }
        emptyHint.text = getString(if (count == 0) R.string.chord_empty_hint else R.string.chord_list_hint)
        sortButton.text = getString(
            when (sort) {
                ChordRows.Sort.KEY_ASC -> R.string.chord_sort_key_asc
                ChordRows.Sort.KEY_DESC -> R.string.chord_sort_key_desc
                ChordRows.Sort.FUNCTION -> R.string.chord_sort_function
            },
        )
        // Nothing to order until there are at least two of them.
        sortButton.visibility = if (count < 2) View.GONE else View.VISIBLE
        val pad = (8 * resources.displayMetrics.density).toInt()
        for ((trigger, rows) in groups) {
            listContainer.addView(
                TextView(this).apply {
                    text = getString(groupTitle(trigger))
                    setPadding(0, pad * 2, 0, 0)
                },
            )
            for (row in rows) listContainer.addView(rowView(row, pad))
        }
    }

    private fun groupTitle(trigger: ChordTrigger): Int = when (trigger) {
        ChordTrigger.MODE -> R.string.chord_group_mode
        ChordTrigger.SPACE -> R.string.chord_group_space
    }

    private fun rowView(row: ChordShortcut, pad: Int): LinearLayout {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, pad, 0, pad)
        }
        val key = ChordRows.keyLabel(row)
        // An action by its name, never the stored `action:` string.
        val action = EditorAction.of(row.expansion)
        val combo = KeyCombo.parse(row.expansion)
        line.addView(
            TextView(this).apply {
                text = if (combo != null) {
                    getString(R.string.chord_row, key, combo.label())
                } else if (action != null) {
                    getString(R.string.chord_row, key, getString(ActionLabels.labelRes(action)))
                } else {
                    getString(R.string.chord_row_text, key, row.expansion)
                }
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
        return line
    }

    private fun showEditor(existing: ChordShortcut?) {
        io.execute {
            val taken = dao().all().mapNotNull { ChordKey.decode(it.chord) }.toSet()
            main.post { if (!isDestroyed) showEditorDialog(existing, taken) }
        }
    }

    private fun showEditorDialog(existing: ChordShortcut?, taken: Set<ChordKey>) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val was = existing?.let { ChordKey.decode(it.chord) }

        // The trigger first: it decides which keys are already taken.
        val triggers = ChordTrigger.entries
        val triggerSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ChordSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                triggers.map {
                    getString(
                        when (it) {
                            ChordTrigger.MODE -> R.string.chord_trigger_mode
                            ChordTrigger.SPACE -> R.string.chord_trigger_space
                        },
                    )
                },
            )
            setSelection(triggers.indexOf(was?.trigger ?: ChordTrigger.MODE))
        }
        // Typed, not picked: any key of any board, a symbol page's included.
        val keyField = EditText(this).apply {
            hint = getString(R.string.shortcut_key_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            setText(was?.key?.toString().orEmpty())
        }
        // What the chord does, from ShortcutKinds, which builds it from EditorAction.entries so a
        // new action cannot be missing.
        val kinds = ShortcutKinds.chords
        val (wasKind, wasField) = ShortcutKinds.decode(existing?.expansion)
        val expansion = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(wasField)
        }
        fun showFieldFor(kind: ShortcutKind) {
            expansion.visibility = if (ShortcutKinds.usesField(kind)) View.VISIBLE else View.GONE
            expansion.hint = getString(ShortcutKinds.hintRes(kind, R.string.chord_expansion_hint))
        }
        showFieldFor(wasKind)
        val kindSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ChordSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                kinds.map { getString(ShortcutKinds.labelRes(it)) },
            )
            setSelection(kinds.indexOf(wasKind).coerceAtLeast(0))
        }
        kindSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                showFieldFor(kinds[pos])
                expansion.error = null
            }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad)
            addView(TextView(context).apply { text = getString(R.string.chord_trigger_label) })
            addView(triggerSpinner)
            addView(TextView(context).apply { text = getString(R.string.chord_letter_label) })
            addView(keyField)
            addView(TextView(context).apply { text = getString(R.string.chord_kind_label) })
            addView(kindSpinner)
            addView(expansion)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.chord_add else R.string.chord_edit)
            .setView(content)
            // Set again once shown, so a refused key keeps the dialog open with its reason.
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val key = when (val r = ShortcutKeyInput.parse(keyField.text)) {
                ShortcutKeyInput.Result.Blank -> {
                    keyField.error = getString(R.string.shortcut_key_error_blank)
                    return@setOnClickListener
                }
                is ShortcutKeyInput.Result.TooMany -> {
                    keyField.error = getString(R.string.shortcut_key_error_many, r.typed)
                    return@setOnClickListener
                }
                is ShortcutKeyInput.Result.One -> r.key
            }
            val chord = ChordKey(triggers[triggerSpinner.selectedItemPosition], key)
            if (chord in taken && chord != was) {
                keyField.error = getString(R.string.chord_key_taken, key.toString())
                return@setOnClickListener
            }
            val kind = kinds[kindSpinner.selectedItemPosition]
            val text = ShortcutKinds.encode(kind, expansion.text.toString())
            if (text == null) {
                expansion.error = getString(
                    if (kind == ShortcutKind.CtrlKey) R.string.combo_key_unsendable else R.string.chord_expansion_hint,
                )
                return@setOnClickListener
            }
            io.execute {
                dao().replace(existing, chord.encode(), text)
                main.post { if (!isDestroyed) refresh() }
            }
            dialog.dismiss()
        }
    }

}
