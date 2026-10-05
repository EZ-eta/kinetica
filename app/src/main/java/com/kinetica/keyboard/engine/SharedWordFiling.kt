package com.kinetica.keyboard.engine

/**
 * Which language a committed word is learned into when both resident lexicons hold it.
 *
 * Provenance cannot say: the merge keeps the active language's copy of a shared word, so English
 * typed with Italian active was learned into Italian word by word. The word's own frequency
 * usually can, `this` is 231 in English against 120 in Italian; where the two are close, `in`
 * 234 against 230, the last word only one lexicon holds decides. Over 139 hand-labelled shared
 * commits in two bilingual captures: 135 right, against 58 for provenance.
 */
class SharedWordFiling(private val margin: Int = KineticaConstants.SHARED_WORD_FREQ_MARGIN) {

    private var lastSingle: String? = null

    /**
     * The language to learn a commit into. [held] maps each resident language whose lexicon
     * holds the word to its frequency byte; [provenance] is where the decode said it came from,
     * and is the answer whenever the word is not shared.
     */
    fun languageFor(held: Map<String, Int>, provenance: String): String {
        if (held.size == 1) lastSingle = held.keys.first()
        if (held.size < 2) return provenance
        val ranked = held.entries.sortedByDescending { it.value }
        if (ranked[0].value - ranked[1].value > margin) return ranked[0].key
        return lastSingle?.takeIf { it in held } ?: provenance
    }
}
