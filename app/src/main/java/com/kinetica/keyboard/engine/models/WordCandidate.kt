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
    /**
     * The uncontacted-letter charge AS APPLIED to [score]:
     * `UNCONTACTED_LETTER_KEEP` once per letter this reading takes from a segment
     * whose own key contacts do not include it, so 1.0 means every letter was
     * measurably touched (or the tokens carry no contacts at all, which charges
     * nothing).
     *
     * A field for the reason the two boosts are: without it the score is a product of
     * five factors and a capture prints four, so establishing that the charge fired at
     * all means dividing by hand - which is exactly what happened when the developer
     * reported `happens` decoding as `happiness`, and the division is where a reading
     * error gets introduced.
     */
    val contactKeep: Float = 1f,
    /**
     * The personal PAIR boost as applied: what this word earned for having followed the
     * previous one in this user's own typing. 1.0 when phrase learning is off.
     *
     * A field for the reason every other factor is one: the score is a product and a
     * captured row has to close by hand.
     */
    val personalBigram: Float = 1f,
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
