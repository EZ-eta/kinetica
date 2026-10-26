package com.kinetica.keyboard.ime

import android.util.Log
import com.kinetica.keyboard.data.KineticaDb
import com.kinetica.keyboard.engine.DecodeTrace
import com.kinetica.keyboard.keys.ChordKey
import com.kinetica.keyboard.settings.ActionLabels
import com.kinetica.keyboard.settings.ChordDefaults
import com.kinetica.keyboard.settings.ExpansionRows
import com.kinetica.keyboard.ui.SuggestionBarView

/**
 * Text expansions and chords: the tables mirrored from Room, expandify at the cursor, and the bar's
 * offer when one trigger has several targets (#19).
 */
internal class Expansions(private val ime: KineticaIME) {

    // (trigger, key character) -> expansion, mirrored from chord_shortcuts. Read on the UI
    // thread at pointer-down; refreshed on every input start so edits made in
    // settings apply as soon as the keyboard regains focus.
    @Volatile
    var chordMap: Map<ChordKey, String> = emptyMap()
        private set

    // trigger -> targets, mirrored from `expansions`. Read on the main thread when the
    // expandify action fires. Unlike chordMap it is not re-read at every input start: the
    // table may hold hundreds of rows, so it reloads on Prefs.EXPANSION_GENERATION.
    @Volatile
    private var table: ExpansionTable = ExpansionTable.EMPTY

    /**
     * Several targets for one trigger, offered on the bar until one is picked (#19).
     * [trigger] is what was typed, [stored] the trigger it matched, which differ in case only.
     */
    class ExpansionChoice(
        val trigger: String,
        val stored: String,
        val targets: List<String>,
        val shown: List<String>,
    )

    var choice: ExpansionChoice? = null

    /** Mirrors the expansion table into memory, on the database thread. */
    fun reloadExpansions() {
        ime.dbExecutor.execute {
            val rows = try {
                KineticaDb.get(ime).expansions().all()
            } catch (e: RuntimeException) {
                Log.w(KineticaIME.TAG, "expansion table unavailable", e)
                emptyList()
            }
            // Every target, in position order: a trigger with several offers them on the bar.
            table = ExpansionTable(
                rows.filter { it.trigger.isNotEmpty() }
                    .groupBy { it.trigger }
                    .mapValues { (_, r) -> r.sortedBy { it.position }.map { it.target } },
            )
        }
    }

    fun reloadChords() {
        ime.dbExecutor.execute {
            val rows = try {
                // First, so the old reserved keys are rows before the map is built.
                ChordDefaults.applyTo(ime)
                KineticaDb.get(ime).chordShortcuts().all()
            } catch (e: RuntimeException) {
                Log.w(KineticaIME.TAG, "chord table unavailable", e)
                emptyList()
            }
            val map = HashMap<ChordKey, String>(rows.size * 2)
            for (row in rows) {
                val key = ChordKey.decode(row.chord) ?: continue
                if (row.expansion.isNotEmpty()) map[key] = row.expansion
            }
            chordMap = map
        }
    }

    /**
     * Replaces the trigger at the cursor with its stored expansion (expandify).
     *
     * The word in progress is settled first and the trigger is read from the editor, so the
     * whole span comes from one read. A target may itself be a trigger, so firing
     * again continues a chain, and a chain back to its start is a loop; neither needs code here.
     */
    fun expandifyAtCursor() {
        ime.autospace.cancel()
        ime.commit.finalizePendingWord()
        val before = ime.ich.textBeforeCursor(MAX_TRIGGER_CHARS + 2) ?: ""
        val found = triggerAtCursor(before, MAX_TRIGGER_CHARS)
        val table = table
        val stored = table.storedFor(found.trigger, ime.config.triggerCase)
        val targets = stored?.let { table.byTrigger[it] }
        if (found.trigger.isEmpty() || stored == null || targets.isNullOrEmpty()) {
            // Nothing is typed for a miss: an unset trigger is the commonest case while the
            // user learns the feature, and inserting something is the worse answer.
            DecodeTrace.log { "  expandify miss trigger=${found.trigger}" }
            ime.views.vibrateForKeyPress()
            return
        }
        if (targets.size > 1) {
            offerExpansions(found.trigger, stored, targets)
            return
        }
        applyExpansion(found, before, targets[0], stored)
    }

    /** [target] as it would be written for [typed]: carried case on text, actions untouched. */
    private fun casedTarget(typed: String, stored: String, target: String): String =
        if (expansionEffect(target) is ExpansionEffect.Text) carryCase(typed, stored, target, ime.config.triggerCase) else target

    /**
     * The targets of one trigger, on the bar, for a tap to choose (#19). The
     * trigger stays written until then, so a choice left unpicked costs nothing.
     */
    private fun offerExpansions(trigger: String, stored: String, targets: List<String>) {
        val shown = ExpansionRows.distinctLabels(
            targets.map { t ->
                ExpansionRows.shown(casedTarget(trigger, stored, t), EXPANSION_PICK_CHARS) { ime.getString(ActionLabels.labelRes(it)) }
            },
        )
        choice = ExpansionChoice(trigger, stored, targets, shown)
        ime.suggestionBar?.setSuggestions(shown.map { SuggestionBarView.Suggestion(it, 0) })
        DecodeTrace.log { "  expandify offer trigger=$trigger n=${targets.size}" }
    }

    /** A pick from [offerExpansions]: applied only while the editor still ends in the trigger. */
    fun pickExpansion(choice: ExpansionChoice, index: Int) {
        val before = ime.ich.textBeforeCursor(MAX_TRIGGER_CHARS + 2) ?: ""
        val found = triggerAtCursor(before, MAX_TRIGGER_CHARS)
        ime.suggestionBar?.clearSuggestions()
        if (found.trigger != choice.trigger) {
            DecodeTrace.log { "  expandify pick refused trigger=${choice.trigger}" }
            return
        }
        applyExpansion(found, before, choice.targets[index], choice.stored)
    }

    /**
     * Replaces the trigger [found] in [before] with [target], or runs it when it is an action.
     * [stored] is the trigger [target] belongs to, which [found] may match in another case.
     */
    private fun applyExpansion(found: TriggerSpan, before: CharSequence, target: String, stored: String) {
        val text = when (val effect = expansionEffect(target)) {
            is ExpansionEffect.Refused -> {
                // Refused before anything is deleted, so the trigger is still there to fix.
                DecodeTrace.log { "  expandify refused trigger=${found.trigger} why=${effect.why}" }
                ime.views.vibrateForKeyPress()
                return
            }
            is ExpansionEffect.Action -> {
                // The trigger and its space go, then the action runs where the trigger was.
                ime.forgetCommitMarks()
                ime.ich.deleteBeforeCursor(found.span)
                ime.expectAfter(found.span, 0)
                DecodeTrace.log {
                    "  expandify trigger=${found.trigger} span=${found.span} action=${effect.action.name}"
                }
                ime.commit.abandonWord()
                ime.autospace.forget()
                ime.commands.performIfAction(effect.action.output)
                ime.updateAutoShift()
                return
            }
            is ExpansionEffect.Combo -> {
                ime.forgetCommitMarks()
                ime.ich.deleteBeforeCursor(found.span)
                ime.expectAfter(found.span, 0)
                DecodeTrace.log {
                    "  expandify trigger=${found.trigger} span=${found.span} combo=${effect.combo.encode()}"
                }
                ime.commit.abandonWord()
                ime.autospace.forget()
                ime.commands.sendKeyCombo(effect.combo)
                ime.updateAutoShift()
                return
            }
            is ExpansionEffect.Text ->
                expansionForField(carryCase(found.trigger, stored, effect.text, ime.config.triggerCase), ime.editorState.multiline)
        }
        val tail = before.subSequence(
            before.length - found.span + found.trigger.length, before.length,
        )
        ime.forgetCommitMarks()
        ime.ich.replaceBeforeCursor(found.span, text + tail)
        ime.expectAfter(found.span, text.length + tail.length)
        DecodeTrace.log {
            "  expandify trigger=${found.trigger} span=${found.span} len=${text.length}"
        }
        // The expansion is finished text, not a word in progress: nothing may autocorrect
        // it, autospace after it or learn it.
        ime.commit.abandonWord()
        ime.autospace.forget()
        ime.updateAutoShift()
    }

    private companion object {
        /** Characters of an expansion target shown on the bar when several are offered. */
        const val EXPANSION_PICK_CHARS = 24
    }
}
