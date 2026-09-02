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

    // A "swipe" shorter than this decodes as its start key only (flick tolerance).
    const val MIN_SWIPE_ARC_KW = 1.5f

    // Path matching.
    const val RESAMPLE_N = 32
    const val DTW_BAND_R = 4
    const val DTW_ENDPOINT_WEIGHT = 2f

    // Candidate pruning. Endpoint radius covers the key itself plus immediate
    // neighbors; inner radius is looser because mid-gesture accuracy is lower.
    const val R_ENDPOINT_KW = 1.4f
    const val R_INNER_KW = 1.8f
    // How far the path must leave a key and come back for the return to count
    // as a SECOND visit (Matcher.collectPasses). The separator used to be
    // R_INNER_KW itself: a visit ended when the path left the
    // 1.8 kw disc. But the smallest genuine revisit is a hop to an adjacent key
    // and back - 1.0 kw - so every out-and-back between 1.0 and 1.8 kw was
    // invisible and all of a key's visits merged into one pass. A word needing
    // that letter at increasing indices then became unspellable: "vedere"
    // (v-e-d-e-r-e, all legs under 1.8 kw) decoded EMPTY on 2 of 3 natural
    // gestures measured on a device.
    //
    // 0.7 = the 1.0 kw key hop minus 0.3 kw conceded to two measured effects:
    // arc-length resampling samples the excursion apex only to within half a
    // step (the clean "vedere" path's e->r->e apex registers 0.88, not 1.00, at
    // a 0.29 kw step), and a real finger rounds its turns short of the
    // neighbour's centre. It is also 2.3x DWELL_RADIUS_KW and 3.5x
    // STICKY_INFLATE_KW, so hand jitter cannot manufacture a pass.
    //
    // Swept against the shipped assets (top 20k words per language, each on its
    // own clean centre-to-centre path): 114 it / 87 en / 108 es words are
    // unspellable pre-fix, and 0.7 recovers 112 / 87 / 105 of them for +2.8%
    // total passes. 0.9 is measurably too high - the clean "vedere" path fails
    // there. Lowering to 0.5 also recovers passare/porteremo/mueren/retrocede
    // (broken on perfect-centre paths only) but nearly doubles the added passes,
    // so it was left on the table rather than taken.
    const val PASS_SPLIT_PROMINENCE_KW = 0.7f
    const val MONOTONE_SLACK = 6
    const val MAX_WORD_LEN = 24
    // Candidate budget per Search pass: counts words that actually reach the
    // heap, and feeds the root-fairness slicing in WordPredictor.descend (the
    // nearest-start subtree gets MAX_CANDIDATES/2, the other admissible starts
    // share the rest, so deep words behind a high-frequency sibling flood -
    // "cuñado" behind co-/con- - are not starved). Raised 400 -> 800 when that
    // slicing landed, so the nearest share still covers what the whole
    // pre-slicing budget used to give it.
    //
    // This counter used to be charged on ENTRY to emit(), i.e. before
    // the score and DTW abandon prunes could reject a word, so it bounded words
    // WALKED, of which only ~6% became candidates: on the clean "parlare" path
    // 499 of 533 units went to DTW-abandoned words, the p- subtree burned its
    // half share inside pe-/pr- (firstStop=pregate), 267 global units went
    // unspent, and "parlare" - freq 166324, admissible at d=0.000 - was never
    // reached at all. With the two budgets separated the
    // slice is measured in candidates, so it now binds only on patterns that
    // genuinely produce 800 of them.
    const val MAX_CANDIDATES = 800
    // Companion ceiling on the work the search may do to find those candidates:
    // one unit per emit() call, charged whether or not the word survives the
    // abandon prunes. Splitting the two is what makes MAX_CANDIDATES mean what
    // its name says, and that separation is the whole of the fix - no pruning,
    // ordering or fairness rule changed.
    //
    // 4000 is a runaway backstop, not a tuning knob, and the distinction is
    // measured: with the split the parlare search TERMINATES BY ITSELF after
    // 853 attempts (stops=0 - it exhausts the admissible trie), so the ceiling
    // is never approached on the pathological path that motivated it. Nodes
    // visited under p- grow 3849 -> 6773 (~1.8x) and the four
    // *LatencyIsBounded goldens hold with room to spare.
    //
    // Two alternative policies were measured and rejected; do not re-derive
    // them. An even share per sibling prefix starves deep words (computer,
    // interessante, cuñado all vanished from their lists), and a seed pass per
    // second letter costs +16% emits and fixes nothing, because reaching a deep
    // word behind high-frequency siblings needs breadth at every level (b^k),
    // not a seed.
    const val MAX_EMIT_ATTEMPTS = 4000
    // Decode heap size: two suggestion-bar pages of 5 zones.
    // A larger K weakens DTW early-abandon budgets (the running heap minimum
    // is lower), so decodeLatencyIsBounded must be re-verified whenever this
    // value changes.
    const val TOP_K = 10

    // Ideal-path length band relative to the observed arc length L:
    // accepted words satisfy 0.5*L - 1 <= idealLen <= 1.5*L + 1 (kw).
    const val LEN_BAND_LO = 0.5f
    const val LEN_BAND_HI = 1.5f
    const val LEN_BAND_MARGIN_KW = 1.0f

    // Fuzzy tap fallback (adjacent-key typos) and transposition alternatives.
    const val FUZZY_TAP_RADIUS_KW = 1.0f
    const val FUZZY_TAP_LAMBDA = 0.15f
    const val TRANSPOSE_PENALTY = 0.15f

    // Live tap-typing completions (pick-only): once the exact all-anchor pass
    // has consumed every tapped letter, the search keeps descending the trie
    // and emits longer words as Source.COMPLETION candidates. Only the exact
    // pass completes - extending fuzzy or transposed prefixes would dress
    // typos up as confident-looking words.
    // Below 2 tapped letters a prefix matches too much of the dictionary to
    // rank meaningfully; a single tap would complete to thousands of words
    // and bury the genuine one-letter words.
    const val COMPLETION_MIN_PREFIX = 2
    // 8 extra letters on a 2-letter prefix already reaches 10-letter words;
    // longer completions are rarely picked before the user keeps typing,
    // while subtree fan-out grows with every level (MAX_CANDIDATES still
    // bounds total emission per pass).
    const val COMPLETION_MAX_EXTRA = 8
    // Additive dTotal per completed letter. Exact-length matches carry
    // dTotal = 0, so any positive value keeps a typed word ahead of its own
    // extensions; 0.25 costs more than a worst-case fuzzy substitution
    // (FUZZY_TAP_LAMBDA * 1.0 kw = 0.15), so an adjacent-key correction of
    // similar frequency still outranks a completion of the same length and
    // only clearly more frequent words complete past it.
    const val COMPLETION_PENALTY_PER_LETTER = 0.25f

    // Dual-stream merge ambiguity generators.
    const val ORDER_AMBIG_MS = 120L
    const val SPLIT_MARGIN_MS = 80L
    const val MIN_SPLIT_HALF_ARC_KW = 0.5f
    // Head trim for the second half of a split swipe, serving two roles:
    //   1. Fallback trim when the resume is a single straight leg to the end
    //      (no turn): the half starts this far past the cut so the tapped letter
    //      is consumed once by the anchor, not re-consumed by the resumed path
    //      (swipe h-e-l-o + tap l must yield hel+l+o, not hel+l+lo).
    //   2. Minimum travel a distance-peak must clear to count as the first real
    //      resumed letter, so the rest cluster (samples within this radius of the
    //      cut) is never mistaken for a letter. splitSwipe prefers resuming at
    //      the first interior distance-from-cut peak (where the finger reaches
    //      its first real letter after a rest and turns away from it), which
    //      makes that letter satisfy the segment start-letter gate; it falls
    //      back to this fixed trim only when no such peak exists.
    // May need on-device tuning: a device path has sample jitter a synthetic
    // fixture lacks, which can raise or lower the effective peak threshold.
    const val SPLIT_HEAD_TRIM_KW = 0.6f
    // Endpoint-trimmed resume (split variants V2/V3, the early-tap regime of
    // the resume-after-interruption bug class): a tap lands BEFORE the intended letter
    // boundary, leaving the second half several kw of repositioning travel
    // (leftover forward leg plus the whole reversal leg for "quindi") whose
    // arc forces minLetters=2 and kills a single trailing letter. A no-turn
    // resume's letters live at its END, so these variants keep only the final
    // approach: comfortably under MIN_SWIPE_ARC_KW (1.5) so one letter may
    // close the half even with a sparse extra sample, yet at least a real key
    // hop so DTW still sees an approach direction rather than a point.
    const val SPLIT_RESUME_TAIL_KW = 1.2f
    // 12: a (tap, swipe) pair now emits up to three split variants (V1
    // cut-resume, V2 endpoint-trimmed tail, V3 apex-snapped cut) instead of
    // one, so the old cap of 8 could truncate the variant that carries the
    // word in dense buffers. Wrong variants find no words and cost
    // microseconds, but the cap bounds worst-case decode work -
    // decodeLatencyIsBounded must be re-verified whenever it changes.
    const val MAX_ALT_SEQUENCES = 12
    // Cross-stream boundaries one swipe may be cut at simultaneously:
    // 3 cuts = 4 path pieces, which covers an 8-letter word typed
    // with letters alternating between thumbs. The shipped tap-split generator
    // inserts one anchor per sequence, so a swipe interrupted TWICE could only
    // ever be cut once - the "cuando" failure measured on a device, where the only
    // representable orders spelled c,u,n,a,d,o instead of c,u,a,n,d,o. Each
    // extra cut multiplies pieces (and DTW work) per candidate, so this bounds
    // the fan-out alongside MAX_ALT_SEQUENCES; the *LatencyIsBounded goldens are
    // the gate whenever it changes.
    const val MAX_SPLIT_ANCHORS = 3

    // Key-contact extraction hysteresis: the current key keeps ownership until
    // the pointer leaves its bounds inflated by this much (kills border jitter).
    const val STICKY_INFLATE_KW = 0.2f

    // Intra-stream dwell detection (GestureStream.addPoint). A run of samples
    // staying within DWELL_RADIUS_KW of
    // the run's first sample for at least DWELL_MIN_MS is a pause: the thumb has
    // stopped producing letters. A dwell is only ever a SECONDARY cut source in
    // MergeAlternatives, admitted for a swipe the other stream is active in -
    // see the co-occurrence rationale on DWELL_MIN_MS.
    //
    // 150 ms, derived from a device capture (n = 4 dwells across
    // seven natural gestures): the one dwell that was a real letter boundary -
    // the deliberate rest inside "interessante" - measured 159 ms, so 150 sits
    // 9 ms below the floor needed to catch it and every larger value loses it.
    // This is a SENSITIVITY argument and explicitly not a specificity one: the
    // three dwells that were mere hesitations measured 158, 164 and 213 ms, so
    // the two populations interleave and NO duration threshold separates them
    // (raising this to 175 would drop the true boundary and keep the 213 ms
    // hesitation). What did separate them perfectly in that sample is
    // co-occurrence: all three false positives sat on single-thumb swipes with
    // no cross-stream activity, the true one on a swipe with a cross-stream tap
    // inside it. That is why MergeAlternatives gates dwell cuts on cross-stream
    // activity rather than on a longer duration. Also measured: real rests are
    // ~160 ms, so TestData.dwellSwipe's 400 ms fixtures are the generous case.
    const val DWELL_MIN_MS = 150L
    // 0.3 kw, and NOT derived from measurement - stated plainly because the
    // capture it was reviewed against could not constrain it: DecodeTrace printed dwell times
    // only, with no positional data (the trace now also prints each dwell's peak
    // displacement and sample count precisely so the next capture can settle
    // this). The value therefore still rests on its original reasoning: above
    // the jitter the contact hysteresis already absorbs (STICKY_INFLATE_KW) so
    // sample noise cannot break a run, well under a key hop (1.0 kw) so a
    // deliberate drift across a key stays travel. One indirect reading from the
    // capture is a caution rather than a value: "sempre"'s parked thumb produced
    // no dwell across a 316 ms window, meaning it never held within 0.3 kw for
    // 150 ms - either that hold was really continuous motion, or 0.3 kw is too
    // tight for real thumb drift. Coordinates are needed to tell those apart.
    const val DWELL_RADIUS_KW = 0.3f
    // Path pieces one gesture may be cut into, so MAX_DWELL_SEGMENTS - 1 dwells
    // are kept (the longest ones) and a jittery slow gesture cannot fan out.
    // Four legs per thumb covers an 8-letter alternated word; two streams give 8
    // pattern pieces, far inside MAX_WORD_LEN and bounded for DTW cost.
    const val MAX_DWELL_SEGMENTS = 4

    // Scoring.
    const val FREQ_WEIGHT_FLOOR = 0.25f

    // Bigram context boost: BigramTable.multiplier returns
    // 1 + BIGRAM_BOOST_MAX * byte/255, so the range is [1.0, 2.0]. Two things
    // changed after the first ship: the boost became CONDITIONED ON THE FIT, the
    // way the personal boost is - both go through appliedBoost below - and the
    // cap came down from 1.5.
    //
    // Lowered 2026-08-18, and the measurement is why it is 1.0 and not something
    // between. Over 219 final-buffer rows from the three captures taken under the
    // current formula, EVERY cap in [0.5, 1.0] moves exactly one top-1 - the
    // `her`/`here` row that item 16 had left open, where `here` fits 0.31 against
    // `her`'s 0.54 and loses to a bigram of 2.19 raw. The plateau is twice as wide
    // as the change, so this is not a knife edge; 1.0 is its top, i.e. the least
    // change that reaches the fix. Below 0.5 real regressions start (3 rows at
    // 0.25, 5 at 0.0) and above 1.0 the fix is not reached at all.
    //
    // Two rows that also move at 1.0 are NOT costs, and checking that mechanically
    // rather than by eye is what made the result readable: `decode out` fires once
    // per pointer lift, so a row whose token list is a strict prefix of the next
    // row's is a word still being drawn. Both were - one grows a second swipe and
    // becomes `solte`, the other grows two taps and becomes `held`. The surviving
    // row's intent is proved outright: the next decode is tap[h] tap[e] tap[r]
    // tap[e], the word being re-typed by hand.
    //
    // The population is those three captures alone, deliberately. Six of the
    // eleven predate the score formula that ships today and four predate the fit
    // conditioning, so their printed boosts came from rules that no longer exist;
    // recovering raw values from them means inverting three superseded rules in
    // sequence, which is exactly the step that once credited a change with fixes
    // that had already been made.
    //
    // Why it needed conditioning at all. The byte is normalized per previous
    // word against that word's own maximum (BigramTable.build), so the argmax
    // continuation of EVERY prev word quantizes to 255 and earns the full 2.5x:
    // the ceiling is routine, not theoretical, and it is larger than any
    // personal boost observed on a device. Once the geometric term was capped
    // and the personal boost conditioned, this was the last unbounded,
    // unconditioned factor in the product, and it took two "mujer" gestures to
    // "me" with pb=1.0 on the winner (me d=0.90 bm=1.96 against mujer d=0.40).
    //
    // Measured over ten device captures, 557 decode rows with >= 2
    // candidates. Two method points, because the first measurement got them
    // wrong and the corrected one changes the answer:
    //  - Six captures predate the saturating geometric term and four predate
    //    the conditioned personal boost, so their printed `s` came from a
    //    formula that no longer ships. The baseline must be RE-RANKED under the
    //    current formula first (the `sat` inversion
    //    ScoreWeightingTest.shippedScore already does); 110 of the 557 rows rank
    //    differently today than their capture shows. Regating the printed
    //    ranking instead credits this rule with rows those two earlier changes
    //    had already fixed: state>sudare and the>there belong to the saturating
    //    term, close>computer to the conditioned personal boost.
    //  - The condition must read geoFit, not dTotal. Measured consequence: none,
    //    and that is worth knowing rather than assuming. All 82 candidates
    //    carrying bm > 1 past the cap are SWIPE (78) or MERGED (4), and the
    //    source precedence in WordPredictor.emit puts FUZZY_TAP ahead of both,
    //    so those rows carry no tap penalty and geoFit == dTotal exactly. ZERO
    //    tap or completion candidates carry a bigram past the cap.
    //
    // Why the shape is a fade and not an outright drop past the cap. A hard
    // gate moves 10 tops and fixes four flagship rows, but it loses a real
    // "sempre" gesture at d=0.55 - five hundredths past the cap, carried
    // entirely by bm=1.91 against "stremo" at 0.45. That is the documented cost
    // of dropping a boost outright ("a reinforced poor fit loses its rescue")
    // landing on a resume-family flagship on real thumbs. Below a retained
    // fraction of ~0.20 a flat retention also promotes the proper noun "connor"
    // over "come" on the very path where the guard forbids exactly that, and
    // neither word is the intended one there. Sweeping a flat retained fraction
    // k of the boost above 1:
    //   k      0.05-0.13   0.14-0.18   0.20-0.32   >= 0.34
    //   moves      8           7        6 (chosen)     4
    //   loses   sempre    connor row       -            -
    // The plateau is 1.6x wide. Compressing the boost EVERYWHERE (bm^b) is a
    // different thing and stays rejected - it reaches 2 rows only at b ~ 0.1,
    // which deletes context prediction (see GEO_EXPONENT above).
    //
    // boostWeight subsumes that sweep rather than contradicting it: k above was
    // the value the boost needed AT d=0.55, its blocker row, and the fade gives
    // 0.79 there - so the sweep's constraint is satisfied with room. What the
    // fade changes is the two ends. It applies no constant from the cap
    // outward, so a bigram on a HOPELESS fit is gone rather than merely reduced,
    // and that is what fixes the residual a flat retention leaves behind: with
    // every candidate's boost at zero past one key hop, an all-saturated row
    // falls through to fw and "nosotros" - the highest fw in that row - finally
    // leads it. Past the cap it is fw that ought to decide, and the fade is what
    // lets it, one window further out.
    //
    // The ratio guarantee is strengthened by the fade, not lost. An affine
    // retention COMPRESSES the ratio between two saturated candidates toward 1
    // rather than preserving it, which makes it weaker than the personal
    // boost's rule; under a weight that reaches zero, two candidates past one
    // key hop have their boosts erased outright and the ratio is preserved
    // again. In between, the compression is monotone in the fit, so the rule
    // still can never move a contest away from the better fit
    // (ScoreWeightingTest.theBigramConditionNeverFavoursTheWorseFit).
    const val BIGRAM_BOOST_MAX = 1.0f

    // Geometric term of the candidate score:
    //   score = fw * geometricTerm(dTotal) * bigram * personalBoost
    //
    // This was previously a bare 1/(1+d), which varies only 1.0 -> 0.4
    // across the useful distance range while fw*bm*pb spans ~3x. A frequent,
    // context-boosted or personally-reinforced word with mediocre shape
    // therefore outranked a near-perfect fit: "the" (d=0.56) beat "there"
    // (d=0.22), "state" beat "sudare" (d=0.18), "come" beat "computer".
    //
    // Two parameters, and the SATURATION is what makes the fix possible at
    // all. Sharpening alone cannot work: the opposing constraint is that
    // "sarei" (d=1.06) must keep beating "sergei" (d=0.59) on frequency, and
    // on the measured numbers that caps any pure 1/(1+d)^g at g < 1.06 - below
    // the g >= 1.44 the "sudare" row needs. The two demands are jointly
    // infeasible for every unbounded monotone function of d (measured on both
    // the device captures and their reconstructions; exp(-d/tau) fails
    // identically, being the same one-parameter trade).
    //
    // What separates them is WHERE the intended word sits. Every target of the
    // first group is a good fit losing to a poor one (d 0.15-0.33 vs 0.4-1.0);
    // "sarei" is the opposite, a poor fit that must win on frequency (d=1.06).
    // So: a good geometric fit is informative and is scored steeply, and past
    // GEO_SATURATION_KW a fit carries no further information - a d=0.6 match
    // and a d=1.5 match are both "this is not the shape you drew" - and
    // frequency decides among them, which is exactly what carries "sarei".
    //
    // Values swept jointly over 42 probe rows (every such contest on its
    // measured numbers AND on its reconstructed path) plus the full JVM suite.
    // Zero-regression fixes, bigram and personal weighting left untouched:
    //   dsat      0.45  0.50  0.55  0.60  0.70
    //   g=2.50       6     7    10    10   x1
    //   g=3.00       8     9    11    x1   x1
    //   g=3.75      10    13    x1    x1   x1
    //   g=5.00      15    16    x1    x1   x3
    // 3.75/0.50 sits inside a broad plateau rather than on a knife edge
    // (0.45-0.60 x 2.5-4.0 is monotone and regression-free), which is why it
    // is trusted; the greedier 5.00 rows were left on the table because the
    // gain past 3.75 is all reconstruction rows whose device twins are
    // already fixed, and a steeper term buys nothing on real geometry.
    // 0.50 kw is also just above R_ENDPOINT_KW/3 and well under the 1.0 kw
    // key hop, i.e. "still inside the intended key" is the informative zone.
    //
    // Compressing the bigram and personal boosts instead (bm^b, pb^b) was
    // swept too and rejected: it reaches at most 2 rows without regressions,
    // and only at b ~ 0.1, which effectively deletes context prediction.
    //
    // GEO_SATURATION_KW carries two further rules that reuse this same bound
    // rather than introduce a threshold of their own, so all three move
    // together if it is ever re-derived: WordComposer.merge rule 3b (a foreign
    // candidate may lead only while its own fit still carries information),
    // and boostWeight below, which fades BOTH multipliers over the window
    // [GEO_SATURATION_KW, 2 * GEO_SATURATION_KW] and additionally reuses this
    // term's own floor as the normalizer. The two boosts used to have separate
    // rules here, each with a discontinuity at the cap; see appliedBoost for
    // why they became one.
    const val GEO_EXPONENT = 3.75f
    const val GEO_SATURATION_KW = 0.5f

    /** Geometric factor of the score; 1.0 at a perfect fit, floored past saturation. */
    fun geometricTerm(dTotal: Float): Float {
        val d = if (dTotal < GEO_SATURATION_KW) dTotal else GEO_SATURATION_KW
        return 1f / pow1p(d)
    }

    /**
     * Exact inverse of [geometricTerm] for the DTW early-abandon budget: the
     * largest dTotal at which [numerator] * geometricTerm(d) still beats
     * [minScore]. POSITIVE_INFINITY when even a saturated fit clears it (no
     * useful budget exists), <= 0 when nothing can.
     *
     * This MUST track [geometricTerm] exactly. A bound that is too tight makes
     * the prune drop candidates that would have won; too loose only costs
     * latency. WordPredictor is the sole caller and the pair is tested for
     * round-trip agreement (ScoreWeightingTest).
     */
    fun maxDTotalForScore(numerator: Float, minScore: Float): Float {
        if (minScore <= 0f) return Float.POSITIVE_INFINITY
        val ratio = numerator / minScore
        if (ratio <= 1f) return 0f
        val d = Math.pow(ratio.toDouble(), 1.0 / GEO_EXPONENT).toFloat() - 1f
        return if (d >= GEO_SATURATION_KW) Float.POSITIVE_INFINITY else d
    }

    /** [geometricTerm] past saturation: the share of a perfect fit's score a
     *  hopeless one still keeps. Used as the normalizer in [boostWeight]. */
    private val SATURATED_TERM = geometricTerm(GEO_SATURATION_KW)

    /**
     * How much confidence a boost - personal or bigram - is given on a
     * candidate whose own geometric fit is [dGeometric]. 1.0 while the fit is
     * inside [GEO_SATURATION_KW], then faded CONTINUOUSLY to 0.0 by one
     * further saturation width, i.e. by `2 * GEO_SATURATION_KW` = 1.0 kw =
     * one whole key.
     *
     * The fade is [geometricTerm] applied to the EXCESS distance and
     * renormalized onto [0, 1]; it therefore owns no tunable of its own, and
     * the two ends are the two statements the score already makes:
     *  - at the cap a fit still discriminates, so a boost is fully believed;
     *  - at one key hop the gesture is somewhere else entirely, so a boost is
     *    not evidence about it at all.
     *
     * [dGeometric] is the pure DTW mean, NOT dTotal - tap substitution and
     * completion penalties are charges for letters that were never drawn, so
     * they say nothing about fit and must not fade the boost. An all-tap
     * decode has `geoFit = 0f` and so is always weighted 1.0, which is what
     * leaves autocorrect and PersonalWeightTest byte-identical.
     *
     * Result is in [0, 1] and non-increasing in [dGeometric] - the two
     * properties every guarantee below rests on.
     */
    fun boostWeight(dGeometric: Float): Float {
        val excess = dGeometric - GEO_SATURATION_KW
        if (excess <= 0f) return 1f
        // geometricTerm saturates its own argument, so this reaches exactly
        // 0 at excess >= GEO_SATURATION_KW and stays there.
        return (geometricTerm(excess) - SATURATED_TERM) / (1f - SATURATED_TERM)
    }

    /**
     * A boost - personal or bigram - as APPLIED to a candidate score: it keeps
     * [rawBoost] while the candidate's own geometry still carries information
     * and fades to 1.0 as that information runs out. See PERSONAL_BOOST below
     * for the measurement.
     *
     * ONE function for both boosts, replacing an earlier on/off gate on the
     * personal boost and a flat retention on the bigram. Both of those were
     * discontinuous at the cap, where [geometricTerm] is not, and the personal
     * boost's step was measured deciding real rows: "sempre" led at
     * d=0.37/0.47 carrying pb=1.54 and lost at d=0.50/0.60 carrying pb=1.0,
     * four for four on the threshold in one capture, so 0.03 kw of fit
     * inverted a 1.22x boost advantage.
     *
     * Never exceeds [rawBoost] and never falls below 1.0. WordPredictor.emit's
     * abandon budget is computed from the RAW value and depends on the first
     * inequality to stay admissible; ScoreWeightingTest asserts both.
     */
    fun appliedBoost(rawBoost: Float, dGeometric: Float): Float =
        1f + (rawBoost - 1f) * boostWeight(dGeometric)

    private fun pow1p(d: Float): Float =
        Math.pow((1f + d).toDouble(), GEO_EXPONENT.toDouble()).toFloat()

    // Personal adaptive weighting:
    //   final_score = base_score * (1 + PERSONAL_BOOST * ln(1 + personalCount))
    // where personalCount increments on every final commit of the word.
    // Why 0.15 and log: the frequency-weight ratio between a top-frequency
    // word (freqByte ~230, fw ~0.93) and a mid-frequency rival (freqByte ~140,
    // fw ~0.66) is ~1.4x, so the boost must reach ~1.4x within a realistic
    // number of picks: 20 picks give 1 + 0.15*ln(21) = 1.46 (rank flip, e.g.
    // "thou" over "you"), 5 picks give 1.27 (visible climb in the strip). The
    // log keeps heavy reinforcement bounded so bigram context (max 1.5x)
    // stays competitive and one obsessively-typed word cannot swallow the
    // whole strip.
    //
    // The boost is BOUNDED BY THE FIT IT BOOSTS: WordPredictor weights it by
    // the candidate's geometric fit - the pure DTW mean, before tap and
    // completion penalties - through boostWeight above. The value 0.15 is
    // UNCHANGED; what changed is where it applies. That shipped first as an
    // on/off gate at GEO_SATURATION_KW and was later replaced by the continuous
    // fade, for the reason recorded under cost (2) below.
    //
    // Why, and why not a ceiling. Unbounded, the log reaches 1.61x at ~59
    // recorded commits of "me" and 1.83x on "che", which is enough to
    // overturn a clear geometric win: swiping "mujer" (d=0.384) committed "me"
    // (d=0.969) on a device. Solving the measured contests exactly gives an
    // EMPTY interval for any d-blind ceiling C:
    //     mujer > me                     needs C < 1.2336  (count cap < 3.8)
    //     keyboard > leonard             needs C < 1.1651  (count cap < 2.0)
    //     twentyCommitsFlipTheRanking    needs C > 1.4340  (fw 0.99118/0.69118)
    // and the shipped pb(20) = 1.45668 clears that last bound by only 1.6%. A
    // ceiling is also refuted independently: swept at C = 1.15 over 458
    // measured rows it moves 22 tops, EIGHT of them single-letter taps going wrong
    // (o -> p, a -> s, c -> v). The boost's magnitude is load-bearing exactly
    // where the fit is good and harmful exactly where it is bad, and a bound
    // blind to d cannot tell those apart.
    //
    // Conditioning on the fit can, with no constant of its own: 16 of 458 rows
    // move, all three targets clear, and all 54 rows where a flagship word
    // carries pb > 1.2 (quindi, sempre, interessante, cuando, siempre, un, in)
    // keep their top-1. Stated once: a personal count tells you WHICH of two
    // plausible words you meant; it is not evidence that a poor fit is what you
    // drew. Same principle as the saturation above, one term to the right.
    //
    // The condition reads the geometric mean and not dTotal on purpose. A
    // COMPLETION's distance is COMPLETION_PENALTY_PER_LETTER per unseen letter
    // ("they" from the "t,h" prefix lands at exactly 0.50) and a FUZZY_TAP's is
    // a substitution charge; neither measures shape, so neither may attenuate
    // the boost - otherwise reinforcedCompletionClimbs would turn on a
    // floating-point boundary. An all-tap decode therefore scores
    // bit-identically to before, which is what leaves autocorrect untouched.
    //
    // The rule is a MONOTONE REFINEMENT: for two candidates the score ratio
    // moves in favour of the better fit and never against it, which is what
    // bounds the saturating term's regression surface. Under the earlier gate
    // it was unchanged when both fits sat on the same side of the cap; under
    // the fade it is unchanged when both are in-cap or both past one key hop,
    // and monotone in between.
    //
    // WHY A FADE AND NOT A THRESHOLD. The gate's step edge turned up on real
    // gestures, so the shape was re-derived against the CURRENT population
    // - eleven captures, 2,965 candidate tuples over 662 multi-candidate
    // rows, every row re-ranked under today's formula first because six
    // captures predate the saturating geometric term and four predate the
    // conditioned boosts (113 of the 662 rank differently today than their
    // capture printed, so regating the printed order would have credited this
    // rule with rows those earlier changes had already fixed).
    //
    // Weighting the boost by w(d), score = fw * geo(dTotal) * (1+(bm-1)w) *
    // (1+(pb-1)w), the candidate shapes resolve cleanly:
    //   w = geo(d) everywhere            re-breaks the "mujer" row outright
    //                                    (0.2397 against me's 0.2460): it
    //                                    dilutes the IN-CAP winner while the
    //                                    past-cap rival keeps the floor
    //   w = flat retention past the cap   misses "sempre" at d=0.60 by 0.44%
    //                                    and "mujer" at 0.51 - a smaller step
    //                                    is still a step
    //   w = fade over the excess          all four targets, every protected row
    //                                    the captures can decide, 11 moves
    // Held to the same plateau standard as GEO_EXPONENT: expressing the fade
    // width in units of GEO_SATURATION_KW, everything from 0.75 to 2.5 clears
    // all four targets and 7 of 8 protected rows. That is 3.3x wide - twice
    // BIGRAM_BOOST_MAX's plateau - and the shipped width sits inside it.
    //
    // The width is 1.0 because that is the only value with an anchor rather
    // than a fit: the fade completes at 2 * GEO_SATURATION_KW = 1.0 kw, one
    // whole key, the unit every geometric constant in this file is written in.
    // A candidate a full key away from what was drawn is not a candidate the
    // gesture has any opinion about, so nothing it carries should be believed.
    //
    // The one protected row that moves is undetermined by the captures rather
    // than lost: one "computer" over "come" row wins by 0.8% today and loses by
    // 0.5% after, and fw prints truncated to 2 dp, so the true ratio straddles
    // the verdict under BOTH formulas. Rows that fail that test are excluded
    // from the acceptance set instead of tuned against - the same discipline
    // that keeps reconstructions out of ranking fixtures. The same contest with
    // a real margin, elsewhere in the capture, holds under both.
    //
    // Rows the fade moves and the gate did not, beyond the four targets: five
    // where the better fit takes the row from a bigram-carried worse one
    // (he->her, ne->me x2, llego->llevo, visto->chiamato), and one cost -
    // "mo"(d=0.45, pb=1.29) loses to "no"(d=0.68, pb=1.57). That row is the
    // intent-unverifiable class this file declines to write goldens from (a
    // stray RIGHT-swipe fragment whose contacts never touched "n"), and it is
    // genuinely decided, not noise. It is the price of restoring the boost at
    // d=0.68, which is also what wins "sempre" at 0.60 - the two sit 0.08 kw
    // apart with boosts within 2% of each other, so no width separates them.
    // The same interleaving of populations that ruled out a dwell-duration
    // threshold and an earlier language-detect margin.
    //
    // Three known limitations, recorded rather than left to be rediscovered.
    // (1) It trades "personal history can overrule geometry" for "geometry
    // overrules personal history once the fit stops carrying information", and
    // the rows where the boost earns its keep are exactly the rows where the
    // intended word fits badly ("sarei" at d=1.06 must beat "sergei" at 0.59,
    // while "atei" at 0.377 sits inside the cap). The fade softens this rather
    // than removing it: a reinforced word just past the cap keeps most of its
    // rescue, and only one a whole key away loses all of it.
    // (2) THE STEP EDGE, which the earlier gate had and boostWeight fixes. The
    // gate was discontinuous at the cap where geometricTerm is not, so a
    // candidate at 0.49 and the same candidate at 0.51 differed by the whole
    // boost, and a device capture caught it deciding "sempre" four times. The
    // obvious linear ramp is NOT the answer and measurement is what showed it:
    // scaling by 1 - min(dGeo, cap)/cap dilutes GOOD fits too (a d=0.30 word
    // keeps 40%), which re-broke the device-verified "mujer" row. What works is
    // to leave the in-cap boost alone - so every saturating-term contest, every
    // tap decode and twentyCommitsFlipTheRanking are byte-identical - and fade
    // only the excess. See boostWeight.
    // (3) A boost past one whole key hop is zero rather than merely small,
    // which is what lets an all-saturated row fall to fw. That is deliberate
    // and is how the "nosotros" row was finally won, but it means a heavily
    // reinforced word can no longer rescue a gesture that landed on different
    // keys at all. If that ever costs a real word, the lever is the fade WIDTH
    // (the plateau runs 0.75-2.5 in units of GEO_SATURATION_KW), not a return
    // to a threshold.
    //
    // Note the badge in the suggestion bar still reports the raw count, so a
    // heavily reinforced word can show seven dots and not move: reinforcement
    // no longer buys rank for a poor fit.
    const val PERSONAL_BOOST = 0.15f

    // Personal words merge into the active language's trie at dictionary
    // load, scaled by USER_FREQ_SCALE so a handful of real uses competes
    // with corpus counts spanning millions - but only once the word has
    // been committed PERSONAL_MERGE_MIN_COUNT times. Floor rationale, from a
    // live trace of the failure it prevents: a single stray commit - a language-swap
    // misfire ("sonore"/"imposte" landing while es was active), a typo
    // literal, a garbage merged decode - must not become a decodable trie
    // citizen, because swipe decode then re-commits it and every
    // auto-commit re-learns it: a self-reinforcing loop that permanently
    // poisons the dictionary. Two commits is the smallest signal
    // distinguishable from one accident. The floor also keeps rows
    // de-reinforced to zero from resurrecting as freqByte-1 ghosts through
    // the max(1, ..) quantizer ("quinndi").
    const val PERSONAL_MERGE_MIN_COUNT = 2
    const val USER_FREQ_SCALE = 1000

    // Suggestion-bar badge tiers: 1..7 dots (1 center + up to 6 hexagon
    // corners). Thresholds double per tier - counts 1,2,4,8,16,32,64 - so a
    // couple of uses already advance the badge visibly while tier 7 lands at
    // 64 uses (inside the 50-100 target), and equal visual steps correspond
    // to equal multiplicative effort, matching the ln-shaped ranking boost.
    // Manual reinforcement (+1/+5/+10) feeds the same count, so a +10 boost
    // jumps several tiers at once by construction.
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

    // Autocorrect: geometric confidence 1/(1+dTotal) must exceed this. The
    // selectable levels are off / normal / aggressive (arrays.xml,
    // KeyboardConfig.from); a "conservative" 0.90 tier existed as a constant
    // but was never wired to a setting and was removed as dead code.
    const val AUTOCORRECT_CONF_NORMAL = 0.85f
    const val AUTOCORRECT_CONF_AGGRESSIVE = 0.80f

    // Per-word cross-language ranking (swipe words only) has NO constants of
    // its own, and that is the result of a measurement rather than an
    // omission. WordComposer.merge ranks every enabled language's candidates
    // into one list; the two constants that used to gate a whole-list swap,
    // LANG_DETECT_LOW_CONF (0.85) and LANG_DETECT_MARGIN (1.15), were deleted
    // together with the swap itself.
    //
    // Why nothing replaced them:
    //
    // 1. No cross-dictionary frequency normalization is needed. freqByteFor
    //    already log-quantizes each asset against its OWN maximum count, and
    //    the four bundled assets are the same construction (hermitdave
    //    FrequencyWords top-50k, OpenSubtitles), so fw at matched rank
    //    percentiles agrees to within 1.04-1.07x across en/it/es/pl (measured:
    //    p50 0.5265/0.5265/0.5382/0.5471,
    //    p99 0.4706/0.4706/0.4882/0.5029). Against relative corpus frequency
    //    the spread remains small. geometricTerm moves 1.335x between d=0.25
    //    and d=0.35, so the residual asset bias is worth under 0.025 kw of
    //    distance - an order of
    //    magnitude below what decides any ranking. The roadmap's stated risk
    //    ("the larger asset wins everything") does not materialize here, and
    //    would have to be re-measured only for an asset built differently -
    //    an AOSP-merged import (DictionaryStore.wordlistOverride) is the one
    //    shipped path that can produce one.
    // 2. Had normalization been needed it could only be a per-language
    //    MULTIPLICATIVE constant. The score is a product, so percentile-rank
    //    or z-score remapping of fw reorders candidates WITHIN a language and
    //    would move every geometric-term golden; a constant factor cannot.
    //    Measured on the device rows, the good and bad merge outcomes overlap
    //    in score ratio (1.006-1.31 against 1.006-1.10), so no such constant
    //    separates them either - the same interleaving of populations that
    //    ruled out a dwell-duration threshold and the confidence ratio below.
    // 3. The one bound the merge does use is GEO_SATURATION_KW, borrowed from
    //    the score's own geometric term rather than tuned: a foreign candidate
    //    may lead only while its fit still carries geometric information.
    //    See WordComposer.merge for the rules and their measured effect.
    //
    // --- the deleted swap gate's derivation, kept because it is what proved
    // --- single-gesture language detection cannot work between two Romance
    // --- languages, and any future attempt has to start from it
    //
    // Both values HELD at their last re-derivation, which re-swept them against
    // the search-budget recall fix (that fix moves the gate's only operand - the
    // active language's best fit - so the previous sweep had measured a decoder
    // that was starving the active language). 91 decodes this time: 48
    // synthetic Italian rows, 28 synthetic Spanish-only rows, and 15 rows
    // rebuilt from the `keys=` contacts of the three device captures. The third
    // population is new and it is the one that matters, because it refuted the
    // previous derivation's central claim.
    //
    // 1. MARGIN 1.15 is held, and it is now known to be near its floor, not
    //    comfortably above a clean gap:
    //      - synthetic same-language rows still ceiling at exactly 1.000 (a word
    //        bundled in both dictionaries gets identical geometry from both);
    //      - but DEVICE-reconstructed same-language geometry reaches 1.095 (the
    //        `siete` gesture: Spanish "suerte" d=0.188 fits the recorded real
    //        path better than Italian "siete" d=0.301), because on a real path
    //        the active language's best fit is usually NOT the intended word;
    //      - and the foreign population reaches DOWN to 1.000 on the same
    //        device paths (ayudarte 1.000-1.100, cuando 1.015).
    //    So the two populations overlap in [1.000, 1.095]: no threshold
    //    separates them. Lowering the margin to catch the ayudarte/cuando class
    //    would hand `siete` back to Spanish - one of the three failures this
    //    gate was written to fix. 1.15 keeps every measured same-language row
    //    safe and detects 23 of 38 foreign rows; the missing 15 are a design
    //    limit of single-gesture detection between two Romance languages, and
    //    the answer was the merged ranking, not this constant.
    // 2. LOW_CONF 0.85 is held, and the sweep is blunt about what it does: in all
    //    53 same-language rows it is NEVER the binding gate - the margin (or the
    //    isWord veto) blocks every one of them - while it costs 4 genuine
    //    detections at 0.75 and 1 more at 0.85 ("ahora", pConf 0.852, ratio
    //    1.173). Active-language best-fit d still does not separate the
    //    populations (same-language 0.000-0.387, foreign 0.132-1.086 - they
    //    interleave, exactly as dwell duration does), so it must not be
    //    asked to discriminate. It stays as the cheap veto for the case the
    //    sweep never produced but the design assumes: a foreign-looking path the
    //    active language explains near-exactly. Raising it past ~0.9 is pointless
    //    anyway - an exact active-language match gives pConf 1.0, which no margin
    //    above 1.0 can beat, so the top end is self-protecting.
}

/** Letter codes: 0..25 = 'a'..'z', 26 = apostrophe. */
object Alphabet {
    const val SIZE = 27
    const val LETTERS = 26
    const val APOSTROPHE = 26

    fun codeOf(c: Char): Int = when (c) {
        in 'a'..'z' -> c - 'a'
        '\'' -> APOSTROPHE
        else -> -1
    }

    fun charOf(code: Int): Char =
        if (code == APOSTROPHE) '\'' else ('a' + code)

    fun encode(word: CharSequence): IntArray? {
        val out = IntArray(word.length)
        for (i in word.indices) {
            val c = codeOf(word[i])
            if (c < 0) return null
            out[i] = c
        }
        return out
    }
}
