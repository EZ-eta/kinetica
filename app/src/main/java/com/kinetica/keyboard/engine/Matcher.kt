package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.TapToken
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * What the trie search consumes: a word matches the pattern iff it decomposes
 * into consecutive pieces where each Anchor consumes exactly its letter and
 * each Segment consumes >= minLetters letters satisfying its geometry.
 */
sealed class Matcher {
    /**
     * From a tap: an exact letter with the raw touch position (kw) kept for the fuzzy pass,
     * and the thumb and instant it came from, which a hand-over between thumbs is timed by.
     */
    class Anchor(
        val code: Int,
        val x: Float,
        val y: Float,
        val stream: StreamId? = null,
        val t: Long = 0L,
    ) : Matcher()

    /** From a swipe, with all per-letter geometry precomputed for O(1) pruning. */
    class Segment(
        val resampled: FloatArray,
        val arcLen: Float,
        /**
         * The part of [arcLen] that counts as evidence of how many letters this
         * piece spells. Equal to [arcLen] for a whole gesture; a cut piece has
         * its lead-in or run-out discounted, because a mid-gesture cut does not
         * know where its letters sit inside the piece. Read by the minimum-letters
         * rule and the lower length band; the upper band uses the true [arcLen],
         * since a long path can spell many letters.
         */
        val letterArcLen: Float,
        val minLetters: Int,
        val maxLetters: Int,
        private val isStartNeighbor: BooleanArray,   // [26]
        private val isEndNeighbor: BooleanArray,     // [26]
        val nearPath: BooleanArray,                  // [26] center within R_INNER of path
        /**
         * [26] keys this gesture was measurably on, from the token's own key
         * contacts. [nearPath] is every key whose centre came within R_INNER_KW
         * of the path, so it holds every neighbour of every key crossed.
         *
         * Adjacent keys are 1.0 kw apart and rows 1.5-1.9 kw, both inside
         * R_INNER_KW = 1.8, so a swipe through `e` then `s` reads as well as one
         * through `r` then `a`: `keys`, every letter touched, lost to `kyra`,
         * which invents two of its four.
         *
         * Empty for a token with no contacts: synthetic fixtures not built with
         * TestData.contactsAlong, and any path a device did not produce. An empty
         * array charges nothing: a missing contact list is no evidence, not
         * evidence against.
         */
        val contacted: BooleanArray,                 // [26] a real key contact
        /**
         * [26] ascending resample indices, one local distance minimum per
         * distinct pass of the path near the key. A single "nearest index" per
         * letter cannot represent revisited letters (the second e of "however")
         * or keys the path merely flies over between two other keys, and the
         * monotonicity prune would kill those words before DTW scored them. A
         * pass ends when the path leaves R_INNER_KW of the key or turns around
         * inside it ([collectPasses]).
         */
        private val passIdx: Array<IntArray>,
        /**
         * True for the second half of a mid-swipe split (SwipeToken.softStart):
         * the half resumes mid-word, so its first letter may be any key the
         * resumed path passes near, not only one near the path's start point.
         */
        val softStart: Boolean,
        /**
         * True for the first half of a mid-swipe split (SwipeToken.softEnd):
         * the half ends at the cut sample, which is mid-travel whenever the
         * thumb was moving when the other thumb tapped, so its last letter may
         * be any key the path passes near, not only one near the cut point.
         */
        val softEnd: Boolean,
        /** The thumb this piece was drawn by. */
        val stream: StreamId,
        /** [RESAMPLE_N] times, one per resample index, from the piece's own raw path. */
        val sampleT: LongArray,
        /**
         * The piece's key contacts clipped to its own time span, so a contact straddling a cut
         * belongs to each half only for the part that half holds.
         */
        private val contactCode: IntArray,
        private val contactEnter: LongArray,
        private val contactExit: LongArray,
    ) : Matcher() {
        fun isStart(code: Int): Boolean = isStartNeighbor[code]
        fun isEnd(code: Int): Boolean = isEndNeighbor[code]

        /**
         * When the letter [code], matched at resample index [idx], happened: inside the key's
         * own contact when the thumb was on it (the contact holding that sample, else the
         * nearest one on the same key), at [KineticaConstants.HANDOVER_CONTACT_FRACTION] of
         * its span; otherwise the sample's own time.
         */
        fun letterTime(code: Int, idx: Int): Long {
            val n = sampleT.size
            val ts = sampleT[idx.coerceIn(0, n - 1)]
            var best = -1
            var bestGap = Long.MAX_VALUE
            for (i in contactCode.indices) {
                if (contactCode[i] != code) continue
                val gap = when {
                    ts < contactEnter[i] -> contactEnter[i] - ts
                    ts > contactExit[i] -> ts - contactExit[i]
                    else -> 0L
                }
                if (gap < bestGap) {
                    bestGap = gap
                    best = i
                }
            }
            if (best < 0) return ts
            val span = contactExit[best] - contactEnter[best]
            return contactEnter[best] + (span * KineticaConstants.HANDOVER_CONTACT_FRACTION).toLong()
        }

        /** Earliest pass of the path near [code] at or after [minIdx], or -1. */
        fun passAtOrAfter(code: Int, minIdx: Int): Int {
            val passes = passIdx[code]
            for (idx in passes) if (idx >= minIdx) return idx
            return -1
        }
    }

    companion object {
        private const val MAX_SEGMENT_LETTERS = 12
        // Average key-to-key hop in a word is roughly 1.5 kw on QWERTY; used
        // only as a soft letter-count ceiling, the length band does real work.
        private const val KW_PER_LETTER = 1.5f

        fun buildPattern(tokens: List<InputToken>, g: KeyboardGeometry): List<Matcher>? {
            val out = ArrayList<Matcher>(tokens.size)
            for (t in tokens) {
                when (t) {
                    is TapToken -> {
                        if (!g.hasKey(t.code)) return null
                        out.add(Anchor(t.code, t.x, t.y, t.streamId, t.tStart))
                    }
                    is SwipeToken -> out.add(buildSegment(t, g))
                }
            }
            return out
        }

        fun buildSegment(t: SwipeToken, g: KeyboardGeometry): Segment {
            val n = KineticaConstants.RESAMPLE_N
            val r = t.resampled
            val sx = r[0]
            val sy = r[1]
            val ex = r[2 * (n - 1)]
            val ey = r[2 * (n - 1) + 1]

            val isStart = BooleanArray(g.letterCount)
            val isEnd = BooleanArray(g.letterCount)
            // Which keys the gesture was measurably on, straight from the token.
            // GestureStream applies hysteresis before recording one, so this is the
            // conservative half of the evidence: a fast crossing can be missed, but
            // a key listed here was under the finger.
            val contacted = if (t.keyContacts.isEmpty()) {
                NO_CONTACTS
            } else {
                BooleanArray(g.letterCount).also { a ->
                    for (c in t.keyContacts) {
                        if (c.code in 0 until g.letterCount) a[c.code] = true
                    }
                }
            }
            val nearPath = BooleanArray(g.letterCount)
            val passIdx = Array(g.letterCount) { EMPTY_PASSES }
            val passScratch = IntArray(MAX_PASSES)
            var anyStart = false
            var anyEnd = false
            for (code in 0 until g.letterCount) {
                if (!g.hasKey(code)) continue
                if (g.distToCenter(sx, sy, code) <= KineticaConstants.R_ENDPOINT_KW) {
                    isStart[code] = true
                    anyStart = true
                }
                if (g.distToCenter(ex, ey, code) <= KineticaConstants.R_ENDPOINT_KW) {
                    isEnd[code] = true
                    anyEnd = true
                }
                val passes = collectPasses(r, n, g, code, passScratch)
                if (passes > 0) {
                    nearPath[code] = true
                    passIdx[code] = passScratch.copyOf(passes)
                }
            }
            // A gesture must always admit at least its nearest start/end key,
            // even when it drifts outside every key rect.
            if (!anyStart) g.nearestKey(sx, sy).takeIf { it >= 0 }?.let { isStart[it] = true }
            if (!anyEnd) g.nearestKey(ex, ey).takeIf { it >= 0 }?.let { isEnd[it] = true }

            // A cut end contributes travel that is no evidence of letters: a
            // softStart piece's arc begins with an unknown lead-in to its first
            // letter, a softEnd piece's ends with run-out past its last. The
            // letter rules already allow for it (softStart drops the isStart
            // radius test, softEnd widens what may close a segment in
            // WordPredictor.descend), and the letter-count rules must too:
            // requiring two letters of every cut piece over MIN_SWIPE_ARC_KW made
            // `praticamente` undecodable, its one-letter `e` leg measuring 2.46 kw
            // as an interior piece and 1.60 kw as a second swipe's head.
            //
            // The discount is SPLIT_RESUME_TAIL_KW, the arc tailStartBefore treats
            // as one trailing letter, so trimmed and untrimmed readings agree on the
            // letter count. It applies once when both ends are soft: one end's worth
            // fixes every measured case, and twice would let a 3.9 kw interior piece
            // be read as a single letter.
            val letterArcLen = if (t.softStart || t.softEnd) {
                max(0f, t.arcLen - KineticaConstants.SPLIT_RESUME_TAIL_KW)
            } else {
                t.arcLen
            }
            val minLetters = if (letterArcLen < KineticaConstants.MIN_SWIPE_ARC_KW) 1 else 2
            val maxLetters = max(
                minLetters,
                min(MAX_SEGMENT_LETTERS, ceil(t.arcLen / KW_PER_LETTER).toInt() + 2),
            )
            val sampleT = LongArray(n)
            DtwMatcher.resampleTimes(t.rawPath, sampleT)
            val spanStart = t.rawPath.firstOrNull()?.t ?: t.tStart
            val spanEnd = t.rawPath.lastOrNull()?.t ?: t.tEnd
            val held = t.keyContacts.filter { it.tExit >= spanStart && it.tEnter <= spanEnd }
            return Segment(
                r, t.arcLen, letterArcLen, minLetters, maxLetters, isStart, isEnd, nearPath, contacted,
                passIdx, t.softStart, t.softEnd,
                t.streamId, sampleT,
                IntArray(held.size) { held[it].code },
                LongArray(held.size) { maxOf(held[it].tEnter, spanStart) },
                LongArray(held.size) { minOf(held[it].tExit, spanEnd) },
            )
        }

        /**
         * Ascending resample indices, one per distinct visit of the path to
         * [code]'s key, written into [scratch]; returns how many were written.
         *
         * A visit ends when the path leaves the R_INNER_KW disc, or when it turns
         * around inside it: an interior peak with PASS_SPLIT_PROMINENCE_KW of rise
         * above the current minimum and the same fall after it means the finger
         * went to another key and came back. Without the second rule a swipe whose
         * legs are all shorter than R_INNER_KW merges every visit into one pass,
         * and a word needing that letter at increasing indices is unspellable:
         * `vedere` decoded empty on real gestures.
         *
         * Two orderings matter:
         *  - `peak` resets whenever a deeper minimum is found, so the peak is
         *    always measured after the current minimum; otherwise the descent
         *    into the first visit reads as an out-and-back and every run splits.
         *  - the split is tested before the new-minimum update, or a visit that
         *    ends on the run's deepest sample (`vedere`'s final e) swallows the
         *    pass before it.
         *
         * The result is a superset of the run rule's indices, since a run's
         * argmin is also the argmin of the sub-run holding it, so this can only
         * admit words, never lose one (PassRunSplitTest).
         */
        private fun collectPasses(
            r: FloatArray,
            n: Int,
            g: KeyboardGeometry,
            code: Int,
            scratch: IntArray,
        ): Int {
            val prominence = KineticaConstants.PASS_SPLIT_PROMINENCE_KW
            var count = 0
            var bestIdx = -1
            var best = 0f
            var peak = 0f
            for (k in 0 until n) {
                val d = g.distToCenter(r[2 * k], r[2 * k + 1], code)
                if (d > KineticaConstants.R_INNER_KW) {
                    if (bestIdx != -1 && count < MAX_PASSES) scratch[count++] = bestIdx
                    bestIdx = -1
                    continue
                }
                if (bestIdx == -1) {
                    bestIdx = k
                    best = d
                    peak = d
                    continue
                }
                if (d > peak) peak = d
                if (peak - best >= prominence && peak - d >= prominence) {
                    if (count < MAX_PASSES) scratch[count++] = bestIdx
                    bestIdx = k
                    best = d
                    peak = d
                } else if (d < best) {
                    best = d
                    bestIdx = k
                    peak = d
                }
            }
            if (bestIdx != -1 && count < MAX_PASSES) scratch[count++] = bestIdx
            return count
        }

        // Consecutive passes are separated by a sample outside the disc or by a
        // peak sample, so 32 resample points admit at most 16 visits: the cap is
        // the structural maximum, not a tunable, and cannot drop a pass the run
        // rule would keep.
        private const val MAX_PASSES = KineticaConstants.RESAMPLE_N / 2
        private val EMPTY_PASSES = IntArray(0)

        /** Shared empty array: WordPredictor's first branch tests for an empty one. */
        private val NO_CONTACTS = BooleanArray(0)
    }
}
