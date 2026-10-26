package com.kinetica.keyboard.ime

import com.kinetica.keyboard.keys.EditorAction
import com.kinetica.keyboard.keys.KeyCombo

/**
 * The expandify trigger sitting at the cursor, and how many characters it occupies there.
 *
 * Its own walk, not [trailingLetterRun], because a trigger is not a word: `.`, `^^` and
 * `(-.-)'` have no letters, so the only boundary is whitespace.
 *
 * One trailing space is skipped and counted in [span], since the autospace has usually
 * written one; the caller puts it back. [span] is measured from the text just handed in,
 * never remembered, so it cannot drift from the text.
 */
internal fun triggerAtCursor(before: CharSequence, maxLen: Int): TriggerSpan {
    var end = before.length
    // At most one, so `word\n\n` or a run of spaces is a boundary and not a licence to
    // reach back over it for something the user has finished with.
    if (end > 0 && before[end - 1] == ' ') end--
    var start = end
    while (start > 0 && end - start < maxLen && !before[start - 1].isWhitespace()) start--
    val trigger = before.subSequence(start, end).toString()
    if (trigger.isEmpty()) return TriggerSpan("", 0)
    return TriggerSpan(trigger, before.length - start)
}

/** [trigger] and the characters it occupies before the cursor, trailing space included. */
internal data class TriggerSpan(val trigger: String, val span: Int)

/**
 * Longest expandify trigger, shared by the walk and by the editor that validates one.
 *
 * Generous against the reporter's own, which run from one character to six, because a
 * trigger may also be a whole keyword standing in for a link. Cheap either way: it bounds
 * one editor read.
 */
const val MAX_TRIGGER_CHARS = 32

/**
 * A target written into a field that holds one line.
 *
 * `commitText` passes a newline straight through, and only the enter key asks whether
 * newlines are allowed. A single-line editor's handling of one is unpredictable: a framework
 * EditText filters it out, others keep it and break their own layout.
 */
internal fun expansionForField(target: String, multiline: Boolean): String =
    if (multiline) target else target.replace('\n', ' ').replace('\r', ' ')

/** What firing an expansion does: write text, run an action, or refuse. */
internal sealed interface ExpansionEffect {
    data class Text(val text: String) : ExpansionEffect
    data class Action(val action: EditorAction) : ExpansionEffect
    data class Combo(val combo: KeyCombo) : ExpansionEffect
    data class Refused(val why: String) : ExpansionEffect
}

/**
 * Reads an expansion's target (#19: "replace the trigger with text, or do an action").
 *
 * A target in the `action:` form runs that action, as chords and edge swipes already do.
 * [EditorAction.NOT_EXPANSION_TARGETS] and a misspelled action are refused, and refused
 * before the caller deletes anything: a typo in a stored target must not cost the trigger.
 * Anything else is text.
 */
internal fun expansionEffect(target: String): ExpansionEffect {
    // A key combination is a target, as it is for chords and edge swipes.
    KeyCombo.parse(target)?.let { return ExpansionEffect.Combo(it) }
    val action = EditorAction.of(target)
    return when {
        action != null && action in EditorAction.NOT_EXPANSION_TARGETS -> ExpansionEffect.Refused(action.name)
        action != null -> ExpansionEffect.Action(action)
        EditorAction.isUnknownAction(target) -> ExpansionEffect.Refused("unknown")
        else -> ExpansionEffect.Text(target)
    }
}
