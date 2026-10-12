package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.DictionaryLoader.LearnedCase
import com.kinetica.keyboard.engine.KineticaConstants
import com.kinetica.keyboard.keys.AutoCapitalization
import com.kinetica.keyboard.keys.WordCase

/** One commit's say on how a learned word is capitalized: up, down, or nothing. */
internal data class CaseVote(val vote: Int, val display: String?) {
    companion object {
        val NONE = CaseVote(0, null)
    }
}

/**
 * Whether [committed] says the user writes it with a capital (#28): `Dennis`, `GitHub`.
 *
 * Only a choice against the keyboard counts. Committing what it proposed, [proposal], says
 * nothing, or a learned capital would vote for itself on every commit and could never be
 * talked out of. So: up, with the capital to store, for a capital the user wrote over a
 * lowercase proposal; down for a lowercase word written over the proposed [learned] capital.
 *
 * Only mid-sentence: at a sentence start, or the start of a field, a capital is the shift's.
 * [before] is the text before the word, null when it could not be read. Neither a word written
 * in capitals, nor English `I`, nor a spelling the dictionary holds as written ([held]: German
 * `Haus`, `Sie` beside `sie`) is the user's capital.
 */
internal fun caseVote(
    committed: String,
    proposal: String?,
    held: Boolean,
    before: CharSequence?,
    learned: String?,
    lang: String,
): CaseVote {
    if (before == null || committed.length < 2 || startsNewSentence(before)) return CaseVote.NONE
    val lower = committed.lowercase()
    if (committed == lower) {
        return if (learned != null && proposal == learned) CaseVote(-1, null) else CaseVote.NONE
    }
    if (committed.count { it.isLetter() } > 1 && committed.none { it.isLowerCase() }) return CaseVote.NONE
    if (AutoCapitalization.forWord(lower, lang) == committed) return CaseVote.NONE
    if (held || committed == proposal) return CaseVote.NONE
    return CaseVote(1, committed)
}

/**
 * A learned capital after [case], or null when nothing changes. A new spelling contests a
 * settled one instead of replacing it, one vote down per commit: one shift slip, `MArk`, must
 * not take the lead from `Mark`. An unsettled one is replaced.
 */
internal fun nextCase(old: LearnedCase?, case: CaseVote): LearnedCase? {
    val low = KineticaConstants.CASE_VOTES_LOW
    val high = KineticaConstants.CASE_VOTES_HIGH
    return when {
        case.vote == 0 -> null
        case.vote < 0 -> old?.copy(votes = (old.votes - 1).coerceIn(low, high))
        case.display == null -> old?.copy(votes = (old.votes + 1).coerceIn(low, high))
        old == null -> LearnedCase(case.display, 1)
        old.display == case.display -> old.copy(votes = (old.votes + 1).coerceIn(low, high))
        settled(old) -> old.copy(votes = (old.votes - 1).coerceIn(low, high))
        else -> LearnedCase(case.display, 1)
    }
}

/** Whether a learned capital leads its word. */
internal fun settled(case: LearnedCase?): Boolean =
    case != null && case.votes >= KineticaConstants.LEARNED_CAPITAL_MIN_VOTES

/** Whether the capital the decode leads with differs between [old] and [new]. */
internal fun leadChanged(old: LearnedCase?, new: LearnedCase?): Boolean =
    settled(old) != settled(new) || (settled(new) && new?.display != old?.display)

/**
 * The learned capital a tapped [word] takes, or null: written all lowercase, and its capital
 * settled. A word typed with its own capital, or a capital still unsettled, keeps the letters.
 */
internal fun learnedCapital(word: String, learned: LearnedCase?): String? {
    if (!settled(learned) || learned == null) return null
    if (word != word.lowercase() || learned.display.lowercase() != word || learned.display == word) return null
    return learned.display
}
