package com.kinetica.keyboard.settings

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AdapterView
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
import com.kinetica.keyboard.keys.EdgeSwipeBinding
import com.kinetica.keyboard.keys.EdgeSwipeBindings
import com.kinetica.keyboard.keys.EditorAction

/**
 * Edge-swipe shortcut management: each binding is (trigger key, direction,
 * action), where the action inserts text or opens the emoji picker. The
 * binding set persists as one JSON preference; the IME rebuilds its config on
 * any preference change, so edits apply live.
 */
class EdgeSwipeSettingsActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var hint: TextView

    // The spacebar is deliberately absent: its slide already owns cursor
    // movement. Letters accept all four directions, though left/right on a
    // letter competes with short typing swipes - the defaults use only
    // down (V/B/X) and up (backspace/enter) for that reason.
    private val keyIds =
        ('a'..'z').map { it.toString() } +
            listOf("comma", "period", "backspace", "enter", "shift", "mode")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad)
        }
        hint = TextView(this).apply {
            text = getString(R.string.edge_swipe_hint)
            setPadding(0, 0, 0, pad)
        }
        root.addView(hint)
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)
        root.addView(
            Button(this).apply {
                text = getString(R.string.edge_swipe_add)
                setOnClickListener { showEditor(existing = null) }
            },
        )
        root.addView(
            Button(this).apply {
                text = getString(R.string.edge_swipe_reset)
                setOnClickListener {
                    AlertDialog.Builder(this@EdgeSwipeSettingsActivity)
                        .setMessage(R.string.edge_swipe_reset_confirm)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            prefs().edit().remove(Prefs.EDGE_SWIPES).apply()
                            render()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            },
        )
        setContentView(ScrollView(this).apply { addView(root) })
        render()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun prefs() = PreferenceManager.getDefaultSharedPreferences(this)

    private fun current(): List<EdgeSwipeBinding> =
        EdgeSwipeBindings.parse(prefs().getString(Prefs.EDGE_SWIPES, null)).bindings

    private fun save(bindings: List<EdgeSwipeBinding>) {
        prefs().edit()
            .putString(Prefs.EDGE_SWIPES, EdgeSwipeBindings(bindings).serialize())
            .apply()
        render()
    }

    private fun directionGlyph(d: EdgeSwipeBinding.Direction): String = when (d) {
        EdgeSwipeBinding.Direction.UP -> "↑"
        EdgeSwipeBinding.Direction.DOWN -> "↓"
        EdgeSwipeBinding.Direction.LEFT -> "←"
        EdgeSwipeBinding.Direction.RIGHT -> "→"
    }

    /**
     * What a binding may do, in picker order: insert text, open the picker, or run one of
     * the editor actions.
     *
     * Derived from [EditorAction.entries] rather than hand-listed, for the reason the
     * chord editor's own list gives: a hand-written list silently omitted RETYPE there for
     * three releases. This one omitted **every** action, which is why `onEdgeSwipe` has
     * been able to run them since v1.0.2 and no user could ever assign one.
     */
    private val actionOutputs: List<String?> =
        listOf(null, EdgeSwipeBindings.ACTION_EMOJI) + EditorAction.entries.map { it.output }

    private fun actionLabel(output: String): String = when {
        output == EdgeSwipeBindings.ACTION_EMOJI || EditorAction.of(output) != null ->
            actionName(output)
        // Newlines are legal in an inserted string and would make one row as tall as the
        // text it holds, pushing every other binding off the screen.
        else -> "\"${output.replace("\n", " ").replace("\r", " ")}\""
    }

    private fun actionName(output: String?): String {
        val action = EditorAction.of(output.orEmpty())
        if (action != null) return getString(ActionLabels.labelRes(action))
        return if (output == EdgeSwipeBindings.ACTION_EMOJI) {
            getString(R.string.edge_swipe_action_emoji)
        } else {
            getString(R.string.edge_swipe_action_insert)
        }
    }

    /** Message for a binding that shadows a built-in gesture, or null. */
    private fun shadowNote(row: EdgeSwipeBinding): String? {
        val id = EdgeSwipeBindings.shadowedGesture(row.keyId, row.direction) ?: return null
        val what = getString(
            when (id) {
                EdgeSwipeBindings.SHADOWS_CURSOR_SLIDE -> R.string.edge_swipe_shadow_cursor
                EdgeSwipeBindings.SHADOWS_STAGED_DELETE -> R.string.edge_swipe_shadow_delete
                EdgeSwipeBindings.SHADOWS_LAYER_SLIDE -> R.string.edge_swipe_shadow_layer
                EdgeSwipeBindings.SHADOWS_ENTER_POPUP -> R.string.edge_swipe_shadow_enter
                else -> R.string.edge_swipe_shadow_typing
            },
        )
        return getString(R.string.edge_swipe_shadow_note, what)
    }

    private fun render() {
        listContainer.removeAllViews()
        val rows = current().sortedWith(compareBy({ it.keyId }, { it.direction }))
        hint.text = getString(
            if (rows.isEmpty()) R.string.edge_swipe_empty_hint else R.string.edge_swipe_hint,
        )
        val pad = (8 * resources.displayMetrics.density).toInt()
        for (row in rows) {
            // A row is a two-line block when it shadows a built-in gesture: the
            // binding itself, then the note under it. Same shape the chord editor
            // uses for its reserved-letter collision.
            val block = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pad, 0, pad)
            }
            line.addView(
                TextView(this).apply {
                    text = getString(
                        R.string.edge_swipe_row,
                        row.keyId, directionGlyph(row.direction), actionLabel(row.output),
                    )
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
                        save(current().filterNot { it.keyId == row.keyId && it.direction == row.direction })
                    }
                },
            )
            block.addView(line)
            // Informs, never forbids - the same call the chord editor makes: the
            // binding still works, it just wins over something the user may not
            // realise they were using.
            shadowNote(row)?.let { note ->
                block.addView(
                    TextView(this).apply {
                        text = note
                        textSize = 13f
                        setPadding(0, 0, 0, pad)
                    },
                )
            }
            listContainer.addView(block)
        }
    }

    private fun showEditor(existing: EdgeSwipeBinding?) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val directions = EdgeSwipeBinding.Direction.values()

        val keySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@EdgeSwipeSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                keyIds,
            )
            setSelection(keyIds.indexOf(existing?.keyId).coerceAtLeast(0))
        }
        val dirSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@EdgeSwipeSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                directions.map { directionGlyph(it) + "  " + it.name.lowercase() },
            )
            setSelection(directions.indexOf(existing?.direction).coerceAtLeast(0))
        }
        val actions = actionOutputs.map { actionName(it) }
        val reserved = existing?.output?.takeIf {
            it == EdgeSwipeBindings.ACTION_EMOJI || EditorAction.of(it) != null
        }
        val textField = EditText(this).apply {
            hint = getString(R.string.edge_swipe_text_hint)
            // Multi-line so a target with a newline in it can be typed rather than only
            // pasted; the expansion editor needs the same and for the same reason.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(if (reserved == null) existing?.output.orEmpty() else "")
            visibility = if (reserved == null) View.VISIBLE else View.GONE
        }
        val actionSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@EdgeSwipeSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                actions,
            )
            setSelection(actionOutputs.indexOf(reserved).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    textField.visibility =
                        if (actionOutputs[pos] == null) View.VISIBLE else View.GONE
                }

                override fun onNothingSelected(p: AdapterView<*>?) = Unit
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad)
            addView(TextView(context).apply { text = getString(R.string.edge_swipe_key_label) })
            addView(keySpinner)
            addView(TextView(context).apply { text = getString(R.string.edge_swipe_direction_label) })
            addView(dirSpinner)
            addView(TextView(context).apply { text = getString(R.string.edge_swipe_action_label) })
            addView(actionSpinner)
            addView(textField)
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.edge_swipe_add else R.string.chord_edit)
            .setView(content)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val output = actionOutputs[actionSpinner.selectedItemPosition]
                    ?: textField.text.toString()
                if (output.isEmpty()) return@setPositiveButton
                val keyId = keySpinner.selectedItem as String
                val direction = directions[dirSpinner.selectedItemPosition]
                // One action per (key, direction): assignment replaces any
                // previous binding, and editing frees the old slot.
                val next = current()
                    .filterNot { it.keyId == keyId && it.direction == direction }
                    .filterNot { existing != null && it.keyId == existing.keyId && it.direction == existing.direction }
                save(next + EdgeSwipeBinding(keyId, direction, output))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
