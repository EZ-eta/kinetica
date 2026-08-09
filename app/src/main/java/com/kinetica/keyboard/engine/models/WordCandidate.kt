package com.kinetica.keyboard.engine.models

/** A scored dictionary word explaining the current token sequence. */
data class WordCandidate(
    val word: String,
    // frequencyWeight * KineticaConstants.geometricTerm(dtwDistance)
    //   * bigramMultiplier * personalBoost
    val score: Float,
    val dtwDistance: Float,         // mean per-step DTW cost plus tap-substitution penalties
    val frequencyWeight: Float,
    /**
     * Bigram boost AS APPLIED to [score] - the table's
     * `1 + BIGRAM_BOOST_MAX * byte/255` when this candidate's own geometry was
     * inside `GEO_SATURATION_KW`, then faded with the fit and gone by one whole
     * key (KineticaConstants.appliedBoost).
     *
     * Applied rather than raw for the same reason as [personalBoost]: a captured
     * row must close arithmetically without inverting the score by hand, which
     * every earlier tuning pass had to do. Read `BigramTable.multiplier` directly if you need
     * the table's own value.
     */
    val bigramMultiplier: Float,
    val wordId: Int,                // trie terminal node id; keys the bigram table
    val source: Source,
    /**
     * Language code of the dictionary that produced this candidate, empty when
     * the predictor was built without one (every single-language test fixture).
     *
     * Provenance is what lets enabled languages share ONE ranked list instead of
     * one language's list replacing the other's wholesale: the merge needs it to
     * decide what may lead (WordComposer.merge), the bar needs it to keep both
     * languages pickable, and the commit path needs it to learn a word into the
     * dictionary it actually came from rather than into the active one
     * rather than into the active one.
     */
    val language: String = "",
    /**
     * Personal boost AS APPLIED to [score] - the raw
     * `1 + PERSONAL_BOOST * ln(1 + count)` when this candidate's own geometry
     * was inside `GEO_SATURATION_KW`, then faded with the fit and 1.0 once it
     * is a whole key out (KineticaConstants.appliedBoost).
     *
     * It is a field rather than something a trace reader derives because
     * deriving it by hand is error-prone, and after the fit condition the
     * arithmetic no longer distinguishes "never committed" from "committed but
     * the fit was too poor to count" - exactly the distinction a capture has to
     * show.
     */
    val personalBoost: Float = 1f,
) {
    enum class Source {
        EXACT_TAP, SWIPE, MERGED, FUZZY_TAP,

        /**
         * Dictionary extension of a fully-typed all-tap prefix (live
         * completion). Pick-only by product decision: autocorrectTarget never
         * returns a COMPLETION, so a delimiter always keeps the typed letters.
         */
        COMPLETION,
    }
}
