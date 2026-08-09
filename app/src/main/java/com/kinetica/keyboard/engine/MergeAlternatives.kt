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
 * alternatives for genuinely ambiguous timings; wrong alternatives simply find
 * no dictionary words and cost microseconds.
 */
object MergeAlternatives {

    fun sequences(tokens: List<InputToken>, resampler: DtwMatcher): List<List<InputToken>> {
        val primary = tokens.sortedBy { it.tStart }
        val result = ArrayList<List<InputToken>>(4)
        result.add(primary)
        if (tokens.size < 2) return result

        // Multi-anchor interleave FIRST (after primary, before every other
        // generator): each generator below returns as soon as result reaches
        // MAX_ALT_SEQUENCES, so a generator placed last is starved in exactly
        // the dense buffers this one exists for - the device "cuando" buffer
        // fills seven sequences from the tap-split generator alone.
        addAnchorInterleaves(primary, resampler, result)
        addCrossSwipeInterleaves(primary, resampler, result)

        // Order swap: adjacent cross-stream tokens starting near-simultaneously.
        for (i in 0 until primary.size - 1) {
            if (result.size >= KineticaConstants.MAX_ALT_SEQUENCES) return result
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

        // Mid-swipe split: a deliberate tap on the other thumb, timed well
        // inside a swipe, inserts its letter at that point in the swipe (the
        // explicit double-letter mechanism and general cross-thumb interleave).
        for (tapIdx in primary.indices) {
            if (result.size >= KineticaConstants.MAX_ALT_SEQUENCES) return result
            val tap = primary[tapIdx] as? TapToken ?: continue
            for (swipeIdx in primary.indices) {
                if (result.size >= KineticaConstants.MAX_ALT_SEQUENCES) break
                val swipe = primary[swipeIdx] as? SwipeToken ?: continue
                if (swipe.streamId == tap.streamId) continue
                if (tap.tStart <= swipe.tStart + KineticaConstants.SPLIT_MARGIN_MS) continue
                // No dead zone before the swipe's end, and a grace window after
                // it: a reaction-timed cross-stream tap routinely lands in the
                // last fraction of the gesture or a beat after the lift (the
                // right thumb is already reversing toward its final key when the
                // left taps). Rejecting those left "quindi" undecodable as "QD"
                // (the late-cut resume regime); splitSwipe now carries
                // the trailing letter, and wrong splits still find no words.
                if (tap.tStart >= swipe.tEnd + KineticaConstants.ORDER_AMBIG_MS) continue
                val variants = splitVariants(swipe, tap.tStart, resampler)
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

        // Swipe-around-swipe: the other thumb's swipe starts inside this swipe's
        // interval - a hold-and-resume where the pause is filled by the other
        // stream's gesture (e.g. left swipes se, holds, right swipes mp, left
        // resumes re -> "sempre"). Split this swipe around it, exactly as a tap,
        // so the interleave se|mp|re becomes representable at all.
        for (outerIdx in primary.indices) {
            if (result.size >= KineticaConstants.MAX_ALT_SEQUENCES) return result
            val outer = primary[outerIdx] as? SwipeToken ?: continue
            for (innerIdx in outerIdx + 1 until primary.size) {
                if (result.size >= KineticaConstants.MAX_ALT_SEQUENCES) break
                val inner = primary[innerIdx] as? SwipeToken ?: continue
                if (inner.streamId == outer.streamId) continue
                if (inner.tStart <= outer.tStart + KineticaConstants.SPLIT_MARGIN_MS) continue
                if (inner.tStart >= outer.tEnd - KineticaConstants.SPLIT_MARGIN_MS) continue
                // Fully contained: cut at the inner gesture's midpoint, the
                // middle of the pause it filled. Unchanged from the shipped
                // behaviour that decodes "sempre" on device.
                //
                // Partially overlapping (inner starts inside but ends after) is
                // what two thumbs moving AT ONCE actually produce, and the old
                // both-sides containment test rejected it outright - the
                // captured "siempre" failure (inner 9015251..9015518
                // against outer 9014754..9015343). There the boundary is the
                // moment the other thumb started, and the split VARIANTS are
                // used so the endpoint-trimmed tail can carry a short resumed
                // piece.
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
        return result
    }

    /** A cut inside a swipe, and the cross-stream token that goes at it (-1 = none). */
    private class Boundary(val t: Long, val tokenIdx: Int)

    /** One path piece plus the primary-index of the token that follows it. */
    private class Piece(val swipe: SwipeToken, val nextTokenIdx: Int)

    /**
     * Sequences that cut a swipe at EVERY cross-stream boundary at once.
     *
     * The shipped tap-split generator inserts one tap per sequence, so a swipe
     * interrupted twice can only ever be cut once: for the device "cuando"
     * buffer (one LEFT swipe c-u-n-o with RIGHT taps a and d inside it) the
     * representable orders were cuno|a|d, cu|a|no|d and cun|a|d|o - and that
     * last one spells c,u,n,a,d,o, which is exactly the "cuñado" that won on
     * device. c,u,a,n,d,o needed cuts at BOTH taps and was therefore pruned
     * from every sequence.
     *
     * Boundaries come from the OTHER stream's event times, which the buffer
     * already carries - not from this stream's stillness. A device capture is
     * why: neither failing word had a single dwell, while three of
     * the four dwells it did record were hesitations inside ordinary
     * single-thumb swipes.
     */
    private fun addAnchorInterleaves(
        primary: List<InputToken>,
        resampler: DtwMatcher,
        result: MutableList<List<InputToken>>,
    ) {
        for (swipeIdx in primary.indices) {
            if (result.size >= KineticaConstants.MAX_ALT_SEQUENCES) return
            val s = primary[swipeIdx] as? SwipeToken ?: continue
            // Two conditions, deliberately separate: a dwell counts as a cut only
            // for a swipe the other stream is active in at all (the co-occurrence
            // gate in boundariesFor), and it takes two cuts to need an interleave
            // the single-cut generators cannot already express. So one tap plus a
            // real dwell qualifies, one tap alone never does.
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
                // (trim interior, trim final). The final piece is the one whose
                // letters live at its END, so it is trimmed by default (the bug-5
                // V2 insight); interior pieces need both readings - a single
                // trailing letter needs the trim to clear minLetters, a
                // two-letter piece needs the untrimmed span to clear the length
                // band. The all-untrimmed pair covers a long final piece.
                for ((trimInterior, trimFinal) in TRIM_MODES) {
                    if (result.size >= KineticaConstants.MAX_ALT_SEQUENCES) return
                    val pieces = multiSplit(s, boundaries, resampler, trimInterior, trimFinal)
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
     * Sequences that cut EVERY swipe the other stream interrupted, at once.
     *
     * [addAnchorInterleaves] multiplies cuts within ONE swipe; every other
     * generator substitutes at a single swipe index too, rebuilding each variant
     * from `primary`. So when the swiping thumb lifts mid-word and re-swipes -
     * two swipes with one interior tap each - the reading that cuts BOTH is in
     * the language of no generator, and the intended word is pruned from every
     * sequence. A device "praticamente" gesture: LEFT swipes r-a-t
     * then c-a, lifts, swipes e then t-e, while RIGHT taps p, i, m, n. The word
     * needs [p][rat][i][ca][m][e][n][te] - swipe 1 cut at tap i AND swipe 2 cut
     * at tap n - and the capture topped out at three segments with every search
     * emitting nothing.
     *
     * Only fires when at least two swipes carry an interior boundary, so
     * single-swipe buffers keep the shipped generator set byte-identically. One
     * sequence per trim mode, deduped, which is why this cannot starve the
     * generators below it the way an unbounded product would.
     */
    private fun addCrossSwipeInterleaves(
        primary: List<InputToken>,
        resampler: DtwMatcher,
        result: MutableList<List<InputToken>>,
    ) {
        val owner = HashMap<Int, Int>()
        val perSwipe = LinkedHashMap<Int, MutableList<Boundary>>()
        for (swipeIdx in primary.indices) {
            val s = primary[swipeIdx] as? SwipeToken ?: continue
            for (b in boundariesFor(primary, swipeIdx, s, withDwells = false)) {
                if (b.tokenIdx < 0) continue
                if (!isInteriorCut(s, b.t)) continue
                // A token can fall in two swipes' acceptance windows (the tail
                // grace of one, the interior of the next). Cutting it into both
                // would insert its letter twice, so ownership is exclusive and
                // goes to the swipe whose interval actually contains it.
                val prev = owner[b.tokenIdx]
                val o = primary[b.tokenIdx]
                val contains = o.tStart > s.tStart && o.tStart < s.tEnd
                if (prev == null) {
                    owner[b.tokenIdx] = swipeIdx
                } else if (contains) {
                    perSwipe[prev]?.removeAll { it.tokenIdx == b.tokenIdx }
                    owner[b.tokenIdx] = swipeIdx
                } else {
                    continue
                }
                perSwipe.getOrPut(swipeIdx) { ArrayList(2) }.add(b)
            }
        }
        val cutting = perSwipe.filterValues { it.isNotEmpty() }
        if (cutting.size < 2) return

        val seen = HashSet<String>(4)
        for ((trimInterior, trimFinal) in TRIM_MODES) {
            if (result.size >= KineticaConstants.MAX_ALT_SEQUENCES) return
            val pieces = HashMap<Int, List<Piece>>(cutting.size)
            for ((swipeIdx, boundaries) in cutting) {
                val s = primary[swipeIdx] as SwipeToken
                pieces[swipeIdx] = multiSplit(s, boundaries, resampler, trimInterior, trimFinal)
                    ?: break
            }
            if (pieces.size != cutting.size) continue
            val sig = cutting.keys.joinToString(";") { idx ->
                pieces[idx]!!.joinToString(",") {
                    "${it.swipe.tStart}-${it.swipe.tEnd}-${it.swipe.rawPath.size}"
                }
            }
            if (!seen.add(sig)) continue
            result.add(interleaveAll(primary, pieces))
        }
    }

    /**
     * True when a cut at [t] leaves usable path on BOTH sides of it.
     *
     * A boundary whose nearest sample is the gesture's last is not an interior
     * cut at all - it is a token that follows the whole swipe, which the primary
     * order already expresses (the device "praticamente" buffer taps `m` 4 ms
     * before the lift). Recognizing that here is not the same as dropping a cut:
     * [multiSplit]'s absorb-never-drop rule stands for real interior boundaries,
     * and one of those clamping onto the tail is exactly what nulled the whole
     * interleave for that buffer.
     */
    private fun isInteriorCut(s: SwipeToken, t: Long): Boolean {
        val path = s.rawPath
        if (path.size < 6) return false
        val idx = nearestIndex(path, t)
        return idx > 1 && idx < path.size - 3
    }

    /** Splices every cut swipe's pieces into [primary], consuming their boundary tokens. */
    private fun interleaveAll(
        primary: List<InputToken>,
        piecesBySwipe: Map<Int, List<Piece>>,
    ): List<InputToken> {
        val consumed = HashSet<Int>(4)
        for (pieces in piecesBySwipe.values) {
            for (p in pieces) if (p.nextTokenIdx >= 0) consumed.add(p.nextTokenIdx)
        }
        val out = ArrayList<InputToken>(primary.size + piecesBySwipe.size + 2)
        for (k in primary.indices) {
            val pieces = piecesBySwipe[k]
            when {
                pieces != null -> for (p in pieces) {
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
     * Cut points inside [s] contributed by the other stream, reusing the
     * shipped acceptance windows verbatim so no timing that is rejected today
     * becomes acceptable here.
     *
     * Dwells are added only when the other stream is active inside this gesture
     * at all ([withDwells] and a non-empty cross-stream set). That gate is the
     * one discriminator the measurement supports: duration cannot
     * separate a boundary from a hesitation (the single true boundary measured
     * 159 ms against false ones at 158, 164 and 213 ms), but every false
     * positive sat on a single-thumb swipe while the true one sat on a swipe
     * with a cross-stream tap inside it.
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
                is TapToken ->
                    if (o.tStart > s.tStart + KineticaConstants.SPLIT_MARGIN_MS &&
                        o.tStart < s.tEnd + KineticaConstants.ORDER_AMBIG_MS
                    ) o.tStart else null
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
     * Cuts [s] at every boundary at once, or null when any piece would be
     * unusable. Returning null rather than silently dropping a cut is the
     * absorb-never-drop rule: a boundary carries a letter position, so a
     * discarded cut would emit a sequence whose token order no longer matches
     * the path - worse than offering no interleave at all. The primary sequence
     * and the single-cut generators still stand either way.
     */
    private fun multiSplit(
        s: SwipeToken,
        boundaries: List<Boundary>,
        resampler: DtwMatcher,
        trimInterior: Boolean,
        trimFinal: Boolean,
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
                    sliceOrNull(s, from, cuts[i], resampler, softStart = i > 0, softEnd = true)
                        ?: return null,
                    boundaries[i].tokenIdx,
                ),
            )
        }
        val last = cuts[cuts.size - 1]
        val from = if (trimFinal) tailStartBefore(path, path.size - 1, last) else last + 1
        out.add(
            Piece(
                sliceOrNull(s, from, path.size - 1, resampler, softStart = true, softEnd = false)
                    ?: return null,
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
     * V1 - cut at the sample nearest the tap, resume at the first distance
     * peak past it - is right when the tap lands where the user intends the
     * insertion: a rest position or the letter itself. But a reaction-timed
     * tap lands EARLIER than the intended boundary (the user taps on
     * perceiving the reversal, while the thumb is still on the forward leg or
     * in the apex dwell - the early-tap regime), leaving the second half
     * several kw of repositioning travel that forces minLetters=2 and kills a
     * single trailing letter. Two additive variants cover that:
     *   V2 keeps only the tail's final approach (SPLIT_RESUME_TAIL_KW,
     *      arc-from-end): a no-turn resume's letters live at its END, so the
     *      travel before it is dead weight that only inflated the arc gates.
     *   V3 additionally snaps the cut forward to the next distance-from-cut
     *      peak - the reversal vertex, where the insertion was intended - so
     *      the first half ends ON its real last letter (true isEnd, no softEnd
     *      lean) when the cut fell too far before it for even the R_INNER pass.
     * Variants are deduped by half boundaries (V2/V3 collapse into V1 for mid
     * and late cuts, so those regimes decode exactly as before); wrong
     * variants find no words and cost microseconds.
     */
    fun splitVariants(s: SwipeToken, tCut: Long, resampler: DtwMatcher): List<SplitVariant> {
        val path = s.rawPath
        if (path.size < 4) return emptyList()
        val nearest = nearestIndex(path, tCut)
        if (nearest < 1) return emptyList()
        // Clamp so a minimal second half (>=2 samples) always survives: a late
        // or post-lift tap whose nearest sample is the swipe's last must still
        // yield a tail carrying the final letter rather than bail here - the
        // out-of-bounds return was half the late-cut resume failure.
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
     * The V1 split: cut at the sample nearest [tCut], resume at [v1Resume].
     * The second half is flagged softStart so the matcher relaxes its
     * start-letter gate; the first half is flagged softEnd for the symmetric
     * reason - when the thumb is mid-travel at the tap, the cut point sits
     * between keys, arbitrarily far from the half's real last letter.
     * Kept as the single-pair entry point for the
     * swipe-around-swipe generator, whose mid-interval cut always lands in the
     * dwell the inner swipe filled; the tap-split generator uses
     * [splitVariants] to also cover reaction-timed early taps.
     */
    fun splitSwipe(s: SwipeToken, tCut: Long, resampler: DtwMatcher): Pair<SwipeToken, SwipeToken>? {
        val path = s.rawPath
        if (path.size < 4) return null
        val nearest = nearestIndex(path, tCut)
        if (nearest < 1) return null
        val cut = nearest.coerceIn(1, path.size - 3)
        return buildHalves(s, cut, v1Resume(path, cut), resampler, softEnd = true)
    }

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
     * Materializes the two halves of a cut: the path prefix through h1End
     * and the suffix from h2Start on.
     * Null when the first half hasn't enough arc to carry real letters - only
     * the first half must clear the minimum-arc floor. A short second half is
     * legitimate when the tap lands late: its letter sits at the swipe's
     * endpoint, where softStart + the endpoint isEnd mark + the length band
     * still police what it can decode to.
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
        // Only the first half must clear the arc floor here: a short second half
        // is legitimate when the tap lands late (its letter sits at the swipe's
        // endpoint, where softStart + the endpoint isEnd mark + the length band
        // still police what it can decode to).
        val first = slice(s, 0, h1End, resampler, softStart = false, softEnd = softEnd)
        val second = slice(s, h2Start, path.size - 1, resampler, softStart = true, softEnd = false)
        return first to second
    }

    /**
     * Materializes the raw-path run [from]..[to] as a standalone swipe: its own
     * resampled array, arc length, time range and overlapping key contacts. The
     * single place a path piece is built, shared by the two-half splits and the
     * multi-anchor interleave, so a piece can never differ by construction path.
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
    ): SwipeToken? {
        if (to - from < 1) return null
        val piece = slice(s, from, to, resampler, softStart, softEnd)
        return if (piece.arcLen < KineticaConstants.MIN_SPLIT_HALF_ARC_KW) null else piece
    }

    /**
     * Where the V1 second half begins: the first real letter the finger
     * reaches after the cut ([apexAfter] - where the path turns away from the
     * cut point), or, for a straight resume with no turn (e.g. the cross-thumb
     * double-letter case), SPLIT_HEAD_TRIM_KW past the cut so the anchor
     * inserted between the halves owns the cut letter exclusively. A fixed
     * contact-agnostic trim alone cannot find the resumed letter - the
     * straight lead-in to it clips intermediate keys, so the resume would land
     * on a flyover key far from the real letter. When the head trim would eat
     * a brief remaining leg that still runs into the swipe's real endpoint key
     * (the bug-5 late-cut regime), keep the untrimmed tail so the trailing
     * letter survives; the trim matters only for the double-letter case, whose
     * tail is never this short, so this never re-introduces a doubled anchor
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
     * vertex, or the first real letter reached after a rest. Shared by
     * [v1Resume] (which RESUMES the second half there - the rest-position
     * lead-in) and the V3 split variant (which CUTS the first half there -
     * the early-tap regime).
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
     * reaching back to [cutIdx] or past the minimum two samples. Keeping the
     * tail under MIN_SWIPE_ARC_KW is the point - it lets a single trailing
     * letter close the half (minLetters stays 1) where the untrimmed leftover
     * travel could not.
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
