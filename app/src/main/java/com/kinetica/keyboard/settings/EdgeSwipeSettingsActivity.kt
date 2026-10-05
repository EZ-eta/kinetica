package com.kinetica.keyboard.settings

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
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
import com.kinetica.keyboard.keys.KeyCombo

/**
 * Edge-swipe shortcut management: each binding is (trigger key, direction, output), where the
 * output is text, a key combination, an action or the emoji picker. The binding set persists as
 * one JSON preference; the IME rebuilds its config on any preference change, so edits apply live.
 */
class EdgeSwipeSettingsActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var hint: TextView

    // The key is typed, so any key of any board can carry one, the symbol pages' included; the
    // keys that type nothing are buttons. The spacebar is absent: its slide owns cursor movement.
    // Letters accept all four directions, but left/right on a letter competes with short typing
    // swipes, so the defaults use only up and down.
    private val specialLabels = mapOf(
        "enter" to R.string.shortcut_key_enter,
        "backspace" to R.string.shortcut_key_backspace,
        "shift" to R.string.shortcut_key_shift,
        "mode" to R.string.shortcut_key_mode,
    )

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

    private fun directionName(d: EdgeSwipeBinding.Direction): Int = when (d) {
        EdgeSwipeBinding.Direction.UP -> R.string.edge_swipe_dir_up
        EdgeSwipeBinding.Direction.DOWN -> R.string.edge_swipe_dir_down
        EdgeSwipeBinding.Direction.LEFT -> R.string.edge_swipe_dir_left
        EdgeSwipeBinding.Direction.RIGHT -> R.string.edge_swipe_dir_right
    }

    /** A binding's output as its row shows it: a combination or action by name, text quoted. */
    private fun actionLabel(output: String): String = when {
        KeyCombo.parse(output) != null -> KeyCombo.parse(output)!!.label()
        output == EdgeSwipeBindings.ACTION_EMOJI || EditorAction.of(output) != null ->
            getString(ShortcutKinds.labelRes(ShortcutKinds.decode(output).first))
        // Newlines are legal in an inserted string and would make one row as tall as the
        // text it holds, pushing every other binding off the screen.
        else -> "\"${output.replace("\n", " ").replace("\r", " ")}\""
    }

    /** A binding's key as the editor names it: its character, or a special key's name. */
    private fun keyName(keyId: String): String =
        specialLabels[keyId]?.let { getString(it) } ?: EdgeSwipeBindings.typedKeyFor(keyId) ?: keyId

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
            // A row is a two-line block when it shadows a built-in gesture: the binding itself,
            // then the note under it.
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
                        keyName(row.keyId), directionGlyph(row.direction), actionLabel(row.output),
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
            // Informs, never forbids: the binding still works, and wins over a gesture the user
            // may not realise they were using.
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

        // A key that types nothing is chosen by its button; typing in the field un-chooses it.
        var special: String? = existing?.keyId?.takeIf { it in EdgeSwipeBindings.SPECIAL_KEY_IDS }
        val keyField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            setText(existing?.keyId?.let { EdgeSwipeBindings.typedKeyFor(it) }.orEmpty())
        }
        fun showSpecial() {
            keyField.hint = special?.let { getString(specialLabels.getValue(it)) }
                ?: getString(R.string.shortcut_key_hint)
        }
        showSpecial()
        keyField.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (!s.isNullOrEmpty() && special != null) {
                        special = null
                        showSpecial()
                    }
                }
            },
        )
        val specials = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((id, label) in specialLabels) {
                addView(
                    Button(context).apply {
                        text = getString(label)
                        isAllCaps = false
                        setOnClickListener {
                            special = id
                            keyField.text = null
                            keyField.error = null
                            showSpecial()
                        }
                    },
                )
            }
        }
        val dirSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@EdgeSwipeSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                directions.map { directionGlyph(it) + "  " + getString(directionName(it)) },
            )
            setSelection(directions.indexOf(existing?.direction).coerceAtLeast(0))
        }
        // What the swipe does, from ShortcutKinds, shared with the chord and expansion editors and
        // derived from EditorAction.entries, so every action the keyboard can run is assignable.
        val kinds = ShortcutKinds.edgeSwipes
        val (wasKind, wasField) = ShortcutKinds.decode(existing?.output)
        val textField = EditText(this).apply {
            // Multi-line so a target with a newline in it can be typed, not only pasted.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(wasField)
        }
        fun showFieldFor(kind: ShortcutKind) {
            textField.visibility = if (ShortcutKinds.usesField(kind)) View.VISIBLE else View.GONE
            textField.hint = getString(ShortcutKinds.hintRes(kind, R.string.edge_swipe_text_hint))
        }
        showFieldFor(wasKind)
        val actionSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@EdgeSwipeSettingsActivity,
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
            addView(TextView(context).apply { text = getString(R.string.edge_swipe_key_label) })
            addView(keyField)
            addView(TextView(context).apply { text = getString(R.string.shortcut_key_or) })
            addView(HorizontalScrollView(context).apply { addView(specials) })
            addView(TextView(context).apply { text = getString(R.string.edge_swipe_direction_label) })
            addView(dirSpinner)
            addView(TextView(context).apply { text = getString(R.string.edge_swipe_action_label) })
            addView(actionSpinner)
            addView(textField)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.edge_swipe_add else R.string.chord_edit)
            .setView(content)
            // Set again once shown, so a refused key keeps the dialog open with its reason.
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val keyId = special ?: when (val r = ShortcutKeyInput.parse(keyField.text)) {
                ShortcutKeyInput.Result.Blank -> {
                    keyField.error = getString(R.string.shortcut_key_error_blank)
                    return@setOnClickListener
                }
                is ShortcutKeyInput.Result.TooMany -> {
                    keyField.error = getString(R.string.shortcut_key_error_many, r.typed)
                    return@setOnClickListener
                }
                is ShortcutKeyInput.Result.One -> EdgeSwipeBindings.keyIdFor(r.key, existing?.keyId)
            }
            val kind = kinds[actionSpinner.selectedItemPosition]
            val output = ShortcutKinds.encode(kind, textField.text.toString())
            if (output == null) {
                textField.error = getString(
                    if (kind == ShortcutKind.CtrlKey) R.string.combo_key_unsendable else R.string.edge_swipe_text_hint,
                )
                return@setOnClickListener
            }
            val direction = directions[dirSpinner.selectedItemPosition]
            // One action per (key, direction): assignment replaces any
            // previous binding, and editing frees the old slot.
            val next = current()
                .filterNot { it.keyId == keyId && it.direction == direction }
                .filterNot { existing != null && it.keyId == existing.keyId && it.direction == existing.direction }
            save(next + EdgeSwipeBinding(keyId, direction, output))
            dialog.dismiss()
        }
    }

}
