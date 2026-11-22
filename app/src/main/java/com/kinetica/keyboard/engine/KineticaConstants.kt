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

package com.kinetica.keyboard.engine

/**
 * Tunable constants for the gesture and prediction core.
 *
 * All geometric values are in key-width units (kw): pixel distances divided by
 * the current key width. This keeps thresholds meaningful across densities,
 * keyboard heights, and layout modes. Time values are milliseconds.
 */
object KineticaConstants {
    // Tap vs swipe classification.
    const val TAP_MAX_MS = 150L
    const val TAP_MAX_DISP_DP = 12f

    /**
     * Drift a held chord trigger may make once its chord labels show, before it counts as
     * moved. Twice [TAP_MAX_DISP_DP], so a resting thumb keeps them; under the 30 dp `?123`
     * slide, so that slide still reads as one. Reasoned, not measured: a moved trigger was not
     * traced before this value.
     */
    const val CHORD_HOLD_WIGGLE_DP = 24f

    /**
     * A touch held still this long while the other thumb typed is a resting finger, dropped at
     * its lift instead of joining the word as a letter. Four times a typing tap's ~100 ms, and
     * past the 150 ms of [TAP_MAX_MS] that marks a deliberate press. Reasoned, not measured.
     */
    const val RESTING_FINGER_MIN_MS = 400L

    /**
     * The arc a swipe piece must carry before it has to spell two letters; a shorter piece, such
     * as a flick, spells one (`Matcher.buildSegment`).
     *
     * 1.8 is a peak: corpus top-1 is 149 at 1.5, 159 at 1.8 and 152 at 2.2, where two goldens
     * fail. 48 of 9 408 captured leads change, none to empty, nineteen of them `keys` no longer
     * losing to names the finger never touched (`kyra`, `jessy`). Equal to R_INNER_KW by
     * coincidence, not derivation.
     */
    const val MIN_SWIPE_ARC_KW = 1.8f

    // Path matching.
    const val RESAMPLE_N = 32
    const val DTW_BAND_R = 4
    const val DTW_ENDPOINT_WEIGHT = 2f

    // Candidate pruning. The endpoint radius covers the key and its neighbours; the inner radius
    // is looser because mid-gesture accuracy is lower.
    // Do not widen the endpoint radius: at 1.8 it turns 149 empty decodes into words but top-1
    // falls 246 -> 183 and five goldens fail, because the intended words are found and then
    // buried under new competitors.
    const val R_ENDPOINT_KW = 1.4f
    const val R_INNER_KW = 1.8f
    // How far the path must leave a key and come back for the return to count as a second visit
    // (Matcher.collectPasses). Leaving R_INNER_KW alone misses every out-and-back to an adjacent
    // key (1.0 kw), and `vedere` decoded empty on 2 of 3 real gestures.
    // 0.7 is the 1.0 kw hop minus 0.3 kw for resampling, which finds an apex only to within half
    // a step, and for a finger rounding its turns; at 2.3x DWELL_RADIUS_KW jitter cannot make a
    // pass. On the top 20k words per language it recovers 112 / 87 / 105 of 114 / 87 / 108
    // unspellable it / en / es words for +2.8% passes. 0.9 loses the clean `vedere` path; 0.5
    // recovers four more words but nearly doubles the added passes.
    const val PASS_SPLIT_PROMINENCE_KW = 0.7f
    // How far back along the path a segment's next letter may be found, in resample indices out
    // of RESAMPLE_N, so a fraction of the piece whatever its length. A segment's lastIdx starts
    // at -MONOTONE_SLACK, so this governs consecutive letters only.
    // `here` needs it: `r` is 1.0 kw from `e`, inside R_INNER_KW, so the path never leaves `e`'s
    // disc, `e` gets one pass at its closest approach, and the second `e` must be found before
    // `r`'s index. Corpus top-1 is 236 at 8 and 246 at 18, flat up to 32; 73 of 4 722 leads
    // change, none to empty, mostly `he` -> `here` and `the` -> `there`. No golden moves at any
    // value. p99 decode 1.20 -> 1.43 ms.
    // Lowering PASS_SPLIT_PROMINENCE_KW instead breaks
    // PassRunSplitTest.preciseShortReversalKeepsOnePassPerVisit at every value from 0.5 down.
    const val MONOTONE_SLACK = 18
    // The longest word learned, decoded or held in a trie, in letters. German compounds in
    // ordinary writing run into the high 30s. At 40, 203 learned words of 16 to 40 letters all lead
    // from taps and from pieces of up to 12 letters; at 24 none past 24 did. The trie's field holds
    // 63. The asset generator and DictionaryMerger keep their own 20.
    const val MAX_WORD_LEN = 40
    // Candidate budget per Search pass, counting words that reach the heap. It feeds the
    // root-fairness slicing in WordPredictor.descend: the nearest-start subtree gets half and the
    // other admissible starts share the rest, so a deep word behind a frequent sibling (`cuñado`
    // behind co-/con-) is not starved. 800 keeps the nearest share at the old whole budget of 400.
    // Counting words walked instead spent 499 of 533 units on `parlare`'s path on words the DTW
    // abandon rejected, and `parlare` itself, at d=0.000, was never reached.
    const val MAX_CANDIDATES = 800
    // Ceiling on search work: one unit per emit call, whether or not the word survives the
    // abandon prunes, so MAX_CANDIDATES counts candidates only.
    // 4000 is a runaway backstop, not a tuning knob: the `parlare` search exhausts the admissible
    // trie by itself after 853 attempts. Refused: an even share per sibling prefix starves deep
    // words (`computer`, `interessante`, `cuñado`), and a seed pass per second letter costs +16%
    // emits and fixes nothing, since a deep word needs breadth at every level.
    const val MAX_EMIT_ATTEMPTS = 4000
    // Decode heap size: two suggestion-bar pages of 5 zones. A larger K lowers the heap minimum
    // and weakens the DTW early-abandon budget, so re-check decodeLatencyIsBounded when it moves.
    const val TOP_K = 10

    // Ideal-path length band relative to the observed arc length L:
    // accepted words satisfy 0.5*L - 1 <= idealLen <= 1.5*L + 1 (kw).
    const val LEN_BAND_LO = 0.5f
    const val LEN_BAND_HI = 1.5f
    const val LEN_BAND_MARGIN_KW = 1.0f

    /**
     * A long one-piece stroke read as a word whose ideal path covers only part of it: the band's
     * lower edge as a charge. Below SHORT_READING_RATIO of the arc the reading keeps
     * (ratio / SHORT_READING_RATIO) ^ SHORT_READING_POWER; under SHORT_READING_ARC_KW nothing is
     * charged, since a short word drawn short sits under the ratio as often as not.
     * Words of 10+ letters in one stroke, replayed: first try 6 to 22 of 43 rebuilt through key
     * centres, 8 to 14 of 18 drawn from their own points, no row lost; 206 of 12 396 corpus
     * leads move, none to a shorter word. Targets drawn over 18 kw keep at least 0.87.
     */
    const val SHORT_READING_ARC_KW = 18f
    const val SHORT_READING_RATIO = 0.9f
    const val SHORT_READING_POWER = 2f

    /** Score factor for a reading of ideal length [idealKw] over a piece of [arcKw]. */
    fun shortReadingKeep(idealKw: Float, arcKw: Float): Float {
        if (arcKw < SHORT_READING_ARC_KW) return 1f
        val r = idealKw / arcKw / SHORT_READING_RATIO
        return if (r >= 1f) 1f else Math.pow(r.toDouble(), SHORT_READING_POWER.toDouble()).toFloat()
    }

    // Fuzzy tap fallback (adjacent-key typos) and transposition alternatives.
    // Radius: a tap on the next key lands past 1 kw on the phone's board; three traced
    // two-thumb rows need 1.01-1.03 and move no corpus lead. 1.25 loses a traced row.
    const val FUZZY_TAP_RADIUS_KW = 1.1f
    const val FUZZY_TAP_LAMBDA = 0.15f
    const val TRANSPOSE_PENALTY = 0.15f

    /**
     * Tap charge, in kw, for a skipped stray tap or an untapped letter. 0.5 fixes 1 035 of
     * 2 008 dropped taps and 1 764 of 2 008 stray ones and changes no captured commit or lead
     * but 19 empty decodes; 0.25 cost three autocorrections. 0.49 and 0.51 leave every captured lead and
     * top five as 0.5 does. Negative: off.
     */
    const val TAP_EDIT_PEN_KW = 0.5064f

    /** Fewest taps before one may be skipped or a letter added: a short word has too many neighbours. Reasoned, not swept. */
    const val TAP_EDIT_MIN_TAPS = 5

    /**
     * Trie nodes a list-prefix walk visits before it answers yes. Apostrophe subtrees are a few
     * dozen nodes (uk, the largest, 331 rows over every head); a wider one is the list's. Reasoned.
     */
    const val LIST_PREFIX_NODES = 4096

    /**
     * Travel, kw, over which a stroke going on past the apostrophe key measures its heading, and the
     * least travel from the key before the next word's first letter. Under a key: the turn onto that
     * letter is read across one key's width either side. Reasoned on the elision goldens, not swept.
     */
    const val LEAD_IN_WINDOW_KW = 0.5f

    /**
     * Heading change, degrees, that marks the first letter after the apostrophe key: the thumb
     * turns onto it from the travel. About 60 keeps the gentle bend of a letter passed on the way.
     * Reasoned on the elision goldens; 59.7 and 61.2 read every captured row and gate row alike.
     */
    const val LEAD_IN_TURN_DEG = 60.61f

    /**
     * Radius, kw, around the apostrophe key's centre inside which a stroke passes over it. The key is
     * 0.5 kw wide and `l`'s centre lies about 0.9 kw away, so the letter radius would give every stroke
     * through `l` a pass. Priced at 0.5 on goldens: en through the key 11 to 60 of 60, none past it moved.
     */
    const val APOSTROPHE_PASS_KW = 0.5f

    /**
     * Longest stroke, kw, lifted on the apostrophe key and read also as the key tapped: a touch aimed at the
     * 0.5 kw key lands on `l` beside it and slides on, 0.5 to 1.6 kw on the phone. On 304 real strokes through
     * the key 1.4 reads 3 more `peter's`, 1.7 reads 7, 2.0 and 2.5 no more; none lost. 1.666 and 1.734 read
     * the rows 1.7 reads. One key contact only: an overshoot onto the key crosses two.
     */
    const val APOSTROPHE_TOUCH_ARC_KW = 1.684f

    /**
     * Distance, kw, from the corner past the apostrophe key within which the first letter of the word
     * after an elision must have its key centre. Set by three goldens read off the travel from the key
     * (`d'danno`, `l'charles`, `un'saliva`).
     */
    const val ELISION_TAIL_TOL_KW = 0.7f

    /**
     * Letters the word after an elision may start with: Italian and French elide only before a vowel or a
     * silent `h` (`l'uomo`, `l'ho`, `l'homme`), French before `y` too (`j'y`). Folded, so `è` is `e`. On 304
     * real strokes through the key: 3 more read right, 6 fewer joins that are no word lead, none lost.
     */
    const val ELISION_TAIL_LETTERS = "aeiouhy"

    /**
     * Worst fit, kw, an elision join may have and still be offered: two words read in one stroke that fit this
     * poorly were not drawn (`m'ad` at 1.01). On 304 real strokes through the key 1.0 to 0.85 lose none and
     * 0.95 takes one more wrong join off the lead, as 0.9452 does; 0.8 loses `sull'erba`.
     */
    const val ELISION_JOIN_MAX_D_KW = 0.9466f

    /**
     * Score kept by `'s` after a list word the list holds no possessive of (`mexico's`), so a list word
     * wins a close call. On goldens through the key: 1.0 read 53 of 60 possessives and lost `he's` twice
     * to `her's`; 0.95 kept both and read 53; 0.9 reads 46 with a margin, 0.8989 and 0.9011 the same; 0.8
     * read 38.
     */
    const val POSSESSIVE_KEEP = 0.8997f

    /**
     * How far, in ms, the beam lets a keyed `'` and the other thumb's letters cross in time. A `'` read
     * where a stroke touched the key splits the word there: no letter after it in the word was typed
     * before it, and none before it after it. The hand-over's 800 ms let `mo'arca` read the left
     * thumb's `a r c`, tapped 750 ms before the right thumb reached the key. 197 to 203 read every gate row alike.
     */
    const val APOSTROPHE_STEP_BACK_MS = 203L

    /**
     * The passes' own bound for letters touched before a keyed `'`, ms: the beam's bound, 200 then, lost three real
     * elisions there (`nell'altro` twice, `sull'erba`), 400 two; 800 loses none and reads one more.
     */
    const val APOSTROPHE_PASS_STEP_BACK_MS = 800L

    // Live tap-typing completions (pick-only): once the exact all-anchor pass has consumed every
    // tapped letter, the search keeps descending and emits longer words as Source.COMPLETION.
    // Only the exact pass completes: completing fuzzy or transposed prefixes would dress typos up
    // as confident words.
    // Below 2 tapped letters a prefix matches too much of the dictionary to rank; one tap would
    // complete to thousands of words and bury the one-letter words.
    const val COMPLETION_MIN_PREFIX = 2
    // 8 extra letters on a 2-letter prefix reaches 10-letter words; longer completions are rarely
    // picked before the user types on, and subtree fan-out grows with every level
    // (MAX_CANDIDATES still bounds emission per pass). A learned word past it is walked on its
    // own, without the fan-out: one typed at least PERSONAL_MERGE_MIN_COUNT times completes at
    // any length.
    const val COMPLETION_MAX_EXTRA = 8
    // Additive dTotal per completed letter. Any positive value keeps a typed word ahead of its own
    // extensions; 0.25 is above a worst-case fuzzy substitution (FUZZY_TAP_LAMBDA * 1.1 kw = 0.165),
    // so an adjacent-key correction of similar frequency outranks a completion of the same length
    // and only much more frequent words complete past it.
    const val COMPLETION_PENALTY_PER_LETTER = 0.25f

    // Dual-stream merge ambiguity generators. Two thumbs starting closer than this are read in
    // either order. Two-letter words typed a thumb each start 78-110 ms apart; at 100 those
    // 100 ms or more apart keep their time order: scripted rows +4 -0 (`who`, `di`, `ma` x2),
    // 45 of 12 396 corpus leads move, 14 to the word kept, 1 away. At 90 and below a 93 ms
    // `ne` (a tap, then a press that drifted) reads `me`; 120 left `who` as `how`.
    const val ORDER_AMBIG_MS = 100L
    const val SPLIT_MARGIN_MS = 80L
    const val MIN_SPLIT_HALF_ARC_KW = 0.4999f
    // Head trim for the second half of a split swipe, two roles:
    //   1. Fallback trim when the resume is one straight leg to the end: the half starts this far
    //      past the cut, so the tapped letter is consumed once, by the anchor (swipe h-e-l-o +
    //      tap l gives hel+l+o, not hel+l+lo).
    //   2. Travel a distance peak must clear to count as the first resumed letter, so the rest
    //      cluster at the cut is never read as a letter. splitSwipe resumes at the first interior
    //      distance-from-cut peak when there is one, which lets that letter pass the segment
    //      start-letter gate, and falls back to this trim otherwise.
    // Device sample jitter, which synthetic fixtures lack, may move the effective peak threshold.
    const val SPLIT_HEAD_TRIM_KW = 0.6005f
    // Endpoint-trimmed resume (split variants V2/V3): a tap that lands before the intended letter
    // boundary leaves the second half several kw of repositioning travel (the reversal leg in
    // `quindi`), whose arc forces minLetters=2 and kills a single trailing letter. A no-turn
    // resume's letters are at its end, so these variants keep only the final approach: under
    // MIN_SWIPE_ARC_KW so one letter may close the half, and at least a key hop so DTW still sees
    // an approach direction.
    const val SPLIT_RESUME_TAIL_KW = 1.2f
    // Sequence budget for one buffer. A (tap, swipe) pair emits up to three split variants, so a
    // small cap can truncate the one carrying the word. Wrong variants find no words and cost
    // microseconds; the cap bounds worst-case decode work, so re-check decodeLatencyIsBounded
    // when it moves.
    // 14 is the bottom of a plateau: swipe+tap empty decodes read 23/405 at 12 and 20/405 from 14
    // to 24. Worst decode 1.32 ms.
    // Do not reallocate it per generator: generators fill in turn, and every reallocation tried
    // buys two or three multi-swipe buffers and loses four to seven swipes-only ones, whose
    // cross-swipe generator needs most of the budget.
    const val MAX_ALT_SEQUENCES = 14

    // Cross-stream boundaries one swipe may be cut at at once: 3 cuts = 4 pieces, enough for an
    // 8-letter word with letters alternating between thumbs. With one anchor per sequence a swipe
    // interrupted twice was cut once, and `cuando` could only spell c,u,n,a,d,o. Each cut
    // multiplies pieces and DTW work per candidate; the *LatencyIsBounded goldens gate a change.
    const val MAX_SPLIT_ANCHORS = 3

    /**
     * Cuts one search branch may hold open at once when the search places its own; distinct from
     * MAX_SPLIT_ANCHORS, which bounds what MergeAlternatives builds before anything knows the word.
     * A resumed tail can be cut too, so one swipe can give three pieces.
     *
     * Corpus top-1 is 236 at 1 and 247 at 2, 3 and 4: with one cut only one swipe per buffer can
     * be read in two pieces, and these words need both. 3 changes six leads instead of two, one of
     * them an empty decode turned into the proper noun `couwenberg`. Empty five-token decodes read
     * 81% at 2, 3 and 4 alike: admissibility stops them, not this bound. p99 decode 1.29 ms.
     */
    const val MAX_SEARCH_CUTS = 2

    /**
     * Where inside a held key a letter is timed when thumbs hand over: 0 at entry, 1 at exit.
     * Exit, because a thumb often reaches its next key before the other thumb has left the letter
     * that comes first: of 173 fully contacted two-thumb rows, exit puts 55 in order at 0 ms of
     * allowance and 111 at 50 ms, entry 53 and 90.
     */
    const val HANDOVER_CONTACT_FRACTION = 1.0f

    /**
     * A single sample step this long is traced as a pointer jump, diagnostic only. At about
     * 250 Hz a fast thumb moves under 0.1 kw per sample; a jump of this size means the pointer
     * changed finger or two touches merged. None occur in the captured corpus.
     */
    const val TELEPORT_STEP_KW = 1.5f

    /**
     * How far back in time the stream pass lets a hand-over between thumbs step, in ms. A
     * thumb often reaches its next letter before the other has left the one that comes
     * first. Corpus top-1 by allowance: 0 -> 277, 100 -> 286, 150 -> 299, 200 -> 315,
     * 300 -> 320; 200 is where the gain stops being mostly target words.
     */
    const val HANDOVER_ALLOWANCE_MS = 200L

    /**
     * The beam's hand-over past HANDOVER_ALLOWANCE_MS: charged instead of refused, up to this. A
     * thumb tapping beside a long stroke lands its letter well before the stroke reaches the one
     * before it. Traced two-thumb long words, first try: 39 of 69 refused, 43 at 400, 46 at 800,
     * flat past it. The stream pass keeps 200: 300 there loses rows.
     */
    const val HANDOVER_CHARGE_MAX_MS = 800L

    /**
     * Beam priority, in kw, per 100 ms a hand-over steps back past the allowance. 2.0 and 3.0
     * lose two traced gains; a flat charge for any step past it loses `improvvisamente`. Under
     * HANDOVER_KEEP_FLOOR, 1.0 held two traced `improvvisamente` behind the width at an early tap
     * though each outscored its lead: 0.9 to 0.9003 read them (+2 -0); 0.85 reads a third but drops a
     * corpus target from the list, 0.5 loses a corpus row. A reading pays at most
     * HANDOVER_CHARGE_CAP_KW in all.
     */
    const val HANDOVER_CHARGE_KW = 0.9002f

    /**
     * Score factor per 100 ms a hand-over steps back past the allowance. Without it `who` reads
     * `how`, its tap 300 ms early; 0.8 loses a hand row, 0.5 four traced gains.
     */
    const val HANDOVER_CHARGE_KEEP = 0.6002f

    /**
     * The least a reading's hand-overs keep of its score, together, however far back they step.
     * In a long two-thumb word a tap often lands 400-800 ms before the other thumb's stroke reaches
     * the letter before it, and the keep per 100 ms left such words 0.01-0.1 of their score. Traced
     * rows 547 to 575 (+28 -0), polyline 451 to 471 (+20 -0), corpus 432 to 433, hand rows and prose
     * kept words unchanged but one more kept; 0.6 on one hand-over loses a kept `praticamente`.
     */
    const val HANDOVER_KEEP_FLOOR = 0.5f

    /**
     * The most a reading's hand-overs add to its beam priority, together. Past HANDOVER_KEEP_FLOOR a far
     * step no longer costs score, but its charge still cut a reading that outscored its lead 2 to 1
     * (`improvvisamente`, a tap 768 ms back). Traced rows +3 -0, polyline +1 -0, corpus and hand rows
     * unchanged; 2.0 drops a corpus target from its list, 2.75 and up add no traced row.
     */
    const val HANDOVER_CHARGE_CAP_KW = 2.499f

    /** Hundreds of ms a hand-over steps back past HANDOVER_ALLOWANCE_MS, 0 inside it. */
    fun handoverExcess(backMs: Long): Float =
        if (backMs <= HANDOVER_ALLOWANCE_MS) 0f else (backMs - HANDOVER_ALLOWANCE_MS) / 100f

    /**
     * HANDOVER_CHARGE_KEEP ^ (n / 100) for a step n ms past the allowance, up to HANDOVER_CHARGE_MAX_MS, which
     * the beam never passes. Read per child state: 0.6 ns on the JVM against 19 ns for `Math.pow`. Every entry
     * is within one float ulp of the formula (HandoverKeepTableTest); past the table the formula runs.
     */
    private val HANDOVER_KEEPS = floatArrayOf(
        1.0f, 0.9949081f, 0.9898421f, 0.9848019f, 0.97978735f, 0.9747984f,
        0.9698348f, 0.9648965f, 0.9599833f, 0.9550952f, 0.9502319f, 0.9453934f,
        0.94057953f, 0.9357902f, 0.9310252f, 0.9262845f, 0.9215679f, 0.9168754f,
        0.91220677f, 0.90756184f, 0.90294063f, 0.89834297f, 0.89376867f, 0.8892177f,
        0.8846898f, 0.88018507f, 0.8757033f, 0.87124425f, 0.86680794f, 0.8623942f,
        0.85800296f, 0.8536341f, 0.84928745f, 0.84496295f, 0.8406605f, 0.83637995f,
        0.83212113f, 0.8278841f, 0.82366854f, 0.8194745f, 0.8153018f, 0.8111504f,
        0.80702f, 0.80291075f, 0.7988224f, 0.79475486f, 0.79070807f, 0.78668183f,
        0.7826761f, 0.77869076f, 0.77472574f, 0.7707809f, 0.7668562f, 0.76295143f,
        0.7590665f, 0.7552014f, 0.751356f, 0.74753016f, 0.7437238f, 0.7399368f,
        0.7361691f, 0.7324206f, 0.72869116f, 0.7249808f, 0.7212892f, 0.7176165f,
        0.71396244f, 0.71032697f, 0.70671004f, 0.7031115f, 0.6995314f, 0.6959694f,
        0.6924256f, 0.6888998f, 0.685392f, 0.68190205f, 0.67842984f, 0.67497534f,
        0.6715384f, 0.668119f, 0.664717f, 0.6613323f, 0.6579649f, 0.65461457f,
        0.65128136f, 0.6479651f, 0.64466566f, 0.6413831f, 0.63811725f, 0.63486797f,
        0.6316353f, 0.62841904f, 0.6252192f, 0.62203556f, 0.6188683f, 0.61571705f,
        0.6125819f, 0.6094627f, 0.6063593f, 0.6032718f, 0.6002f, 0.59714377f,
        0.5941032f, 0.5910781f, 0.58806837f, 0.585074f, 0.58209485f, 0.5791308f,
        0.57618195f, 0.5732481f, 0.5703292f, 0.56742513f, 0.5645358f, 0.56166124f,
        0.55880135f, 0.55595595f, 0.5531251f, 0.55030864f, 0.5475065f, 0.5447186f,
        0.541945f, 0.5391854f, 0.53643996f, 0.53370845f, 0.53099084f, 0.52828705f,
        0.5255971f, 0.5229208f, 0.5202581f, 0.517609f, 0.5149734f, 0.5123512f,
        0.5097423f, 0.5071468f, 0.5045644f, 0.5019952f, 0.4994391f, 0.496896f,
        0.49436584f, 0.4918486f, 0.48934415f, 0.48685244f, 0.48437342f, 0.48190704f,
        0.47945318f, 0.47701186f, 0.47458294f, 0.47216642f, 0.4697622f, 0.4673702f,
        0.4649904f, 0.4626227f, 0.46026707f, 0.45792344f, 0.45559174f, 0.4532719f,
        0.45096388f, 0.4486676f, 0.446383f, 0.44411007f, 0.4418487f, 0.43959883f,
        0.43736044f, 0.43513346f, 0.43291777f, 0.43071342f, 0.42852026f, 0.42633826f,
        0.4241674f, 0.42200753f, 0.41985872f, 0.41772082f, 0.41559383f, 0.41347766f,
        0.41137227f, 0.40927762f, 0.4071936f, 0.4051202f, 0.40305737f, 0.40100503f,
        0.39896315f, 0.39693168f, 0.3949105f, 0.39289966f, 0.39089906f, 0.38890862f,
        0.38692835f, 0.38495812f, 0.38299796f, 0.38104776f, 0.3791075f, 0.37717712f,
        0.37525657f, 0.3733458f, 0.37144473f, 0.36955336f, 0.36767164f, 0.3657995f,
        0.36393687f, 0.36208373f, 0.36024004f, 0.35840574f, 0.35658076f, 0.3547651f,
        0.35295865f, 0.35116142f, 0.34937334f, 0.34759435f, 0.34582442f, 0.34406352f,
        0.3423116f, 0.34056857f, 0.33883443f, 0.33710906f, 0.33539253f, 0.33368474f,
        0.33198565f, 0.3302952f, 0.32861337f, 0.32694012f, 0.32527536f, 0.3236191f,
        0.32197124f, 0.3203318f, 0.3187007f, 0.3170779f, 0.31546336f, 0.31385705f,
        0.31225893f, 0.31066895f, 0.30908704f, 0.30751318f, 0.30594736f, 0.3043895f,
        0.30283958f, 0.30129755f, 0.29976338f, 0.298237f, 0.29671836f, 0.2952075f,
        0.29370433f, 0.29220882f, 0.2907209f, 0.2892406f, 0.2877678f, 0.2863025f,
        0.2848447f, 0.28339428f, 0.28195128f, 0.28051558f, 0.27908725f, 0.27766615f,
        0.2762523f, 0.27484563f, 0.27344614f, 0.27205378f, 0.2706685f, 0.2692903f,
        0.2679191f, 0.26655486f, 0.2651976f, 0.26384723f, 0.26250374f, 0.26116708f,
        0.25983724f, 0.25851417f, 0.25719783f, 0.25588822f, 0.25458524f, 0.25328892f,
        0.2519992f, 0.25071603f, 0.24943942f, 0.24816929f, 0.24690564f, 0.24564841f,
        0.2443976f, 0.24315314f, 0.24191503f, 0.24068323f, 0.23945768f, 0.2382384f,
        0.2370253f, 0.23581839f, 0.23461762f, 0.23342296f, 0.2322344f, 0.23105188f,
        0.22987536f, 0.22870485f, 0.22754031f, 0.22638169f, 0.22522898f, 0.22408214f,
        0.22294113f, 0.22180593f, 0.22067651f, 0.21955284f, 0.21843491f, 0.21732266f,
        0.21621607f, 0.21511512f, 0.21401978f, 0.21293f, 0.21184579f, 0.21076708f,
        0.20969388f, 0.20862614f, 0.20756382f, 0.20650692f, 0.20545541f, 0.20440926f,
        0.20336843f, 0.20233285f, 0.2013026f, 0.20027758f, 0.19925779f, 0.19824319f,
        0.19723375f, 0.19622946f, 0.19523028f, 0.19423617f, 0.19324714f, 0.19226314f,
        0.19128416f, 0.19031015f, 0.18934111f, 0.18837701f, 0.1874178f, 0.18646349f,
        0.18551403f, 0.18456942f, 0.1836296f, 0.18269458f, 0.18176432f, 0.1808388f,
        0.17991798f, 0.17900185f, 0.17809036f, 0.17718354f, 0.17628133f, 0.17538373f,
        0.17449069f, 0.17360221f, 0.17271824f, 0.17183878f, 0.17096378f, 0.17009325f,
        0.16922715f, 0.16836546f, 0.16750816f, 0.16665523f, 0.16580662f, 0.16496235f,
        0.16412239f, 0.16328669f, 0.16245525f, 0.16162804f, 0.16080505f, 0.15998624f,
        0.1591716f, 0.1583611f, 0.15755475f, 0.15675248f, 0.1559543f, 0.1551602f,
        0.15437014f, 0.15358411f, 0.15280207f, 0.15202402f, 0.15124992f, 0.15047976f,
        0.14971353f, 0.1489512f, 0.14819276f, 0.14743818f, 0.14668743f, 0.14594051f,
        0.1451974f, 0.14445807f, 0.1437225f, 0.14299068f, 0.14226258f, 0.1415382f,
        0.1408175f, 0.14010046f, 0.13938709f, 0.13867734f, 0.1379712f, 0.13726865f,
        0.1365697f, 0.13587429f, 0.13518244f, 0.1344941f, 0.13380927f, 0.13312791f,
        0.13245004f, 0.13177562f, 0.13110463f, 0.13043706f, 0.12977289f, 0.12911208f,
        0.12845467f, 0.12780057f, 0.12714984f, 0.12650238f, 0.12585826f, 0.1252174f,
        0.12457981f, 0.123945445f, 0.123314336f, 0.122686416f, 0.12206172f, 0.12144018f,
        0.12082183f, 0.12020661f, 0.11959454f, 0.118985556f, 0.11837971f, 0.117776915f,
        0.11717722f, 0.11658055f, 0.11598695f, 0.115396336f, 0.11480877f, 0.11422416f,
        0.11364252f, 0.11306388f, 0.11248815f, 0.11191539f, 0.11134551f, 0.11077856f,
        0.11021447f, 0.10965329f, 0.109094925f, 0.10853944f, 0.107986756f, 0.10743691f,
        0.10688984f, 0.10634557f, 0.105804056f, 0.10526533f, 0.10472931f, 0.10419606f,
        0.103665486f, 0.10313764f, 0.10261246f, 0.10208998f, 0.10157014f, 0.10105296f,
        0.100538395f, 0.10002645f, 0.09951714f, 0.09901039f, 0.09850625f, 0.098004654f,
        0.09750564f, 0.09700914f, 0.096515186f, 0.09602372f, 0.095534794f, 0.09504833f,
        0.09456436f, 0.09408284f, 0.09360379f, 0.093127154f, 0.09265297f, 0.092181176f,
        0.09171181f, 0.09124481f, 0.09078021f, 0.09031796f, 0.08985808f, 0.089400515f,
        0.08894531f, 0.08849239f, 0.08804179f, 0.087593496f, 0.08714747f, 0.08670373f,
        0.08626223f, 0.08582301f, 0.08538599f, 0.08495122f, 0.08451865f, 0.084088296f,
        0.08366012f, 0.08323414f, 0.082810305f, 0.082388654f, 0.08196913f, 0.08155176f,
        0.081136495f, 0.08072337f, 0.08031232f, 0.07990339f, 0.07949652f, 0.079091735f,
        0.078689f, 0.07828833f, 0.07788969f, 0.07749307f, 0.07709849f, 0.0767059f,
        0.07631533f, 0.07592673f, 0.075540125f, 0.075155474f, 0.0747728f, 0.07439206f,
        0.07401326f, 0.07363639f, 0.07326145f, 0.0728884f, 0.07251727f, 0.072148f,
        0.071780644f, 0.071415134f, 0.0710515f, 0.07068971f, 0.07032977f, 0.06997164f,
        0.069615364f, 0.06926088f, 0.06890822f, 0.06855734f, 0.06820824f, 0.06786094f,
        0.06751539f, 0.06717162f, 0.06682958f, 0.066489294f, 0.066150725f, 0.0658139f,
        0.06547877f, 0.06514537f, 0.06481365f, 0.064483635f, 0.06415528f, 0.06382861f,
        0.063503586f, 0.06318025f, 0.06285854f, 0.062538475f, 0.062220022f, 0.061903212f,
        0.061588f, 0.061274406f, 0.060962394f, 0.060651988f, 0.060343146f, 0.060035877f,
        0.059730187f, 0.05942604f, 0.059123453f, 0.058822393f, 0.058522884f, 0.058224883f,
        0.057928413f, 0.05763344f, 0.057339985f, 0.057048008f, 0.05675753f, 0.056468524f,
        0.05618099f, 0.05589492f, 0.055610314f, 0.055327144f, 0.05504543f, 0.054765135f,
        0.054486282f, 0.054208837f, 0.053932816f, 0.05365819f, 0.053384975f, 0.053113136f,
        0.05284268f, 0.052573618f, 0.05230591f, 0.05203958f, 0.051774595f, 0.051510967f,
        0.051248673f, 0.050987724f, 0.050728094f, 0.050469797f, 0.050212804f, 0.04995713f,
        0.04970275f, 0.04944967f, 0.04919787f, 0.048947368f, 0.048698124f, 0.048450164f,
        0.048203453f, 0.047958013f, 0.04771381f, 0.047470864f, 0.047229137f, 0.046988655f,
        0.046749387f,
    )

    /** Score factor for a hand-over stepping back [backMs]: 1 inside the allowance. */
    fun handoverKeep(backMs: Long): Float {
        val n = backMs - HANDOVER_ALLOWANCE_MS
        if (n <= 0L) return 1f
        if (n < HANDOVER_KEEPS.size) return HANDOVER_KEEPS[n.toInt()]
        return Math.pow(HANDOVER_CHARGE_KEEP.toDouble(), handoverExcess(backMs).toDouble()).toFloat()
    }

    /**
     * The rescue pass runs when the lead's distance is over this, or nothing was
     * found. At 0.5 it cost 10 labelled leads; 1.0 cost none. Infinite turns it off.
     * The beam's dropped-tap run opens on the same lead ([DROPPED_TAP_COST_KW]).
     */
    const val RESCUE_MIN_LEAD_D = 1.0f

    /**
     * A list only the rescue pass found commits its lead when it fits within this. On the phone,
     * four right words sat pick-only at 0.48-0.63 and one wrong at 0.56; the corpus commits
     * nothing up to 0.8, and past 0.94 about as many wrong words as right.
     */
    const val RESCUE_COMMIT_D = 0.65f

    /**
     * And the lead is alone, or scores this many times the next word. 1.5 commits `minute` for
     * `minutes` at d 0.27; the phone's `improvvisamente` led its rival 2.5 to 1.
     */
    const val RESCUE_COMMIT_CLEAR = 2f

    /**
     * Score factor per gate the rescue relaxes. A factor, not a distance: past GEO_SATURATION_KW
     * a distance charge is flattened to nothing. Flat from 0.3 to 0.8; 0.6 keeps both gains on
     * the hand-checked rows.
     */
    const val RESCUE_GATE_KEEP = 0.6f

    /**
     * Descend steps for the rescue pass. 15 000 is the smallest budget that keeps every labelled
     * and hand-checked gain; the slowest phone decodes walked 20 000 on top of a full stream pass.
     */
    const val RESCUE_STEP_BUDGET = 15_000

    /**
     * Which search reads a swipe buffer: 1 the DFS passes, 2 the thumb-cursor beam
     * ([ThumbBeam]) alone, 3 the passes then the beam. 3 takes labelled top-1 336 -> 416 and
     * hand-checked 46 -> 64 with no golden moved; 2 alone is six times faster but empties 91 leads.
     */
    const val SEARCH_ENGINE = 3

    /** States the beam keeps per letter. */
    const val BEAM_WIDTH = 128

    /** Readings the beam may score, each a DTW over every swipe. */
    const val BEAM_MAX_ATTEMPTS = 303

    /** Beam priority per unit of -ln(best frequency below): pulls common subtrees forward. */
    const val BEAM_FREQ_WEIGHT = 0.4999f

    /** Beam priority charge, in kw, for a DFS gate the reading breaks (start or end radius). */
    const val BEAM_GATE_COST = 0.5f

    /** Score factor per broken gate on a finished beam reading, as the rescue's. */
    const val BEAM_GATE_KEEP = 0.6f

    /**
     * Beam priority, in kw, for a tap read as no letter: a stray tap inside a long two-thumb word, read
     * in a run of its own when the lead fits worse than RESCUE_MIN_LEAD_D. Traced rows +11 -0, polyline
     * +8 -0, corpus +1 -0; prose kept words +3, rejected -3, fixed +1. Inside the one beam it lost rows
     * at every charge; on its own run 1.0 kw (keep 0.5) loses a polyline and a corpus row, 3.0 kw (keep
     * 0.1) gives up a traced row; a lead over 0.6 adds two traced rows and fires more often.
     */
    const val DROPPED_TAP_COST_KW = 2.006f

    /** Score factor on a reading that drops a tap, so it leads only a word the strokes fit far better. */
    const val DROPPED_TAP_KEEP = 0.25f

    /** New words the beam may add to the passes' list; the rest of the list keeps its order. */
    const val BEAM_MAX_NEW = 2

    /** Whether a beam reading is judged by the geometric term without its floor. */
    const val BEAM_UNSATURATED = true

    /**
     * Whether a beam letter whose earliest pass is too far back for the hand-over takes the next
     * pass of its key that is in time. A long stroke passes a key early and again later; the
     * earliest alone left 34 of 73 traced two-thumb long words with no reading at all. Traced rows
     * +8 -0, scripted +22 -0, corpus and hand unchanged; one polyline replay of a traced miss lost.
     */
    const val BEAM_PASS_IN_TIME = true

    /** Scoring attempts for the rescue pass, each one a DTW. 200 moves no labelled row; the slowest phone decode spent 800. */
    const val RESCUE_MAX_ATTEMPTS = 200

    /**
     * Descend steps the stream pass may take before it stops. 100 000 is the smallest budget
     * that moves no labelled row: JVM worst decode 18.5 -> 8.0 ms, one lead of 6 939 changed, a
     * garbage `cistifellea` to empty; 50 000 starts changing real words.
     */
    const val STREAM_SEARCH_STEP_BUDGET = 100_000

    /**
     * Skip a trie subtree whose longest word is shorter than the letters the unconsumed input
     * must still spell. Exact: the candidates are byte-identical either way.
     */
    const val LENGTH_BOUND_PRUNE = true

    /**
     * What a single tap costs when it stands for a doubled letter, in kw added to the reading's
     * distance; negative turns the allowance off. A tap consumes one letter, so without it
     * `ottima` from one `t` and `tutti` from two cannot be read at all. At 0.1, and down to 0.09988, hand-checked
     * leads go 26 -> 34 and labelled top-1 321 -> 324, no word to empty; 0 doubles short tapped
     * words (`trace` -> `tracce` x18) and 0.3 gives the gain back.
     */
    const val DOUBLE_TAP_PEN_KW = 0.09994f

    /**
     * Row pitch the centred landscape block is held at, the 1.50 kw of `TestData.qwertyGeometry`.
     * Pitch is not what costs a landscape decode under a real hand, key size is. The
     * split takes its width from the gap slider instead.
     */
    const val LANDSCAPE_ROW_PITCH_KW = 1.5f

    /**
     * Frequency bytes by which one lexicon must out-rank the other before a shared word is filed
     * there on frequency alone; closer than this the surrounding words decide. 18 is the lowest
     * value on the 18-25 plateau, 135 of 139 hand-labelled commits.
     */
    const val SHARED_WORD_FREQ_MARGIN = 18

    /**
     * When the stream pass runs: 0 never, 1 when the heap still has room, 3 always. Always
     * evicts good leads the way the cut pass did ungated: top-1 251 against 268 and
     * `keys` 45 -> 25.
     */
    const val STREAM_SEARCH_GATE = 1

    /**
     * Cuts one reading of the stream pass may place, over all its swipes together. 3 is the
     * smallest value holding the gain: top-1 291 at 2, 299 at 3, 300 at 4 (at 150 ms).
     */
    const val STREAM_SEARCH_MAX_CUTS = 3

    /**
     * The stream pass may also cut a thumb at its own key boundaries, where one of its letters
     * ends. The other thumb's events alone cannot separate `happens`' left-thumb `pp` from the
     * travel toward its `n`, and the lower length band then refuses the head: without these
     * cuts the pass lifts top-1 by 3, with them by 23.
     */
    const val STREAM_SEARCH_OWN_CUTS = true

    /**
     * A head cut at the end of one of the thumb's own keys must close on that key's letter:
     * the cut says that key was the thumb's last letter before the hand-over. It halves the
     * worst decode (54 -> 22 ms on the corpus) for 5 top-1 at 3 cuts.
     */
    const val STREAM_SEARCH_OWN_CUT_CLOSES_ON_KEY = true

    /**
     * ...or on any letter matched while the thumb was still on that key. A thumb turning at `o`
     * for `happens`' `pp` never touches `p`; the letter is read at the turn, inside `o`'s
     * contact, and the strict rule refused it on all three captured attempts.
     */
    const val STREAM_SEARCH_CLOSE_INSIDE_LAST_KEY = true

    /**
     * Cut times offered per swipe when the search places its own cuts: the other stream's events
     * inside the swipe, so on a two-thumb buffer one per contact the other thumb made. A tail is
     * offered the same list, filtered to its own span. Each is a branch walked, so this is the
     * latency knob of the pair; top-1 is 245 at 4 with one golden failing, 247 at 8 and 12.
     */
    const val MAX_SEARCH_CUT_TIMES = 8

    // Key-contact extraction hysteresis: the current key keeps ownership until
    // the pointer leaves its bounds inflated by this much (kills border jitter).
    const val STICKY_INFLATE_KW = 0.2f

    // Intra-stream dwell detection (GestureStream.addPoint): a run of samples within
    // DWELL_RADIUS_KW of its first sample for at least DWELL_MIN_MS is a pause, and the thumb has
    // stopped producing letters. A dwell is only a secondary cut source in MergeAlternatives,
    // admitted for a swipe the other stream is active in.
    // 150 ms catches the one real letter boundary in a device capture, the rest inside
    // `interessante` at 159 ms. Duration cannot separate rests from hesitations (158, 164 and
    // 213 ms in the same capture); co-occurrence did, since every hesitation sat on a single-thumb
    // swipe, hence the cross-stream gate in MergeAlternatives. Real rests are about 160 ms, so
    // TestData.dwellSwipe's 400 ms fixtures are the generous case.
    const val DWELL_MIN_MS = 150L
    // 0.3 kw is reasoned, not measured: above the jitter STICKY_INFLATE_KW already absorbs, so
    // noise cannot break a run, and well under a 1.0 kw key hop, so a drift across a key stays
    // travel. The trace prints each dwell's peak displacement and sample count to settle it.
    // Caution: a parked thumb in `sempre` produced no dwell over 316 ms, so either it kept moving
    // or 0.3 kw is too tight for real thumb drift.
    const val DWELL_RADIUS_KW = 0.3f
    // Path pieces one gesture may be cut into, so MAX_DWELL_SEGMENTS - 1 dwells are kept (the
    // longest) and a jittery slow gesture cannot fan out. Four legs per thumb covers an 8-letter
    // alternated word; two streams give 8 pattern pieces, inside MAX_WORD_LEN and bounded for DTW.
    const val MAX_DWELL_SEGMENTS = 4

    // Scoring.
    const val FREQ_WEIGHT_FLOOR = 0.25f

    // Bigram context boost: BigramTable.multiplier returns 1 + BIGRAM_BOOST_MAX * byte/255, so the
    // range is [1.0, 2.0], conditioned on the fit through appliedBoost like the personal boost.
    // The byte is normalised per previous word against its own maximum, so the top continuation of
    // every word earns the full boost; unconditioned, it took two `mujer` gestures to `me`
    // (d=0.90 bm=1.96 against d=0.40).
    // 1.0 is the top of a plateau: on 219 final-buffer rows every cap from 0.5 to 1.0 moves one
    // top-1, `here` over `her` (d 0.31 against 0.54). Below 0.5 rows regress (3 at 0.25, 5 at 0);
    // above 1.0 `her` keeps the row.
    // A fade, not a hard gate past the cap: a hard gate loses a real `sempre` at d=0.55 carried by
    // bm=1.91. boostWeight keeps 0.79 of the boost there and none past one key, so an
    // all-saturated row falls to fw (`nosotros`). The fade only ever moves a contest toward the
    // better fit (ScoreWeightingTest.theBoostConditionNeverFavoursTheWorseFit). Compressing the
    // boost everywhere (bm^b) reaches two rows only at b ~ 0.1, which deletes context prediction.
    const val BIGRAM_BOOST_MAX = 1.0f

    /**
     * Score kept per letter a swipe segment consumed that the finger was never measurably on.
     *
     * A segment admits any key whose centre is within R_INNER_KW of the path, so every neighbour
     * of every key crossed is admissible: `keys`, all four letters contacted, lost to `kyra`,
     * which takes `r` and `a` from neighbours. On 447 device decodes 48% of winners need at least
     * one such letter, so this is a charge, not a veto: contact detection misses fast crossings,
     * and `held`'s own `l` was never contacted either.
     *
     * A factor, not a distance: past GEO_SATURATION_KW a distance charge moves neither the
     * geometric term nor the boost. 0.85 is the plateau top: 0.80 loses the `sarei` golden, whose
     * `a` was not crossed either, while `keys` on device wants 0.80 or below.
     */
    const val UNCONTACTED_LETTER_KEEP = 0.85f

    // Geometric term of the candidate score:
    //   score = fw * geometricTerm(dTotal) * bigram * personalBoost
    // A bare 1/(1+d) spans 1.0 -> 0.4 over the useful range while fw*bm*pb spans ~3x, so a
    // frequent word with a mediocre shape beat a near-perfect fit (`the` d=0.56 over `there`
    // d=0.22). Sharpening alone cannot fix it: `sarei` (d=1.06) must keep beating `sergei`
    // (d=0.59) on frequency, which caps any 1/(1+d)^g at g < 1.06, and the `sudare` row needs
    // g >= 1.44. So the term is steep for good fits and saturates at GEO_SATURATION_KW, past
    // which a fit carries no information and frequency decides.
    // 3.75 / 0.50 sits inside a regression-free plateau (0.45-0.60 x 2.5-4.0) and fixes 13 of 42
    // measured contests; steeper values gain only on reconstructed paths. 0.50 kw is still inside
    // the intended key.
    // GEO_SATURATION_KW also bounds WordComposer.merge rule 2b and the boostWeight fade. A long
    // stroke moves the score's cap alone (GEO_SATURATION_LONG_KW): on the probes both others
    // left every row where it was.
    const val GEO_EXPONENT = 3.75f
    const val GEO_SATURATION_KW = 0.5f

    /**
     * On a buffer whose strokes add up to LONG_STROKE_KW or more, the fit counts up to this
     * before frequency decides: there a frequent word past half a key beat a better-fitting long
     * one (`particularly` 0.42 against `prosperity` 1.04). Traced rows 404 to 420 of 597,
     * labelled corpus 428 to 432, hand rows and kept words unchanged at 0.8. With
     * HANDOVER_KEEP_FLOOR in, 0.9 adds two traced rows (`significantly`) and loses none; past 1.0
     * prose kept words fall.
     */
    const val GEO_SATURATION_LONG_KW = 0.9f

    /**
     * A buffer's swiped arc, both thumbs summed, from which [GEO_SATURATION_LONG_KW] applies.
     * One cap for every reading of a buffer: a cap by the word's own length lowered a long
     * word's floor below its short rivals (`probabilmente` read `ospedale`). 22 sits inside a
     * range of 21-26 kw that loses no row, and 21.97 moves no captured lead from it; at 18 a sloppy
     * `understanding` reads `undertaking`.
     */
    const val LONG_STROKE_KW = 21.97f

    /** The score's saturation for a buffer whose swipes add up to [arcKw]. */
    fun saturationFor(arcKw: Float): Float =
        if (arcKw >= LONG_STROKE_KW) GEO_SATURATION_LONG_KW else GEO_SATURATION_KW

    /**
     * A swiped word the active language cannot spell at all may still commit from the other
     * language when it fits this closely: below the 0.35 and 0.40 kw fits of the two empty
     * `parlare` decodes that once committed `patéale`. 0.25 and 0.30 both gain 3 of 487 labelled
     * foreign rows with none lost; 0.25 changes 26 of 8 692 captured leads, 0.30 changes 33. 0.245 and
     * 0.255 move no captured lead today.
     */
    const val NO_NATIVE_LEAD_KW = 0.2461f

    /**
     * With no primary language (the opt-in), how many languages decode at once: the active one
     * and the next two enabled in its script. Each extra resident competes for the others'
     * words: labelled top-1 on the en/it rows is 380 with two, 373 with three, 360 with five and
     * 328 with nine.
     */
    const val MAX_RESIDENT_LANGUAGES = 3

    /**
     * With no primary language, how far each language's standing moves toward a committed
     * word's score in it (LanguageMomentum). Replayed with its own commits, 0.3 and 0.5 give the
     * same labelled rows (210 of 487 foreign, 210 native); 0.3 changes fewer captured leads.
     */
    const val MOMENTUM_STEP = 0.3f

    /**
     * The weight the other languages fall to behind a clear front-runner. Swept 1.0 / 0.8 /
     * 0.6 / 0.4: labelled rows are flat (208-210) and captured lead churn is 385 / 291 / 286 /
     * 351 of 4 527; 0.6 is the bottom.
     */
    const val MOMENTUM_DAMPED_WEIGHT = 0.6f

    /** The front-runner's lead, in score units, at which the others reach [MOMENTUM_DAMPED_WEIGHT]. */
    const val MOMENTUM_FULL_LEAD = 0.2f

    /**
     * [geometricTerm] without the floor, for readings only the thumb-cursor beam can make:
     * they must win on the shape, because past the cap a frequent word would win on nothing.
     */
    fun geometricTermUnsaturated(dTotal: Float): Float = 1f / pow1p(dTotal)

    /** Geometric factor of the score; 1.0 at a perfect fit, floored past saturation at [cap]. */
    fun geometricTerm(dTotal: Float, cap: Float = GEO_SATURATION_KW): Float {
        val d = if (dTotal < cap) dTotal else cap
        return 1f / pow1p(d)
    }

    /**
     * Exact inverse of [geometricTerm] for the DTW early-abandon budget: the largest dTotal at
     * which [numerator] * geometricTerm(d) still beats [minScore]. POSITIVE_INFINITY when even a
     * saturated fit clears it (no useful budget exists), <= 0 when nothing can.
     *
     * Must track [geometricTerm] exactly: a bound too tight drops candidates that would have won,
     * one too loose only costs latency. WordPredictor is the sole caller and ScoreWeightingTest
     * checks the round trip.
     */
    fun maxDTotalForScore(numerator: Float, minScore: Float, cap: Float = GEO_SATURATION_KW): Float {
        if (minScore <= 0f) return Float.POSITIVE_INFINITY
        val ratio = numerator / minScore
        if (ratio <= 1f) return 0f
        val d = Math.pow(ratio.toDouble(), 1.0 / GEO_EXPONENT).toFloat() - 1f
        return if (d >= cap) Float.POSITIVE_INFINITY else d
    }

    /** [geometricTerm] past saturation: the share of a perfect fit's score a
     *  hopeless one still keeps. Used as the normalizer in [boostWeight]. */
    private val SATURATED_TERM = geometricTerm(GEO_SATURATION_KW)

    /**
     * Confidence given to a boost, personal or bigram, on a candidate whose geometric fit is
     * [dGeometric]: 1.0 inside [GEO_SATURATION_KW], then faded continuously to 0.0 by
     * `2 * GEO_SATURATION_KW` = 1.0 kw, one whole key.
     *
     * The fade is [geometricTerm] of the excess, renormalised onto [0, 1], so it has no tunable
     * of its own: at the cap a fit still discriminates and a boost is believed; a key away the
     * gesture is somewhere else and a boost says nothing about it.
     *
     * [dGeometric] is the DTW mean, not dTotal: tap substitution and completion penalties charge
     * letters that were never drawn and say nothing about fit. An all-tap decode has
     * `geoFit = 0f` and keeps weight 1.0, which leaves autocorrect and PersonalWeightTest
     * unchanged.
     *
     * The result is in [0, 1] and non-increasing in [dGeometric]; [appliedBoost]'s guarantees
     * rest on both.
     */
    fun boostWeight(dGeometric: Float): Float {
        val excess = dGeometric - GEO_SATURATION_KW
        if (excess <= 0f) return 1f
        // geometricTerm saturates its own argument, so this reaches 0 at
        // excess >= GEO_SATURATION_KW and stays there.
        return (geometricTerm(excess) - SATURATED_TERM) / (1f - SATURATED_TERM)
    }

    /**
     * A boost, personal or bigram, as applied to a score: [rawBoost] while the candidate's own
     * geometry carries information, faded to 1.0 as it runs out (see PERSONAL_BOOST). One
     * continuous rule for both boosts: an on/off gate stepped at the cap, and 0.03 kw of fit
     * flipped a 1.22x boost on `sempre` four times in one capture.
     *
     * Never exceeds [rawBoost] and never falls below 1.0. WordPredictor.emit's abandon budget
     * uses the raw value and relies on the first bound to stay admissible; ScoreWeightingTest
     * checks both.
     */
    fun appliedBoost(rawBoost: Float, dGeometric: Float): Float =
        1f + (rawBoost - 1f) * boostWeight(dGeometric)

    private fun pow1p(d: Float): Float =
        Math.pow((1f + d).toDouble(), GEO_EXPONENT.toDouble()).toFloat()

    // Personal adaptive weighting:
    //   final_score = base_score * (1 + PERSONAL_BOOST * ln(1 + personalCount))
    // where personalCount counts final commits of the word. A top-frequency word (fw ~0.93) leads
    // a mid-frequency rival (fw ~0.66) by ~1.4x, so the boost must reach that in a realistic
    // number of picks: 20 give 1.46 (a rank flip, `thou` over `you`), 5 give 1.27. The log keeps
    // heavy reinforcement bounded, so bigram context (up to 2.0x) stays competitive.
    // The boost is weighted by the candidate's geometric fit through boostWeight. A ceiling blind
    // to fit cannot work: `mujer` over `me` needs C < 1.234, `keyboard` over `leonard` C < 1.165,
    // and PersonalWeightTest.twentyCommitsFlipTheRanking C > 1.434. A personal count says which
    // of two plausible words was meant, not that a poor fit is what was drawn.
    // The fade ends at 1.0 kw, one key; widths from 0.75 to 2.5 x GEO_SATURATION_KW all clear the
    // four target rows. Its cost is `mo` (d=0.45, pb=1.29) losing to `no` (d=0.68, pb=1.57), the
    // price of winning `sempre` at d=0.60, 0.08 kw away. A reinforced word cannot rescue a
    // gesture a whole key off; if that costs a real word, the lever is the fade width, not a
    // threshold.
    // The bar's badge still shows the raw count, so a reinforced word can show seven dots and not
    // move.
    const val PERSONAL_BOOST = 0.15f

    /**
     * Personal pair weighting: `1 + PERSONAL_BIGRAM_BOOST * ln(1 + pairCount)`, through the same
     * fade as every other boost. PERSONAL_BOOST's value, as the same shape over the same kind of
     * evidence; the two multiply on a word frequent for this user and after this predecessor.
     */
    const val PERSONAL_BIGRAM_BOOST = 0.15f

    // A learned pair must be seen this many times before it boosts anything, for the reason of
    // PERSONAL_MERGE_MIN_COUNT: a commit is not proof the decode was right. In "i don't oboe
    // know" the wrong word was left in and `don't -> oboe` learned from one commit; the retype
    // button only takes back errors the user retypes.
    const val PERSONAL_PAIR_MIN_COUNT = 2

    /**
     * Bundled pairs read per previous word for next-word predictions. The table normalises
     * each context to its own maximum, so the strongest few already carry the ranking.
     */
    const val NEXT_WORD_POOL = 32

    // Personal words merge into the active language's trie at dictionary load, scaled by
    // USER_FREQ_SCALE so a few real uses compete with corpus counts in the millions, once
    // committed PERSONAL_MERGE_MIN_COUNT times. One stray commit (a language-swap misfire, a
    // typo, a garbage merged decode) must not become decodable, or decoding re-commits it and
    // every commit re-learns it. Two commits is the smallest signal distinguishable from one
    // accident. The floor also keeps rows de-reinforced to zero from returning as freqByte-1
    // ghosts through the max(1, ..) quantizer.
    const val PERSONAL_MERGE_MIN_COUNT = 2
    const val USER_FREQ_SCALE = 1000

    /**
     * Votes before a learned capital leads its word: up one for each mid-sentence commit written
     * with it, down one for each without. Two, as for the merge floor, is the smallest signal
     * that is not one accident. A sentence start does not vote: the shift wrote that capital.
     */
    const val LEARNED_CAPITAL_MIN_VOTES = 2

    /**
     * The votes' range. A settled capital stays at most four lowercase commits from losing the
     * lead, and a word written lowercase four from gaining one, whatever came before.
     */
    const val CASE_VOTES_LOW = -2
    const val CASE_VOTES_HIGH = 5

    // Suggestion-bar badge tiers: 1..7 dots (1 center + up to 6 hexagon corners). Thresholds
    // double per tier, counts 1,2,4,8,16,32,64, so a couple of uses already advance the badge
    // while tier 7 lands at 64 uses, and equal visual steps are equal multiplicative effort, like
    // the ln-shaped boost. Manual reinforcement (+1/+5/+10) feeds the same count.
    const val PERSONAL_TIER_MAX = 7

    fun personalTier(count: Int): Int {
        if (count <= 0) return 0
        var tier = 1
        var threshold = 2
        while (tier < PERSONAL_TIER_MAX && count >= threshold) {
            tier++
            threshold *= 2
        }
        return tier
    }

    // How long after a buffer is abandoned its letters may still be labelled by the next
    // committed word, for the commit-time miss line only; nothing in the decode path reads it.
    // Loose because the line prints its own gap and an unrelated pair is filtered when the
    // capture is read. It covers a delete-and-retype (1 456 and 1 651 ms against a 170 ms median
    // typing gap) plus time spent looking at a wrong word before retyping, 5.7 s once captured.
    const val MAX_RETYPE_GAP_MS = 6000L

    // Autocorrect: geometric confidence 1/(1+dTotal) must exceed this. The levels are
    // off / normal / aggressive (arrays.xml, KeyboardConfig.from).
    const val AUTOCORRECT_CONF_NORMAL = 0.85f
    const val AUTOCORRECT_CONF_AGGRESSIVE = 0.80f

    // Per-word cross-language ranking (WordComposer.merge) has no constants of its own:
    // - No cross-dictionary frequency normalisation is needed. Each asset is log-quantised
    //   against its own maximum, and fw at matched rank percentiles agrees within 1.04-1.07x
    //   across en/it/es/pl/cs, worth under 0.025 kw of distance. Across the nine shipped assets
    //   the p50 spread is 1.20x, Norwegian the outlier at 0.456, not yet priced. An AOSP-merged
    //   import (DictionaryStore.wordlistOverride) is the one path that can build a different one.
    // - Any normalisation would have to be a per-language factor: the score is a product, so a
    //   percentile or z-score remap reorders words within a language and moves the goldens.
    // - The one bound the merge uses is GEO_SATURATION_KW, borrowed from the score.
    // A whole-list language swap was removed with its two thresholds: on device geometry the
    // same-language and foreign confidence ratios overlap in [1.000, 1.095], so no threshold on
    // a single gesture separates two Romance languages.
}

/**
 * The letters one language's trie and board are spelled in, as compact codes.
 *
 * A language declares the characters it uses and each gets a small code, so the trie is as
 * wide as the language needs. Codes are the letters' order in [letters], then the apostrophe
 * when the language keeps one. Latin is `a`..`z` = 0..25 and the apostrophe 26, the codes
 * every earlier build used, so a Latin trie, geometry and decode are unchanged. The companion
 * keeps Latin's constants for the code that only ever meant Latin.
 *
 * A character outside [letters] has no code. Accented and alternate forms reach a code
 * through [AccentFolder] first, and come back through a word's display forms.
 */
class Alphabet private constructor(
    /** The script's name, as a layout declares it (`"script"`). */
    val script: String,
    /** The letters in code order. One contiguous Unicode run per script keeps [codeOf] a table. */
    val letters: String,
    hasApostrophe: Boolean,
) {
    val letterCount: Int = letters.length

    /** Codes in use: the letters, then the apostrophe if kept. */
    val size: Int = letterCount + if (hasApostrophe) 1 else 0

    /** The apostrophe's code, or -1 where the language writes none. */
    val apostrophe: Int = if (hasApostrophe) letterCount else -1

    private val base: Int = letters.minOf { it.code }
    private val table = IntArray(letters.maxOf { it.code } - base + 1) { -1 }.also { t ->
        for ((i, c) in letters.withIndex()) t[c.code - base] = i
    }

    init {
        require(size <= MAX_SIZE) { "$script has $size codes; the trie fits $MAX_SIZE" }
    }

    fun codeOf(c: Char): Int {
        if (c == '\'') return apostrophe
        val i = c.code - base
        return if (i >= 0 && i < table.size) table[i] else -1
    }

    fun charOf(code: Int): Char = if (code == apostrophe) '\'' else letters[code]

    /** True for a letter of this alphabet, not the apostrophe. */
    fun isLetter(c: Char): Boolean {
        val code = codeOf(c)
        return code in 0 until letterCount
    }

    fun encode(word: CharSequence): IntArray? {
        val out = IntArray(word.length)
        for (i in word.indices) {
            val c = codeOf(word[i])
            if (c < 0) return null
            out[i] = c
        }
        return out
    }

    override fun toString(): String = script

    companion object {
        /** The widest alphabet the trie's 6-bit letter and child-count fields hold. */
        const val MAX_SIZE = 63

        val LATIN = Alphabet("latin", "abcdefghijklmnopqrstuvwxyz", hasApostrophe = true)

        /** Russian: `ё` folds onto `е`, as its keyboard puts it there. No apostrophe. */
        val CYRILLIC = Alphabet("cyrillic", "абвгдежзийклмнопрстуфхцчшщъыьэюя", hasApostrophe = false)

        /**
         * Ukrainian: its own alphabet order, without ъ ы э and with є і ї, and the apostrophe,
         * which is part of a word (`п'ять`). `ґ` folds onto `г`, as its keyboard puts it there.
         * Not Russian's codes: a Russian trie on a Ukrainian board would read wrong keys.
         */
        val UKRAINIAN = Alphabet("ukrainian", "абвгдеєжзиіїйклмнопрстуфхцчшщьюя", hasApostrophe = true)

        /**
         * Hebrew: 22 letters and the five final forms, each a key of its own, in Unicode order.
         * The apostrophe stays: transliterated sounds are written with one (`צ'יפס`).
         */
        val HEBREW = Alphabet("hebrew", "אבגדהוזחטיךכלםמןנסעףפץצקרשת", hasApostrophe = true)

        /**
         * Arabic: the 28 letters with hamza, hamza on waw and on yeh, taa marbuta and alef
         * maqsura, each a key. The alef-hamza forms fold onto alef (AccentFolder).
         */
        val ARABIC = Alphabet("arabic", "ءؤئابةتثجحخدذرزسشصضطظعغفقكلمنهوىي", hasApostrophe = false)

        /** Every alphabet a board can be in. */
        val ALL: List<Alphabet> = listOf(LATIN, CYRILLIC, UKRAINIAN, HEBREW, ARABIC)

        private val BY_SCRIPT = ALL.associateBy { it.script }

        /** The alphabet a layout names, Latin when it names none or an unknown one. */
        fun forScript(script: String?): Alphabet = BY_SCRIPT[script] ?: LATIN

        /** The alphabet a language's dictionary is spelled in. */
        fun forLanguage(lang: String): Alphabet = when (lang) {
            "ru" -> CYRILLIC
            "uk" -> UKRAINIAN
            "he" -> HEBREW
            "ar" -> ARABIC
            else -> LATIN
        }

        // Latin's own codes, for the code that has only ever meant Latin.
        const val SIZE = 27
        const val LETTERS = 26
        const val APOSTROPHE = 26

        fun codeOf(c: Char): Int = LATIN.codeOf(c)
        fun charOf(code: Int): Char = LATIN.charOf(code)
        fun encode(word: CharSequence): IntArray? = LATIN.encode(word)
    }
}
