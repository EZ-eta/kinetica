package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.PathPoint
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.TapToken
import kotlin.math.abs

/**
 * Turns the raw token buffer into the merged sequences to decode.
 *
 * Primary order = sort by tStart: in sustained dual-thumb typing the *start*
 * of each gesture is the user's sequencing signal. Two bounded generators add
 * alternatives for ambiguous timings; wrong alternatives find
 * no dictionary words and cost microseconds.
 */
object MergeAlternatives {

    /**
     * Every generator's alternatives, and how the budget was spent on them.
     *
     * Each generator builds its own list and the budget is spent by concatenating them in a fixed
     * order, so a trace can tell a generator that rejected every cut from one the cap never
     * reached. On one capture, 89 of 186 cross-stream taps landing inside a swipe were never
     * examined by the mid-swipe split, and with two swipes each carrying an interior tap it
     * produced nothing on 11 of 11 buffers.
     *
     * Reallocating the budget does not pay: an equal round robin, a per-generator ceiling and a
     * reservation each gain two or three multi-swipe buffers and lose four to seven swipes-only
     * ones. The readings a starved generator would build are mostly refused
     * by the segment gates anyway.
     */
    fun sequences(tokens: List<InputToken>, resampler: DtwMatcher): List<List<InputToken>> {
        val primary = tokens.sortedBy { it.tStart }
        val result = ArrayList<List<InputToken>>(4)
        result.add(primary)
        if (tokens.size < 2) return result

        // Built eagerly, each into its own list. A producer's own cap is the same
        // MAX_ALT_SEQUENCES, so construction cost is bounded per generator; the
        // *LatencyIsBounded goldens police the total.
        val producers = listOf(
            Alternatives("anchorIL", buildList { addAnchorInterleaves(primary, resampler, this) }),
            Alternatives("crossIL", buildList { addCrossSwipeInterleaves(primary, resampler, this) }),
            Alternatives("orderSwap", buildList { addOrderSwaps(primary, this) }),
            Alternatives("tapSplit", buildList { addTapSplits(primary, resampler, this) }),
            Alternatives("swipeAround", buildList { addSwipeAroundSwipes(primary, resampler, this) }),
        )

        // Concatenated in generator order. Sharing the budget more evenly costs more than it
        // buys, in empty decodes on multi-swipe+taps / swipes-only buffers against 87/8:
        //
        //   equal round robin      85/12      per-generator ceiling 8   86/15
        //   reservation of 1       85/12      ceiling 12, cap 24        84/12
        //
        // The cross-swipe generator needs most of the budget on a swipes-only buffer, and the
        // readings a starved generator would build are refused by the segment gates anyway.
        for (p in producers) {
            while (p.taken < p.seqs.size &&
                result.size < KineticaConstants.MAX_ALT_SEQUENCES
            ) {
                result.add(p.seqs[p.taken])
                p.taken++
            }
        }

        // taken/produced per generator, and what the cap refused. Without this a
        // generator that rejected every cut and one that was never reached read
        // identically in a capture.
        DecodeTrace.log {
            val dropped = producers.sumOf { it.seqs.size - it.taken }
            "  seqs total=${result.size} primary=1 " +
                producers.joinToString(" ") { "${it.name}=${it.taken}/${it.seqs.size}" } +
                " dropped=$dropped"
        }
        return result
    }

    /** One generator's alternatives, and how many of them the budget took. */
    private class Alternatives(val name: String, val seqs: List<List<InputToken>>) {
        var taken = 0
    }

    /**
     * Order swap: adjacent cross-stream tokens starting near-simultaneously.
     *
     * Which of two thumbs "went first" is not decidable inside ORDER_AMBIG_MS, so
     * both orders are offered and the dictionary settles it.
     */
    private fun addOrderSwaps(
        primary: List<InputToken>,
        result: MutableList<List<InputToken>>,
    ) {
        for (i in 0 until primary.size - 1) {
            if (result.size >= KineticaConstants.MAX_ALT_SEQUENCES) return
            val a = primary[i]
            val b = primary[i + 1]
            if (a.streamId != b.streamId &&
                abs(a.tStart - b.tStart) < KineticaConstants.ORDER_AMBIG_MS
            ) {
                val v = ArrayList(primary)
                v[i] = b
                v[i + 1] = a
                result.add(v)
            }
        }
    }

    /**
     * Mid-swipe split: a deliberate tap on the other thumb, timed well inside a
     * swipe, inserts its letter at that point in the swipe (the explicit
     * double-letter mechanism and general cross-thumb interleave).
     */
    private fun addTapSplits(
        primary: List<InputToken>,
        resampler: DtwMatcher,
        result: MutableList<List<InputToken>>,
    ) {
        for (tapIdx in primary.indices) {
            if (result.size >= KineticaConstants.MAX_ALT_SEQUENCES) return
            val tap = primary[tapIdx] as? TapToken ?: continue
            for (swipeIdx in primary.indices) {
                if (result.size >= KineticaConstants.MAX_ALT_SEQUENCES) break
                val swipe = primary[swipeIdx] as? SwipeToken ?: continue
                if (swipe.streamId == tap.streamId) continue
                // Everything the 80 ms start margin admits, plus an early tap that is interior
                // by the path. A widening, not a replacement: a late or post-lift tap is never
                // an interior cut, and the quindi late-cut regime needs those.
                //
                // The margin alone decided words on reaction time: `where` decoded `here` with
                // the tap at +45 and +78 ms and `where` at +89 and +106 ms, and this is the only
                // generator that can cut a one-swipe-one-tap buffer. 342 of 2 416 captured
                // cross-stream taps, one handover in seven, land under the margin.
                // isInteriorCut is the same guard against the path instead of the clock,
                // splitVariants clamps its own index, and sliceOrNull refuses a piece that
                // cannot carry a letter.
                if (tap.tStart <= swipe.tStart + KineticaConstants.SPLIT_MARGIN_MS &&
                    !isInteriorCut(swipe, tap.tStart)
                ) {
                    continue
                }
                // No dead zone before the swipe's end, and a grace window after it: a
                // reaction-timed cross-stream tap often lands in the last fraction of the
                // gesture or a beat after the lift (the right thumb is already reversing toward
                // its final key when the left taps). Rejecting those left "quindi" undecodable
                // as "QD"; splitSwipe carries the trailing letter, and wrong splits still find
                // no words.
                if (tap.tStart >= swipe.tEnd + KineticaConstants.ORDER_AMBIG_MS) continue
                // The tap's own instant, then the boundaries of whatever key this thumb was on
                // when it arrived. V1/V2/V3 are path-geometry guesses; a contact boundary is
                // direct evidence of where a letter ended, and it makes a swiped-from "where"
                // spellable. The geometry variants stay because they carry the device-verified
                // resume family, and the dedup by half boundaries drops any overlap.
                val variants = ArrayList<SplitVariant>(4)
                val seenHalves = HashSet<String>(4)
                val snaps = contactSnaps(swipe, tap.tStart)
                // The tap's own instant first, through the geometry variants, so the resume
                // family's orderings hold. Then the untrimmed contact cuts, the better evidence,
                // ahead of MAX_ALT_SEQUENCES. Then geometry at the snapped times.
                for (v in splitVariants(swipe, tap.tStart, resampler)) {
                    val sig = "${v.first.tStart}-${v.first.tEnd}-${v.second.tStart}"
                    if (seenHalves.add(sig)) variants.add(v)
                }
                for (cut in snaps.drop(1)) {
                    val v = splitAtContact(swipe, cut, resampler) ?: continue
                    val sig = "${v.first.tStart}-${v.first.tEnd}-${v.second.tStart}"
                    if (seenHalves.add(sig)) variants.add(v)
                }
                for (cut in snaps.drop(1)) {
                    for (v in splitVariants(swipe, cut, resampler)) {
                        val sig = "${v.first.tStart}-${v.first.tEnd}-${v.second.tStart}"
                        if (seenHalves.add(sig)) variants.add(v)
                    }
                }
                DecodeTrace.log {
                    "  tapsplit tap@${tap.tStart} in swipe[${swipe.tStart}..${swipe.tEnd}] -> " +
                        if (variants.isEmpty()) "rejected" else variants.joinToString(" ") { it.label }
                }
                for (halves in variants) {
                    if (result.size >= KineticaConstants.MAX_ALT_SEQUENCES) break
                    val v = ArrayList<InputToken>(primary.size + 1)
                    for (k in primary.indices) {
                        when (k) {
                            swipeIdx -> v.add(halves.first)
                            tapIdx -> {
                                v.add(tap)
                                v.add(halves.second)
                            }
                            else -> v.add(primary[k])
                        }
                    }
                    result.add(v)
                }
            }
        }
    }

    /**
     * Swipe-around-swipe: the other thumb's swipe starts inside this swipe's
     * interval, a hold-and-resume where the pause is filled by the other
     * stream's gesture (left swipes se, holds, right swipes mp, left resumes
     * re -> "sempre"). This swipe is split around it as around a tap, so the
     * interleave se|mp|re can be represented.
     */
    private fun addSwipeAroundSwipes(
        primary: List<InputToken>,
        resampler: DtwMatcher,
        result: MutableList<List<InputToken>>,
    ) {
        for (outerIdx in primary.indices) {
            if (result.size >= KineticaConstants.MAX_ALT_SEQUENCES) return
            val outer = primary[outerIdx] as? SwipeToken ?: continue
            for (innerIdx in outerIdx + 1 until primary.size) {
                if (result.size >= KineticaConstants.MAX_ALT_SEQUENCES) break
                val inner = primary[innerIdx] as? SwipeToken ?: continue
                if (inner.streamId == outer.streamId) continue
                if (inner.tStart <= outer.tStart + KineticaConstants.SPLIT_MARGIN_MS) continue
                if (inner.tStart >= outer.tEnd - KineticaConstants.SPLIT_MARGIN_MS) continue
                // Fully contained: cut at the inner gesture's midpoint, the middle of the pause
                // it filled, which decodes "sempre" on device.
                //
                // Partially overlapping (inner starts inside but ends after) is what two thumbs
                // moving at once produce, as in a captured "siempre" (inner 9015251..9015518
                // against outer 9014754..9015343). There the boundary is the moment the other
                // thumb started, and the split variants let the endpoint-trimmed tail carry a
                // short resumed piece.
                val contained = inner.tEnd < outer.tEnd - KineticaConstants.SPLIT_MARGIN_MS
                val halves = if (contained) {
                    val h = splitSwipe(outer, (inner.tStart + inner.tEnd) / 2, resampler) ?: continue
                    listOf(h)
                } else {
                    splitVariants(outer, inner.tStart, resampler).map { it.first to it.second }
                }
                for ((h1, h2) in halves) {
                    if (result.size >= KineticaConstants.MAX_ALT_SEQUENCES) break
                    val v = ArrayList<InputToken>(primary.size + 1)
                    for (k in primary.indices) {
                        when (k) {
                            outerIdx -> v.add(h1)
                            innerIdx -> {
                                v.add(inner)
                                v.add(h2)
                            }
                            else -> v.add(primary[k])
                        }
                    }
                    result.add(v)
                }
            }
        }
    }

    /** A cut inside a swipe, and the cross-stream token that goes at it (-1 = none). */
    private class Boundary(val t: Long, val tokenIdx: Int)

    /** One path piece plus the primary-index of the token that follows it. */
    private class Piece(val swipe: SwipeToken, val nextTokenIdx: Int)

    /**
     * Sequences that cut a swipe at every cross-stream boundary at once.
     *
     * The tap-split generator inserts one tap per sequence, so a swipe interrupted twice is cut
     * once. For a device "cuando" buffer (one left swipe c-u-n-o with right taps a and d inside
     * it) the representable orders were cuno|a|d, cu|a|no|d and cun|a|d|o, and the last spells
     * the "cuñado" that won; c,u,a,n,d,o needs cuts at both taps.
     *
     * Boundaries come from the other stream's event times, not this stream's stillness: on that
     * capture neither failing word had a dwell, while three of its four dwells were hesitations
     * inside ordinary single-thumb swipes.
     */
    private fun addAnchorInterleaves(
        primary: List<InputToken>,
        resampler: DtwMatcher,
        result: MutableList<List<InputToken>>,
    ) {
        for (swipeIdx in primary.indices) {
            if (result.size >= KineticaConstants.MAX_ALT_SEQUENCES) return
            val s = primary[swipeIdx] as? SwipeToken ?: continue
            // Two separate conditions: a dwell counts as a cut only for a swipe the other
            // stream is active in (the co-occurrence gate in boundariesFor), and only two cuts
            // need an interleave the single-cut generators cannot express. So one tap plus a
            // real dwell qualifies; one tap alone never does.
            val tokenOnly = boundariesFor(primary, swipeIdx, s, withDwells = false)
            if (tokenOnly.isEmpty()) continue
            val sets = ArrayList<List<Boundary>>(2)
            if (tokenOnly.size >= 2) sets.add(tokenOnly)
            if (s.dwells.isNotEmpty()) {
                val withDwells = boundariesFor(primary, swipeIdx, s, withDwells = true)
                if (withDwells.size >= 2) sets.add(withDwells)
            }
            val seen = HashSet<String>(8)
            for (boundaries in sets) {
                // (trim interior, trim final). The final piece's letters sit at its end, so it
                // is trimmed by default. Interior pieces need both readings: a single trailing
                // letter needs the trim to clear minLetters, a two-letter piece needs the
                // untrimmed span to clear the length band. The all-untrimmed pair covers a long
                // final piece.
                for ((trimInterior, trimFinal) in TRIM_MODES) {
                    if (result.size >= KineticaConstants.MAX_ALT_SEQUENCES) return
                    // contacted = true: a piece cut where the other thumb acted can be one key
                    // the finger was on, not a journey, and the arc minimum cannot tell that from
                    // noise. A multi-tap word needs it: `provando`'s head piece is the thumb
                    // sitting on `p` while the other hand taps `r`, which travels almost nothing,
                    // and without the flag the four-piece reading is discarded.
                    val pieces =
                        multiSplit(s, boundaries, resampler, trimInterior, trimFinal, contacted = true)
                            ?: continue
                    // Sample count disambiguates pieces whose timestamps coincide,
                    // which happens across a dwell's stationary run.
                    val sig = pieces.joinToString(",") {
                        "${it.swipe.tStart}-${it.swipe.tEnd}-${it.swipe.rawPath.size}"
                    }
                    if (!seen.add(sig)) continue
                    result.add(interleave(primary, swipeIdx, pieces))
                }
            }
        }
    }

    /**
     * Sequences that cut every swipe the other stream interrupted, at once.
     *
     * [addAnchorInterleaves] multiplies cuts within one swipe, and every other generator
     * substitutes at a single swipe index. So when the swiping thumb lifts mid-word and re-swipes
     * (two swipes with one interior tap each), only this generator cuts both. A device
     * "praticamente": left swipes r-a-t then c-a, lifts, swipes e then t-e, while right taps p, i,
     * m, n. The word needs [p][rat][i][ca][m][e][n][te], swipe 1 cut at tap i and swipe 2 at tap n.
     *
     * Fires only when at least two swipes carry an interior boundary, so single-swipe buffers keep
     * the other generators' sequences unchanged. One sequence per trim mode, deduped, so it cannot
     * starve the generators after it the way an unbounded product would.
     */
    private fun addCrossSwipeInterleaves(
        primary: List<InputToken>,
        resampler: DtwMatcher,
        result: MutableList<List<InputToken>>,
    ) {
        val perSwipe = LinkedHashMap<Int, List<Boundary>>()
        for (idx in primary.indices) {
            val s = primary[idx] as? SwipeToken ?: continue
            val b = contactBoundaries(primary, idx, s)
            if (b.isNotEmpty()) perSwipe[idx] = b
        }
        // Two cuttable swipes or nothing. A single swipe interrupted by taps belongs to the
        // tap-split and multi-anchor generators and is device-verified; letting it in here costs
        // budget and changes rankings the resume family depends on (the "sarei" golden).
        if (perSwipe.size < 2) return

        val seen = HashSet<String>(8)
        val idx = perSwipe.keys.toList()

        // Ordered single-cut pairs first. Over 506 recorded swipes with a known target,
        // 440 need one cut, 66 none and none two. Cutting at every candidate at once
        // divides a three-letter word into five to eight pieces, and since every segment must
        // consume a letter, that pattern spells nothing.
        //
        // The right cut is among the candidates 99% of the time, but the best selection
        // heuristic ("take the first") is right 61% of the time, so pairs are enumerated. In an
        // alternating reading the earlier swipe is interrupted first, so only pairs with a < b
        // are real: at most three pairs, and two of them capture 97% of correct readings.
        //
        // Over all pairs of cuttable swipes, not only when there are two: 3 of 74 multi-swipe
        // buffers on one capture have three cuttable swipes, 15 of 74 once a lift counts as a
        // handover, and without this they fall through to cutting everything at once.
        //
        // Nearest pairs first: an alternation hands over between consecutive runs, so (0,1) and
        // (1,2) are the readings a two-thumb word needs and (0,2) skips a run. MAX_ALT_SEQUENCES
        // binds here, and this is the order it spends the budget in.
        for (span in 1 until idx.size) {
            for (i in 0 until idx.size - span) {
                val first = idx[i]
                val second = idx[i + span]
                for (a in perSwipe[first]!!) {
                    for (b in perSwipe[second]!!) {
                        if (a.t >= b.t) continue
                        if (!emitCuts(
                                primary, resampler, result, seen,
                                mapOf(first to listOf(a), second to listOf(b)),
                            )
                        ) {
                            return
                        }
                    }
                }
            }
        }

        // One swipe cut, the other left whole: one thumb draws a run while the other draws a
        // single uninterrupted one. 66 of those 506 swipes need no cut.
        for (k in idx) {
            for (cut in perSwipe[k]!!) {
                if (!emitCuts(primary, resampler, result, seen, mapOf(k to listOf(cut)))) return
            }
        }

        // Every candidate at once, last, only if budget remains. No measured word needs it; it
        // covers a word with three or more runs from one thumb, unreachable otherwise.
        emitCuts(primary, resampler, result, seen, perSwipe)
    }

    /**
     * Cuts each swipe at its given boundaries in every trim mode, ordering the pieces
     * by time. Returns false when the sequence budget is spent.
     *
     * With a single cut there is no interior piece for [multiSplit] to build, so
     * `trimInterior` goes unread and the three trim modes collapse to two distinct
     * results; the signature dedup discards the third.
     */
    private fun emitCuts(
        primary: List<InputToken>,
        resampler: DtwMatcher,
        result: MutableList<List<InputToken>>,
        seen: MutableSet<String>,
        cuts: Map<Int, List<Boundary>>,
    ): Boolean {
        for ((trimInterior, trimFinal) in TRIM_MODES) {
            if (result.size >= KineticaConstants.MAX_ALT_SEQUENCES) return false
            val pieces = HashMap<Int, List<Piece>>(cuts.size)
            for ((swipeIdx, boundaries) in cuts) {
                val s = primary[swipeIdx] as SwipeToken
                pieces[swipeIdx] =
                    multiSplit(s, boundaries, resampler, trimInterior, trimFinal, contacted = true)
                        ?: break
            }
            if (pieces.size != cuts.size) continue
            val ordered = orderByTime(primary, pieces)
            if (!seen.add(ordered.joinToString(",") { "${it.tStart}-${it.tEnd}" })) continue
            result.add(ordered)
        }
        return true
    }

    /**
     * The two halves of [s] cut at [t], with nothing trimmed from either.
     *
     * [splitVariants] resumes the second half at the next apex, or else past
     * SPLIT_HEAD_TRIM_KW, because a cut derived from a time cannot know where the interrupted
     * letter ended. A cut derived from a key contact does: the letter ended when the finger left
     * the key, and everything after it is the next letter.
     *
     * The apex resume loses the middle of a swiped "where": its path runs w-e-r-t-r-e, the r-t-r
     * overshoot is a reversal, and the second half jumps past `e` and `r` to spell `re`. Cut at
     * the end of the `w` contact and take the rest whole, and the halves are `w` and `ertre`.
     */
    private fun splitAtContact(
        s: SwipeToken,
        t: Long,
        resampler: DtwMatcher,
    ): SplitVariant? {
        val path = s.rawPath
        if (path.size < 4) return null
        val cut = nearestIndex(path, t).coerceIn(1, path.size - 3)
        val first = sliceOrNull(s, 0, cut, resampler, softStart = false, softEnd = true, keepIfContacted = true)
            ?: return null
        val second = sliceOrNull(
            s, cut + 1, path.size - 1, resampler,
            softStart = true, softEnd = false, keepIfContacted = true,
        ) ?: return null
        return SplitVariant("vc[..$cut|${cut + 1}..]", first, second)
    }

    /**
     * Where a cut at [t] should fall inside [s], as key-contact boundaries.
     *
     * A cross-stream event says when the other thumb acted, not where this thumb had got to. On
     * a device capture every cross-stream event landed inside one of the swipe's key contacts,
     * never between two, and 64% in the first half of one, so a cut at the raw time is always
     * mid-letter. In a swiped "where" the tap of `h` arrives 42% of the way through the `w`
     * contact, the first piece ends inside `w`, and nothing spells it.
     *
     * Both boundaries of the enclosing contact are returned: its end gives the letter to the
     * earlier piece, its start to the later one, and every way of choosing between them has
     * measured worse. The raw time is kept too, since dropping it would move existing goldens.
     */
    private fun contactSnaps(s: SwipeToken, t: Long): List<Long> {
        val c = s.keyContacts.firstOrNull { t >= it.tEnter && t < it.tExit } ?: return listOf(t)
        return listOf(t, c.tExit, c.tEnter).distinct()
    }

    /**
     * Cut points inside [s] taken from the other stream's key contact entry times.
     *
     * [boundariesFor] cuts at the other token's start, so of two overlapping swipes only the
     * later one is cuttable (`o.tStart > s.tStart + SPLIT_MARGIN_MS`), and a whole token carries
     * one event time while an alternating word needs one boundary per handover.
     *
     * Over 202 stream changes inside words with a known target, contact entry order
     * never contradicted the letter order, at a median margin of 142 ms. Contact intervals
     * overlap heavily (a thumb rests on its key while the other moves, 82% of the time), which
     * does not matter here.
     *
     * Cuts closer together than [KineticaConstants.SPLIT_MARGIN_MS] collapse to the first: two
     * events that close are one event, and a cut on each would spend budget on a boundary no
     * letter sits at.
     */
    private fun contactBoundaries(
        primary: List<InputToken>,
        swipeIdx: Int,
        s: SwipeToken,
    ): List<Boundary> {
        // No time margin at the ends: a device "keys" buffer needs its right thumb cut where the
        // left one's first letter begins, 67 ms into a 421 ms gesture, which SPLIT_MARGIN_MS
        // rejects, and cutting at the next admissible contact orders the pieces k, e, s, y.
        // [isInteriorCut], applied below, is the same guard against the path, and it knows
        // whether a cut leaves usable gesture on both sides.
        val lo = s.tStart
        val hi = s.tEnd
        val times = ArrayList<Long>(8)
        for (k in primary.indices) {
            if (k == swipeIdx) continue
            val o = primary[k]
            if (o.streamId == s.streamId) continue
            when (o) {
                is TapToken -> if (o.tStart in lo..hi) times.add(o.tStart)
                is SwipeToken -> for (c in o.keyContacts) if (c.tEnter in lo..hi) times.add(c.tEnter)
            }
            // And the moment the other thumb left the keyboard: a handover is signalled as much
            // by one thumb finishing as by it starting a letter, and a thumb that reached its
            // last key before this swipe began would otherwise contribute no event.
            //
            // Only the lift, not every contact exit: contacts inside a swipe are contiguous (0
            // gaps in 571 adjacent pairs on one capture), so every other exit is the next
            // contact's entry and is already listed.
            //
            // On the three `happens` buffers where the word ranked second, the cut lands on the
            // handover instead of mid-letter, and the word reads at d = 0.349 / 0.358 / 0.333
            // instead of 0.478 / 0.560 / 0.380.
            if (o.tEnd in lo..hi) times.add(o.tEnd)
        }
        if (times.isEmpty()) return emptyList()
        times.sort()
        val out = ArrayList<Boundary>(KineticaConstants.MAX_SPLIT_ANCHORS)
        for (raw in times) {
            // The contact end first: a letter the thumb was still on when the other
            // one acted belongs to the piece being closed, not to the one starting.
            val t = contactSnaps(s, raw).firstOrNull { cand ->
                cand > s.tStart && cand < s.tEnd && isInteriorCut(s, cand) &&
                    s.keyContacts.any { it.tEnter >= cand }
            } ?: raw
            if (out.isNotEmpty() && t - out[out.size - 1].t < KineticaConstants.SPLIT_MARGIN_MS) continue
            if (!isInteriorCut(s, t)) continue
            // A cut is worth making only if this gesture still has a key to reach after it. The
            // other thumb's last letter usually lands while this thumb is already on its own
            // last key, and cutting there splits one contact in two and hands the pattern a
            // piece with no letter, which then needs a longer word. A contact of this swipe
            // beginning after the cut tells a handover from the other thumb finishing.
            if (s.keyContacts.none { it.tEnter > t }) continue
            out.add(Boundary(t, -1))
            if (out.size == KineticaConstants.MAX_SPLIT_ANCHORS) break
        }
        return out
    }

    /**
     * Every piece and every uncut token, in the order the thumbs produced them.
     *
     * Splicing each cut swipe's pieces into the primary order would emit one swipe's pieces and
     * then the other's when both are cut. One ordering by start time is right for one cut swipe
     * and for two, as the contact-entry measurement supports.
     *
     * [slice] keeps the gesture's own tStart for the first piece and the cut time for the rest,
     * so the sort key is the moment each piece began.
     */
    private fun orderByTime(
        primary: List<InputToken>,
        piecesBySwipe: Map<Int, List<Piece>>,
    ): List<InputToken> {
        val out = ArrayList<InputToken>(primary.size + piecesBySwipe.size + 2)
        for (k in primary.indices) {
            val pieces = piecesBySwipe[k]
            if (pieces != null) pieces.forEach { out.add(it.swipe) } else out.add(primary[k])
        }
        // Ties are real: a piece resumes at the instant the other thumb's contact begins,
        // since that contact cut it. The gesture starting goes first and the one resuming
        // follows, the order the thumbs produced; the reverse spells the resumed letter before
        // the letter that interrupted it.
        return out.sortedWith(
            compareBy({ it.tStart }, { if (it is SwipeToken && it.softStart) 1 else 0 }),
        )
    }

    /**
     * True when a cut at [t] leaves usable path on both sides of it.
     *
     * A boundary whose nearest sample is the gesture's last is a token following the whole
     * swipe, which the primary order already expresses (a device "praticamente" buffer taps `m`
     * 4 ms before the lift). [multiSplit]'s absorb-never-drop rule still holds for real interior
     * boundaries; letting this one clamp onto the tail nulled the whole interleave for that
     * buffer.
     */
    private fun isInteriorCut(s: SwipeToken, t: Long): Boolean {
        val path = s.rawPath
        if (path.size < 6) return false
        val idx = nearestIndex(path, t)
        return idx > 1 && idx < path.size - 3
    }

    /**
     * Cut points inside [s] contributed by the other stream, using the single-cut generators'
     * acceptance windows, so no timing they reject becomes acceptable here.
     *
     * Dwells are added only when the other stream is active inside this gesture ([withDwells]
     * and a non-empty cross-stream set). Duration cannot separate a boundary from a hesitation
     * (the one true boundary measured 159 ms against false ones at 158, 164 and 213 ms), but every
     * false positive sat on a single-thumb swipe and the true one on a swipe with a cross-stream
     * tap inside it.
     */
    private fun boundariesFor(
        primary: List<InputToken>,
        swipeIdx: Int,
        s: SwipeToken,
        withDwells: Boolean,
    ): List<Boundary> {
        val out = ArrayList<Boundary>(4)
        for (k in primary.indices) {
            if (k == swipeIdx) continue
            val o = primary[k]
            if (o.streamId == s.streamId) continue
            val t: Long? = when (o) {
                // The tap split's early-tap widening, anchored to a contact. Cutting a multi-tap
                // swipe at a raw early instant makes a two-sample head piece that
                // keepIfContacted keeps, and the piece counts multiply (a sixteen-minute suite
                // run). The key ends where the finger left it, so an early tap is admitted only
                // inside a contact, and at that contact's exit.
                is TapToken -> when {
                    o.tStart >= s.tEnd + KineticaConstants.ORDER_AMBIG_MS -> null
                    o.tStart > s.tStart + KineticaConstants.SPLIT_MARGIN_MS -> o.tStart
                    else -> contactSnaps(s, o.tStart).getOrNull(1)
                        ?.takeIf { it > s.tStart && it < s.tEnd && isInteriorCut(s, it) }
                }
                is SwipeToken ->
                    if (o.tStart > s.tStart + KineticaConstants.SPLIT_MARGIN_MS &&
                        o.tStart < s.tEnd - KineticaConstants.SPLIT_MARGIN_MS
                    ) {
                        if (o.tEnd < s.tEnd - KineticaConstants.SPLIT_MARGIN_MS) {
                            (o.tStart + o.tEnd) / 2
                        } else {
                            o.tStart
                        }
                    } else null
            }
            if (t != null) out.add(Boundary(t, k))
        }
        if (withDwells && out.isNotEmpty()) {
            for (d in s.dwells) out.add(Boundary((d.tEnter + d.tExit) / 2, -1))
        }
        out.sortBy { it.t }
        return if (out.size <= KineticaConstants.MAX_SPLIT_ANCHORS) {
            out
        } else {
            ArrayList(out.subList(0, KineticaConstants.MAX_SPLIT_ANCHORS))
        }
    }

    /**
     * Cuts [s] at every boundary at once, or null when any piece would be unusable. Null, not a
     * silently dropped cut, is the absorb-never-drop rule: a boundary carries a letter position,
     * so a discarded cut would emit a sequence whose token order no longer matches the path. The
     * primary sequence and the single-cut generators stand either way.
     */
    private fun multiSplit(
        s: SwipeToken,
        boundaries: List<Boundary>,
        resampler: DtwMatcher,
        trimInterior: Boolean,
        trimFinal: Boolean,
        contacted: Boolean = false,
    ): List<Piece>? {
        val path = s.rawPath
        // Each of the >= 3 pieces needs >= 2 samples.
        if (path.size < 6) return null
        val cuts = IntArray(boundaries.size)
        for (i in boundaries.indices) {
            val c = nearestIndex(path, boundaries[i].t).coerceIn(1, path.size - 3)
            if (i > 0 && c - cuts[i - 1] < 2) return null
            cuts[i] = c
        }

        val out = ArrayList<Piece>(cuts.size + 1)
        for (i in cuts.indices) {
            val from = when {
                i == 0 -> 0
                trimInterior -> tailStartBefore(path, cuts[i], cuts[i - 1])
                else -> cuts[i - 1] + 1
            }
            out.add(
                Piece(
                    sliceOrNull(
                        s, from, cuts[i], resampler,
                        softStart = i > 0, softEnd = true, keepIfContacted = contacted,
                    ) ?: return null,
                    boundaries[i].tokenIdx,
                ),
            )
        }
        val last = cuts[cuts.size - 1]
        val from = if (trimFinal) tailStartBefore(path, path.size - 1, last) else last + 1
        out.add(
            Piece(
                sliceOrNull(
                    s, from, path.size - 1, resampler,
                    softStart = true, softEnd = false, keepIfContacted = contacted,
                ) ?: return null,
                -1,
            ),
        )
        return out
    }

    /** Splices [pieces] into [primary] in place of the swipe, consuming the boundary tokens. */
    private fun interleave(
        primary: List<InputToken>,
        swipeIdx: Int,
        pieces: List<Piece>,
    ): List<InputToken> {
        val consumed = HashSet<Int>(pieces.size)
        for (p in pieces) if (p.nextTokenIdx >= 0) consumed.add(p.nextTokenIdx)
        val out = ArrayList<InputToken>(primary.size + pieces.size)
        for (k in primary.indices) {
            when {
                k == swipeIdx -> for (p in pieces) {
                    out.add(p.swipe)
                    if (p.nextTokenIdx >= 0) out.add(primary[p.nextTokenIdx])
                }
                k in consumed -> Unit
                else -> out.add(primary[k])
            }
        }
        return out
    }

    /**
     * One distinct way to cut a swipe around an interrupting tap. [label]
     * names the variant and its half boundaries (raw-path indices) for
     * DecodeTrace, so a live capture shows which surgery carried the word.
     */
    class SplitVariant(val label: String, val first: SwipeToken, val second: SwipeToken)

    /**
     * All distinct cuts of [s] around a tap at [tCut].
     *
     * V1 cuts at the sample nearest the tap and resumes at the first distance peak past it, right
     * when the tap lands where the user means the insertion: a rest position or the letter itself.
     * A reaction-timed tap lands earlier (the user taps on seeing the reversal, while the thumb is
     * still on the forward leg or in the apex dwell), leaving the second half several kw of
     * repositioning that forces minLetters=2 and loses a single trailing letter. Two variants
     * cover that:
     *   V2 keeps only the tail's final approach (SPLIT_RESUME_TAIL_KW, arc from the end): a
     *      no-turn resume's letters sit at its end, and the travel before it only inflates the
     *      arc gates.
     *   V3 also snaps the cut forward to the next distance-from-cut peak, the reversal vertex, so
     *      the first half ends on its real last letter (true isEnd, no softEnd) when the cut fell
     *      too far before it even for the R_INNER pass.
     * Variants are deduped by half boundaries (V2/V3 collapse into V1 for mid and late cuts);
     * wrong variants find no words and cost microseconds.
     */
    fun splitVariants(s: SwipeToken, tCut: Long, resampler: DtwMatcher): List<SplitVariant> {
        val path = s.rawPath
        if (path.size < 4) return emptyList()
        val nearest = nearestIndex(path, tCut)
        if (nearest < 1) return emptyList()
        // Clamp so a minimal second half (>= 2 samples) always survives: a late or post-lift
        // tap whose nearest sample is the swipe's last must still yield a tail carrying the
        // final letter.
        val cut = nearest.coerceIn(1, path.size - 3)

        val out = ArrayList<SplitVariant>(3)
        val seen = HashSet<Long>(4)
        fun add(name: String, h1End: Int, h2Start: Int, softEnd: Boolean) {
            if (h2Start <= h1End || h2Start > path.size - 2) return
            if (!seen.add(h1End.toLong() shl 32 or h2Start.toLong())) return
            val halves = buildHalves(s, h1End, h2Start, resampler, softEnd) ?: return
            out.add(SplitVariant("$name[..$h1End|$h2Start..]", halves.first, halves.second))
        }
        add("v1", cut, v1Resume(path, cut), softEnd = true)
        add("v2", cut, tailStart(path, cut), softEnd = true)
        val apex = apexAfter(path, cut)
        if (apex > cut && apex <= path.size - 3) {
            add("v3", apex, tailStart(path, apex), softEnd = false)
        }
        return out
    }

    /**
     * The V1 split: cut at the sample nearest [tCut], resume at [v1Resume]. The second half is
     * softStart so the matcher relaxes its start-letter gate; the first half is softEnd because a
     * thumb mid-travel at the tap puts the cut between keys, far from the half's real last
     * letter. The single-pair entry point for the swipe-around-swipe generator, whose
     * mid-interval cut lands in the dwell the inner swipe filled; the tap-split generator uses
     * [splitVariants] to cover reaction-timed early taps too.
     */
    fun splitSwipe(s: SwipeToken, tCut: Long, resampler: DtwMatcher): Pair<SwipeToken, SwipeToken>? {
        val path = s.rawPath
        if (path.size < 4) return null
        val nearest = nearestIndex(path, tCut)
        if (nearest < 1) return null
        val cut = nearest.coerceIn(1, path.size - 3)
        return buildHalves(s, cut, v1Resume(path, cut), resampler, softEnd = true)
    }

    /**
     * [s] cut at [t], a key-contact boundary, with neither half trimmed: the letter before the
     * boundary ended when the thumb left the key. Used by the stream pass, which cuts a thumb
     * where its own letter ends, not where the other thumb's begins.
     */
    fun splitAtOwnContact(s: SwipeToken, t: Long, resampler: DtwMatcher): Pair<SwipeToken, SwipeToken>? =
        splitAtContact(s, t, resampler)?.let { it.first to it.second }

    /** Raw-path index of the sample nearest [t]. */
    private fun nearestIndex(path: List<PathPoint>, t: Long): Int {
        var best = 0
        var bestDt = Long.MAX_VALUE
        for (i in path.indices) {
            val dt = abs(path[i].t - t)
            if (dt < bestDt) {
                bestDt = dt
                best = i
            }
        }
        return best
    }

    /**
     * Materializes the two halves of a cut: the path prefix through h1End and the suffix from
     * h2Start on. Null when the first half has too little arc to carry letters.
     */
    private fun buildHalves(
        s: SwipeToken,
        h1End: Int,
        h2Start: Int,
        resampler: DtwMatcher,
        softEnd: Boolean,
    ): Pair<SwipeToken, SwipeToken>? {
        val path = s.rawPath
        if (arcLen(path.subList(0, h1End + 1)) < KineticaConstants.MIN_SPLIT_HALF_ARC_KW) return null
        if (path.size - h2Start < 2) return null
        // Only the first half must clear the arc floor: a short second half is legitimate when
        // the tap lands late, since its letter sits at the swipe's endpoint, where softStart,
        // the endpoint isEnd mark and the length band still police what it can decode to.
        val first = slice(s, 0, h1End, resampler, softStart = false, softEnd = softEnd)
        val second = slice(s, h2Start, path.size - 1, resampler, softStart = true, softEnd = false)
        return first to second
    }

    /**
     * Materializes the raw-path run [from]..[to] as a standalone swipe: its own
     * resampled array, arc length, time range and overlapping key contacts. Every
     * path piece is built here, so pieces from the two-half splits and the
     * multi-anchor interleave cannot differ.
     */
    private fun slice(
        s: SwipeToken,
        from: Int,
        to: Int,
        resampler: DtwMatcher,
        softStart: Boolean,
        softEnd: Boolean,
    ): SwipeToken {
        val path = s.rawPath
        val sub = path.subList(from, to + 1)
        val r = FloatArray(2 * KineticaConstants.RESAMPLE_N)
        resampler.resample(sub, r)
        val tFrom = path[from].t
        val tTo = path[to].t
        return SwipeToken(
            s.streamId, ArrayList(sub), r,
            s.keyContacts.filter { it.tExit >= tFrom && it.tEnter <= tTo },
            arcLen(sub),
            if (from == 0) s.tStart else tFrom,
            if (to == path.size - 1) s.tEnd else tTo,
            softStart = softStart,
            softEnd = softEnd,
        )
    }

    /** [slice], or null when the piece is too short or too flat to carry a letter. */
    private fun sliceOrNull(
        s: SwipeToken,
        from: Int,
        to: Int,
        resampler: DtwMatcher,
        softStart: Boolean,
        softEnd: Boolean,
        keepIfContacted: Boolean = false,
    ): SwipeToken? {
        if (to - from < 1) return null
        val piece = slice(s, from, to, resampler, softStart, softEnd)
        if (piece.arcLen >= KineticaConstants.MIN_SPLIT_HALF_ARC_KW) return piece
        // The arc minimum asks whether the piece travelled far enough to be a gesture, the only
        // evidence a two-half split has. A piece cut at the other thumb's contact times has
        // better: it covers a key the finger was on, or it does not. One letter drawn while the
        // other thumb writes the next travels barely half a key, so the arc minimum would rule
        // out the alternation this cut exists to express.
        return if (keepIfContacted && piece.keyContacts.isNotEmpty()) piece else null
    }

    /**
     * Where the V1 second half begins: the first real letter the finger reaches after the cut
     * ([apexAfter], where the path turns away from the cut point), or, for a straight resume with
     * no turn (the cross-thumb double-letter case), SPLIT_HEAD_TRIM_KW past the cut so the anchor
     * between the halves owns the cut letter alone. A fixed trim alone cannot find the resumed
     * letter: the straight lead-in clips intermediate keys, so the resume would land on a flyover
     * key. When the head trim would eat a brief last leg into the swipe's endpoint key (the
     * late-cut regime), the untrimmed tail is kept so the trailing letter survives; the
     * double-letter case's tail is never this short, so this cannot reintroduce a doubled anchor
     * letter.
     */
    private fun v1Resume(path: List<PathPoint>, cut: Int): Int {
        var resume = apexAfter(path, cut)
        if (resume == -1) {
            resume = cut + 1
            while (resume < path.size - 2 &&
                dist(path[resume], path[cut]) < KineticaConstants.SPLIT_HEAD_TRIM_KW
            ) resume++
        }
        return if (arcLen(path.subList(resume, path.size)) < KineticaConstants.MIN_SPLIT_HALF_ARC_KW) {
            cut + 1
        } else {
            resume
        }
    }

    /**
     * First interior local maximum of distance from `path[from]` at least
     * SPLIT_HEAD_TRIM_KW out, or -1 when the path never turns back. This is
     * where the path stops moving away from the reference point: a reversal
     * vertex, or the first real letter reached after a rest. [v1Resume]
     * resumes the second half there (the rest-position lead-in) and the V3
     * variant cuts the first half there (the early-tap regime).
     */
    private fun apexAfter(path: List<PathPoint>, from: Int): Int {
        val fromPt = path[from]
        var prevD = 0f
        var i = from + 1
        while (i <= path.size - 2) {
            val d = dist(path[i], fromPt)
            val dNext = dist(path[i + 1], fromPt)
            if (d >= KineticaConstants.SPLIT_HEAD_TRIM_KW && d >= prevD && d > dNext) return i
            prevD = d
            i++
        }
        return -1
    }

    /**
     * Start index of the endpoint-trimmed tail (split variants V2/V3): the
     * longest suffix whose arc stays within SPLIT_RESUME_TAIL_KW, never
     * reaching back to [cutIdx] or past the minimum two samples. A tail under
     * MIN_SWIPE_ARC_KW lets a single trailing letter close the half
     * (minLetters stays 1) where the untrimmed leftover travel could not.
     */
    private fun tailStart(path: List<PathPoint>, cutIdx: Int): Int =
        kotlin.math.min(tailStartBefore(path, path.size - 1, cutIdx), path.size - 2)

    /**
     * Start index of the <= SPLIT_RESUME_TAIL_KW run ending at [end], never
     * reaching back to [floor]. The generalization of [tailStart] to a piece
     * that ends at an interior cut rather than at the gesture's last sample.
     */
    private fun tailStartBefore(path: List<PathPoint>, end: Int, floor: Int): Int {
        var acc = 0f
        var i = end
        while (i - 1 > floor) {
            val step = dist(path[i], path[i - 1])
            if (acc + step > KineticaConstants.SPLIT_RESUME_TAIL_KW) break
            acc += step
            i--
        }
        return i
    }

    private fun dist(a: PathPoint, b: PathPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun arcLen(path: List<PathPoint>): Float {
        var acc = 0f
        for (i in 1 until path.size) {
            val dx = path[i].x - path[i - 1].x
            val dy = path[i].y - path[i - 1].y
            acc += kotlin.math.sqrt(dx * dx + dy * dy)
        }
        return acc
    }

    // (trim interior pieces, trim the final piece). Interior pieces need both
    // readings and the final one is trimmed by default; see addAnchorInterleaves.
    private val TRIM_MODES = listOf(
        true to true,
        false to true,
        false to false,
    )
}
