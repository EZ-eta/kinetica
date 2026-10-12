package com.kinetica.keyboard.ime

import com.kinetica.keyboard.keys.WordCase

/**
 * How an expansion trigger's case is read: as written, ignored, or carried onto the text.
 *
 * Matched exactly, `andor` misses at a sentence start, where the shift writes `Andor`; the case
 * modes spare storing each expansion twice.
 */
enum class TriggerCase {
    EXACT, IGNORE, CARRY;

    companion object {
        fun fromPref(value: String?): TriggerCase = when (value) {
            "ignore" -> IGNORE
            "carry" -> CARRY
            else -> EXACT
        }
    }
}

/**
 * The expansion table: each stored trigger's targets, and a lowercase index for the case modes.
 * Where two stored triggers differ only in case the lowercase one is indexed, so `andor` and
 * `Andor` stored side by side keep `andor` as the general one.
 */
class ExpansionTable(val byTrigger: Map<String, List<String>>) {
    private val byLower: Map<String, String> = HashMap<String, String>().also { index ->
        for (stored in byTrigger.keys) {
            val key = stored.lowercase()
            val held = index[key]
            if (held == null || (stored == key && held != key)) index[key] = stored
        }
    }

    /**
     * The stored trigger [typed] fires, or null. An exact match first, so a row written for
     * `Andor` keeps its own target; the lowercase match only in the case modes.
     */
    fun storedFor(typed: String, mode: TriggerCase): String? {
        if (typed in byTrigger) return typed
        if (mode == TriggerCase.EXACT) return null
        return byLower[typed.lowercase()]
    }

    companion object {
        val EMPTY = ExpansionTable(emptyMap())
    }
}

/**
 * [target] in the case [typed] was written in, for [TriggerCase.CARRY]: all capitals give all
 * capitals, a capital first letter capitalizes the target's first letter and nothing else, so
 * `eg` gives `E.g.` and `NASA` inside a target survives. Unchanged when [typed] is the stored
 * trigger itself, has no letters (`^^`), or is lowercase: a target is never lowercased, which
 * would lose its units and names.
 */
internal fun carryCase(typed: String, stored: String, target: String, mode: TriggerCase): String {
    if (mode != TriggerCase.CARRY || typed == stored) return target
    if (typed.none { it.isLetter() }) return target
    // All capitals needs two letters to say so: `B4` at a sentence start is the shift's capital,
    // and `BEFORE` would shout it.
    val case = WordCase.of(typed).let { if (it == WordCase.UPPER && typed.count { c -> c.isLetter() } < 2) WordCase.TITLE else it }
    return when (case) {
        WordCase.LOWER -> target
        WordCase.UPPER -> target.uppercase()
        WordCase.TITLE -> {
            val i = target.indexOfFirst { it.isLetter() }
            if (i < 0) target else target.substring(0, i) + target[i].uppercaseChar() + target.substring(i + 1)
        }
    }
}
