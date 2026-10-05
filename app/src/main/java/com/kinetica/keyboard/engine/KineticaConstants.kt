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
    const val MAX_WORD_LEN = 24
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

    // Fuzzy tap fallback (adjacent-key typos) and transposition alternatives.
    const val FUZZY_TAP_RADIUS_KW = 1.0f
    const val FUZZY_TAP_LAMBDA = 0.15f
    const val TRANSPOSE_PENALTY = 0.15f

    /**
     * Tap charge, in kw, for a skipped stray tap or an untapped letter. 0.5 fixes 1 035 of
     * 2 008 dropped taps and 1 764 of 2 008 stray ones and changes no captured commit or lead
     * but 19 empty decodes; 0.25 cost three autocorrections. Negative: off.
     */
    const val TAP_EDIT_PEN_KW = 0.5f

    /** Fewest taps before one may be skipped or a letter added: a short word has too many neighbours. Reasoned, not swept. */
    const val TAP_EDIT_MIN_TAPS = 5

    // Live tap-typing completions (pick-only): once the exact all-anchor pass has consumed every
    // tapped letter, the search keeps descending and emits longer words as Source.COMPLETION.
    // Only the exact pass completes: completing fuzzy or transposed prefixes would dress typos up
    // as confident words.
    // Below 2 tapped letters a prefix matches too much of the dictionary to rank; one tap would
    // complete to thousands of words and bury the one-letter words.
    const val COMPLETION_MIN_PREFIX = 2
    // 8 extra letters on a 2-letter prefix reaches 10-letter words; longer completions are rarely
    // picked before the user types on, and subtree fan-out grows with every level
    // (MAX_CANDIDATES still bounds emission per pass).
    const val COMPLETION_MAX_EXTRA = 8
    // Additive dTotal per completed letter. Any positive value keeps a typed word ahead of its own
    // extensions; 0.25 is above a worst-case fuzzy substitution (FUZZY_TAP_LAMBDA * 1.0 kw = 0.15),
    // so an adjacent-key correction of similar frequency outranks a completion of the same length
    // and only much more frequent words complete past it.
    const val COMPLETION_PENALTY_PER_LETTER = 0.25f

    // Dual-stream merge ambiguity generators.
    const val ORDER_AMBIG_MS = 120L
    const val SPLIT_MARGIN_MS = 80L
    const val MIN_SPLIT_HALF_ARC_KW = 0.5f
    // Head trim for the second half of a split swipe, two roles:
    //   1. Fallback trim when the resume is one straight leg to the end: the half starts this far
    //      past the cut, so the tapped letter is consumed once, by the anchor (swipe h-e-l-o +
    //      tap l gives hel+l+o, not hel+l+lo).
    //   2. Travel a distance peak must clear to count as the first resumed letter, so the rest
    //      cluster at the cut is never read as a letter. splitSwipe resumes at the first interior
    //      distance-from-cut peak when there is one, which lets that letter pass the segment
    //      start-letter gate, and falls back to this trim otherwise.
    // Device sample jitter, which synthetic fixtures lack, may move the effective peak threshold.
    const val SPLIT_HEAD_TRIM_KW = 0.6f
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
     * The rescue pass runs when the lead's distance is over this, or nothing was
     * found. At 0.5 it cost 10 labelled leads; 1.0 cost none. Infinite turns it off.
     */
    const val RESCUE_MIN_LEAD_D = 1.0f

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
    const val BEAM_MAX_ATTEMPTS = 300

    /** Beam priority per unit of -ln(best frequency below): pulls common subtrees forward. */
    const val BEAM_FREQ_WEIGHT = 0.5f

    /** Beam priority charge, in kw, for a DFS gate the reading breaks (start or end radius). */
    const val BEAM_GATE_COST = 0.5f

    /** Score factor per broken gate on a finished beam reading, as the rescue's. */
    const val BEAM_GATE_KEEP = 0.6f

    /** New words the beam may add to the passes' list; the rest of the list keeps its order. */
    const val BEAM_MAX_NEW = 2

    /** Whether a beam reading is judged by the geometric term without its floor. */
    const val BEAM_UNSATURATED = true

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
     * `ottima` from one `t` and `tutti` from two cannot be read at all. At 0.1 hand-checked
     * leads go 26 -> 34 and labelled top-1 321 -> 324, no word to empty; 0 doubles short tapped
     * words (`trace` -> `tracce` x18) and 0.3 gives the gain back.
     */
    const val DOUBLE_TAP_PEN_KW = 0.1f

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
    // GEO_SATURATION_KW also bounds WordComposer.merge rule 2b and the boostWeight fade, so all
    // three move together.
    const val GEO_EXPONENT = 3.75f
    const val GEO_SATURATION_KW = 0.5f

    /**
     * A swiped word the active language cannot spell at all may still commit from the other
     * language when it fits this closely: below the 0.35 and 0.40 kw fits of the two empty
     * `parlare` decodes that once committed `patéale`. 0.25 and 0.30 both gain 3 of 487 labelled
     * foreign rows with none lost; 0.25 changes 26 of 8 692 captured leads, 0.30 changes 33.
     */
    const val NO_NATIVE_LEAD_KW = 0.25f

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

    /** Geometric factor of the score; 1.0 at a perfect fit, floored past saturation. */
    fun geometricTerm(dTotal: Float): Float {
        val d = if (dTotal < GEO_SATURATION_KW) dTotal else GEO_SATURATION_KW
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
        require(size <= MAX_SIZE) { "$script needs $size codes, the trie holds $MAX_SIZE" }
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
