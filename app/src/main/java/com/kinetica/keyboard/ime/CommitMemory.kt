package com.kinetica.keyboard.ime

/**
 * The last committed word, for the two things that act on it.
 *
 * The correction strip is up only when the commit had an alternative, but the retype and the
 * re-case need the word either way. Read off the strip, both went dead after every
 * lone-candidate commit, 14 of 39 retype presses, and the rejected word kept its weight.
 */
class CommitMemory {

    /** The word the correction strip is showing, or null when no strip is up. */
    var stripWord: String? = null
        private set

    /** The last committed word, strip or not. The editor still has to hold it to be acted on. */
    var retypeWord: String? = null
        private set

    /** The gesture's other candidates, in rank order, and the language each came from. */
    var alternatives: List<String> = emptyList()
        private set
    var languages: Map<String, String> = emptyMap()
        private set

    fun onCommit(
        word: String,
        stripShown: Boolean,
        alternatives: List<String> = emptyList(),
        languages: Map<String, String> = emptyMap(),
    ) {
        retypeWord = word
        stripWord = if (stripShown) word else null
        this.alternatives = alternatives
        this.languages = languages
    }

    /**
     * What the bar offers once a retype has taken [rejected] back: the gesture's other
     * candidates, never the rejected word, at most [max]. A tap fixes a wrong decode
     * without re-swiping, and a new gesture still replaces the bar.
     */
    fun offerAfterRetype(rejected: String, max: Int): List<String> {
        val out = ArrayList<String>(max)
        for (w in alternatives) {
            if (out.size >= max) break
            if (w.equals(rejected, ignoreCase = true)) continue
            if (out.any { it.equals(w, ignoreCase = true) }) continue
            out.add(w)
        }
        return out
    }

    /** The commit was rewritten in place, by a correction pick or a re-case. */
    fun onReplaced(word: String) {
        retypeWord = word
        if (stripWord != null) stripWord = word
    }

    fun clear() {
        stripWord = null
        retypeWord = null
        alternatives = emptyList()
        languages = emptyMap()
    }
}
