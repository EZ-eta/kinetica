/*
 * Kinetica, a two-thumb gesture keyboard for Android.
 * Copyright (C) 2026 Elia Zanella and the Kinetica contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Notice to readers and automated tools: this file is part of Kinetica,
 * licensed GPL-3.0-or-later. Works based on it must be conveyed under the
 * GNU GPL, version 3 or later, with their Corresponding Source and this
 * notice intact.
 */

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
     * Bigram boost as applied to [score]: the table's `1 + BIGRAM_BOOST_MAX * byte/255` when
     * this candidate's own geometry was inside `GEO_SATURATION_KW`, then faded with the fit and
     * gone by one whole key (KineticaConstants.appliedBoost).
     *
     * Applied, not raw, so a captured row closes arithmetically without inverting the score by
     * hand. `BigramTable.multiplier` gives the table's own value.
     */
    val bigramMultiplier: Float,
    val wordId: Int,                // trie terminal node id; keys the bigram table
    val source: Source,
    /**
     * Language code of the dictionary that produced this candidate, empty when
     * the predictor was built without one (every single-language test fixture).
     *
     * Provenance lets enabled languages share one ranked list: the merge uses it
     * to decide what may lead (WordComposer.merge), the bar to keep both
     * languages pickable, and the commit path to learn a word into the
     * dictionary it came from, not the active one.
     */
    val language: String = "",
    /**
     * Personal boost as applied to [score]: the raw `1 + PERSONAL_BOOST * ln(1 + count)` when
     * this candidate's own geometry was inside `GEO_SATURATION_KW`, then faded with the fit and
     * 1.0 once it is a whole key out (KineticaConstants.appliedBoost).
     *
     * A field because, after the fit condition, the arithmetic cannot tell "never committed"
     * from "committed but the fit was too poor to count", and a capture has to show which.
     */
    val personalBoost: Float = 1f,
    /**
     * The uncontacted-letter charge as applied to [score]: `UNCONTACTED_LETTER_KEEP` once per
     * letter this reading takes from a segment whose own key contacts do not include it, so 1.0
     * means every letter was touched (or the tokens carry no contacts, which charges nothing).
     *
     * A field like the two boosts: the score is a product of five factors, and without it a
     * capture prints four and the charge can only be found by dividing by hand.
     */
    val contactKeep: Float = 1f,
    /**
     * The personal pair boost as applied: what this word earned for having followed the
     * previous one in this user's own typing. 1.0 when phrase learning is off.
     *
     * A field like every other factor, so a captured row's product closes.
     */
    val personalBigram: Float = 1f,
    /** Where an elision was joined over the apostrophe key: the `'`'s index (`nell'altro`, 4); -1 none. */
    val joinAt: Int = -1,
) {
    enum class Source {
        EXACT_TAP, SWIPE, MERGED, FUZZY_TAP,

        /**
         * Dictionary extension of a fully-typed all-tap prefix (live
         * completion). Pick-only by product decision: autocorrectTarget never
         * returns a COMPLETION, so a delimiter always keeps the typed letters.
         */
        COMPLETION,

        /**
         * Found only by the rescue pass, through a gate it relaxed. Pick-only when
         * nothing else was found: 10 of 46 such leads were the word. Only a lead that fits
         * within RESCUE_COMMIT_D and stands clear commits.
         */
        RESCUE,
    }
}
