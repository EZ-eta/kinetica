package com.kinetica.keyboard.settings

import android.annotation.SuppressLint
import android.content.Context
import androidx.preference.PreferenceManager
import com.kinetica.keyboard.data.KineticaDb
import com.kinetica.keyboard.keys.ChordKey
import com.kinetica.keyboard.keys.ChordTrigger
import com.kinetica.keyboard.keys.EditorAction

/**
 * The language switch and the peck-type toggle as ordinary chords, defaults the user can delete or
 * rebind (no reserved keys).
 *
 * They were two preferences that won over any chord on the same letter, converted once into rows:
 * a stored key keeps working on `?123`, a fresh install gets `l` and `p`, and a text chord the
 * reserved key shadowed moves to the spacebar, where it can fire.
 */
object ChordDefaults {

    const val LANGUAGE_KEY = 'l'
    const val PECK_KEY = 'p'

    /** What the old preferences said when switched off. */
    private const val OFF = "none"

    /**
     * The chords to write, in order, or null when there is nothing to convert. [rows] is the table
     * as stored keys to expansions; [storedLanguage] and [storedPeck] are the legacy preferences,
     * null when absent.
     */
    fun plan(
        storedLanguage: String?,
        storedPeck: String?,
        seeded: Boolean,
        rows: Map<String, String>,
    ): List<Pair<String, String>>? {
        if (seeded && storedLanguage == null && storedPeck == null) return null
        val out = ArrayList<Pair<String, String>>(3)
        val table = HashMap(rows)
        // The language key defaulted to `l` and won whenever it was set, so a key here is one that
        // fired: what it shadowed moves aside. `none` was a choice, since `l` was the default.
        val language = storedLanguage ?: if (seeded) null else LANGUAGE_KEY.toString()
        if (language != null && language != OFF) {
            place(language, EditorAction.NEXT_LANGUAGE, shadowed = true, table, out)
        }
        // Peck-type shipped off, and the old screen wrote `none` on being opened, so `none` says
        // nothing about the user: a fresh table gets `p` unless that key already does something,
        // which the reserved key never took from it.
        val peck = when {
            storedPeck != null && storedPeck != OFF -> storedPeck
            seeded -> null
            else -> PECK_KEY.toString()
        }
        if (peck != null) {
            val wasLive = storedPeck != null && storedPeck != OFF
            place(peck, EditorAction.TOGGLE_PECK_MODE, shadowed = wasLive, table, out)
        }
        return out
    }

    private fun place(
        value: String,
        action: EditorAction,
        shadowed: Boolean,
        table: MutableMap<String, String>,
        out: MutableList<Pair<String, String>>,
    ) {
        val key = value.singleOrNull()?.lowercaseChar() ?: return
        val mode = ChordKey(ChordTrigger.MODE, key).encode()
        val held = table[mode]
        when {
            held == null -> Unit
            held == action.output -> return
            // The row never fired while the reserved key held the letter, so it is not lost.
            shadowed && table[ChordKey(ChordTrigger.SPACE, key).encode()] == null -> {
                val space = ChordKey(ChordTrigger.SPACE, key).encode()
                out.add(space to held)
                table[space] = held
            }
            // A chord of the user's own that nothing shadowed keeps its key; the default is not set.
            else -> return
        }
        out.add(mode to action.output)
        table[mode] = action.output
    }

    /** Converts once, on the caller's background thread; again whenever old preferences reappear. */
    @SuppressLint("ApplySharedPref") // the next read must see the flag, on any thread
    fun applyTo(context: Context) = synchronized(this) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val stored = prefs.all
        val writes = plan(
            stored[Prefs.LANG_CYCLE_KEY] as? String,
            stored[Prefs.PECK_CHORD_KEY] as? String,
            prefs.getBoolean(Prefs.CHORD_DEFAULTS_SEEDED, false),
            KineticaDb.get(context).chordShortcuts().all().associate { it.chord to it.expansion },
        ) ?: return@synchronized
        val db = KineticaDb.get(context)
        db.runInTransaction { for ((chord, expansion) in writes) db.chordShortcuts().assign(chord, expansion) }
        prefs.edit()
            .remove(Prefs.LANG_CYCLE_KEY)
            .remove(Prefs.PECK_CHORD_KEY)
            .putBoolean(Prefs.CHORD_DEFAULTS_SEEDED, true)
            .commit()
    }
}
