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
import com.kinetica.keyboard.keys.EditorAction
import com.kinetica.keyboard.data.KineticaDb
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Every `?123` chord setting, on one screen: the two reserved keys, then the list of
 * letter -> expansion bindings with add/edit/delete.
 *
 * Chords are opt-in per letter; an empty list keeps the feature inert. The keyboard
 * re-reads the table on every input start, so changes apply on the next focused field
 * without restarting the IME.
 *
 * The two reserved keys were separate entries in the preference screen, which a user
 * reported as three places to look for one feature. They are shown here and still STORED
 * as the same two preferences: KeyboardConfig reads them unchanged, and a backup written
 * before this change restores into it.
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
            TextView(this).apply {
                text = getString(R.string.chord_reserved_heading)
                setPadding(0, 0, 0, pad / 2)
            },
        )
        root.addView(
            reservedKeyRow(
                R.string.pref_lang_cycle_key_title,
                R.string.pref_lang_cycle_key_summary,
                Prefs.LANG_CYCLE_KEY,
                Prefs.DEFAULT_LANG_CYCLE_KEY,
            ),
        )
        root.addView(
            reservedKeyRow(
                R.string.pref_peck_chord_key_title,
                R.string.pref_peck_chord_key_summary,
                Prefs.PECK_CHORD_KEY,
                Prefs.DEFAULT_PECK_CHORD_KEY,
            ),
        )
        root.addView(leadInRow())
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
     * One reserved-key setting: a label and a Spinner over the same entries the preference
     * screen used, writing the same string value back to the same key.
     *
     * Deliberately not a Room row. KeyboardConfig.chordLetterCode reads these two
     * preferences, the backup format carries them as preferences, and moving the storage
     * would have meant a migration for a change that is only about where the control sits.
     */
    /**
     * How long `?123` must be held before a letter tap counts as a chord.
     *
     * On this screen rather than in the preference list because everything about chords
     * belongs in one place, and because the number only means anything next to the chords it
     * gates. Same preference key as before, so a backup written earlier restores into it.
     *
     * 150 is the shipped default and was never a measured value: a user reported the wait as
     * a delay between the two presses, which is what made it a setting.
     */
    private fun leadInRow(): LinearLayout {
        val pad = (8 * resources.displayMetrics.density).toInt()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val value = prefs.getInt(Prefs.CHORD_ARM_MS, Prefs.DEFAULT_CHORD_ARM_MS)
            .coerceIn(0, CHORD_ARM_MAX_MS)
        val readout = TextView(this).apply { text = getString(R.string.chord_lead_in_value, value) }
        val bar = SeekBar(this).apply {
            max = CHORD_ARM_MAX_MS
            progress = value
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    readout.text = getString(R.string.chord_lead_in_value, p)
                    if (fromUser) prefs.edit().putInt(Prefs.CHORD_ARM_MS, p).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad, 0, pad)
            addView(TextView(context).apply { setText(R.string.pref_chord_arm_title) })
            addView(
                TextView(context).apply {
                    setText(R.string.pref_chord_arm_summary)
                    textSize = 13f
                },
            )
            addView(readout)
            addView(bar)
        }
    }

    private fun reservedKeyRow(
        titleRes: Int,
        summaryRes: Int,
        prefKey: String,
        default: String,
    ): LinearLayout {
        val pad = (8 * resources.displayMetrics.density).toInt()
        val values = resources.getStringArray(R.array.lang_cycle_key_values)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val current = prefs.getString(prefKey, default) ?: default
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ChordSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                resources.getStringArray(R.array.lang_cycle_key_entries),
            )
            setSelection(values.indexOf(current).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    prefs.edit().putString(prefKey, values[pos]).apply()
                }
                override fun onNothingSelected(p: AdapterView<*>?) = Unit
            }
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
            addView(spinner)
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
            val rows = ChordRows.sorted(dao().all(), sort)
            main.post { if (!isDestroyed) render(rows) }
        }
    }

    private fun render(rows: List<ChordShortcut>) {
        listContainer.removeAllViews()
        emptyHint.text = getString(
            if (rows.isEmpty()) R.string.chord_empty_hint else R.string.chord_list_hint,
        )
        sortButton.text = getString(
            when (sort) {
                ChordRows.Sort.KEY_ASC -> R.string.chord_sort_key_asc
                ChordRows.Sort.KEY_DESC -> R.string.chord_sort_key_desc
                ChordRows.Sort.FUNCTION -> R.string.chord_sort_function
            },
        )
        // Nothing to order until there are at least two of them.
        sortButton.visibility = if (rows.size < 2) View.GONE else View.VISIBLE
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
        // Built from EditorAction.entries rather than hand-listed, so a new action cannot
        // be added without appearing here. The hand-listed version silently omitted RETYPE
        // for three releases, which left its own KDoc's "two triggers for one
        // implementation" half true - the suggestion-bar button worked and the chord could
        // not be assigned. The `when` below is exhaustive, so the omission is now a
        // compile error instead of an invisible gap.
        val kinds = listOf<EditorAction?>(null) + EditorAction.entries
        val kindLabels = kinds.map { action ->
            when (action) {
                null -> getString(R.string.chord_kind_text)
                EditorAction.PASTE -> getString(R.string.chord_kind_paste)
                EditorAction.COPY -> getString(R.string.chord_kind_copy)
                EditorAction.CUT -> getString(R.string.chord_kind_cut)
                EditorAction.SELECT_ALL -> getString(R.string.chord_kind_select_all)
                EditorAction.RETYPE -> getString(R.string.chord_kind_retype)
            }
        }
        val kindSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ChordSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                kindLabels,
            )
            setSelection(kinds.indexOfFirst { it == existingAction }.coerceAtLeast(0))
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
                    if (kinds[pos] == null) View.VISIBLE else View.GONE
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
                val action = kinds[kindSpinner.selectedItemPosition]
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

    private companion object {
        /** Matches the bound KeyboardConfig coerces to; above this the guard stops helping. */
        const val CHORD_ARM_MAX_MS = 300
    }

}
