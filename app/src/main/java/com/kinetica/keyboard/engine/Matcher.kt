package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
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
    /** From a tap: an exact letter with the raw touch position (kw) kept for the fuzzy pass. */
    class Anchor(val code: Int, val x: Float, val y: Float) : Matcher()

    /** From a swipe, with all per-letter geometry precomputed for O(1) pruning. */
    class Segment(
        val resampled: FloatArray,
        val arcLen: Float,
        /**
         * The part of [arcLen] that counts as evidence of how many letters this
         * piece spells. Equal to [arcLen] for a whole gesture; for a softStart
         * piece the lead-in travel to its first letter is discounted, because
         * where that letter sits inside the piece is exactly what a mid-gesture
         * cut does not know. Read by the minimum-letters rule and the lower
         * length band; the upper band still uses the true [arcLen], since a long
         * path really can spell many letters.
         */
        val letterArcLen: Float,
        val minLetters: Int,
        val maxLetters: Int,
        private val isStartNeighbor: BooleanArray,   // [26]
        private val isEndNeighbor: BooleanArray,     // [26]
        val nearPath: BooleanArray,                  // [26] center within R_INNER of path
        /**
         * [26] ascending resample indices, one local distance minimum per
         * distinct pass of the path near the key. A single "nearest index" per
         * letter cannot represent revisited letters (the second e of "however")
         * or keys the path merely flies over between two other keys, and the
         * monotonicity prune would silently kill those words before DTW ever
         * scored them. A pass ends when the path leaves R_INNER of the key OR
         * turns around inside it - see [collectPasses].
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
    ) : Matcher() {
        fun isStart(code: Int): Boolean = isStartNeighbor[code]
        fun isEnd(code: Int): Boolean = isEndNeighbor[code]

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
                        out.add(Anchor(t.code, t.x, t.y))
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

            val isStart = BooleanArray(Alphabet.LETTERS)
            val isEnd = BooleanArray(Alphabet.LETTERS)
            val nearPath = BooleanArray(Alphabet.LETTERS)
            val passIdx = Array(Alphabet.LETTERS) { EMPTY_PASSES }
            val passScratch = IntArray(MAX_PASSES)
            var anyStart = false
            var anyEnd = false
            for (code in 0 until Alphabet.LETTERS) {
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

            // A cut end contributes travel that is no evidence of letters. A
            // softStart piece resumes mid-gesture, so an unknown prefix of its
            // arc is the lead-in to its first letter; a softEnd piece was cut
            // while the thumb was still moving, so an unknown suffix is run-out
            // past its last letter. Both are already accepted where the LETTERS
            // are concerned - softStart drops the isStart radius test and softEnd
            // widens what may close a segment (WordPredictor.descend) - but the
            // letter COUNT rules kept reading the whole arc, and requiring two
            // letters of every cut piece over MIN_SWIPE_ARC_KW is what made
            // "praticamente" undecodable: its one-letter "e" leg measures
            // arc=2.46 kw as an interior piece and 1.60 kw as a second swipe's
            // head, nearly all of it travel.
            //
            // The discount is SPLIT_RESUME_TAIL_KW, the arc the split generators
            // themselves treat as one trailing letter's worth in tailStartBefore,
            // so a piece's trimmed and untrimmed readings now agree on how many
            // letters it can hold instead of contradicting each other. It is
            // applied ONCE even when both ends are soft: one end's worth fixes
            // every measured case, and a double discount would let a 3.9 kw
            // interior piece be explained by a single letter.
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
            return Segment(
                r, t.arcLen, letterArcLen, minLetters, maxLetters, isStart, isEnd, nearPath,
                passIdx, t.softStart, t.softEnd,
            )
        }

        /**
         * Ascending resample indices, one per distinct visit of the path to
         * [code]'s key, written into [scratch]; returns how many were written.
         *
         * A visit ends in one of two ways. The path leaves the R_INNER_KW disc
         * entirely - the original rule - or it turns around inside it: an
         * interior peak with PASS_SPLIT_PROMINENCE_KW of rise above the current
         * minimum AND the same fall after it means the finger demonstrably went
         * to another key and came back. Without the second rule a swipe whose
         * legs are all shorter than R_INNER_KW never leaves any disc, so every
         * visit merged into a single pass and a word needing that letter at
         * increasing indices became unspellable - "vedere" decoded empty on real
         * device gestures.
         *
         * Two orderings here are load-bearing, and both were found by getting
         * them wrong first:
         *  - [peak] resets whenever a deeper minimum is found, so the peak is
         *    always measured AFTER the current minimum. Otherwise the descent
         *    into the first visit reads as an out-and-back and every run splits.
         *  - the split is tested BEFORE the new-minimum update, or a visit that
         *    ends on the run's deepest sample (exactly "vedere"'s final e)
         *    swallows the pass before it.
         *
         * The result is a strict superset of the run rule's indices - a run's
         * argmin is also the argmin of whichever sub-run contains it - so this
         * can only admit words, never lose one (PassRunSplitTest).
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

        // Consecutive passes are separated either by a sample outside the disc
        // or by a peak sample, so 32 resample points admit at most 16 visits:
        // the cap is the structural maximum, not a tunable, and cannot silently
        // drop a pass the run rule would have kept. It was 8 before that was
        // worked out, and paths with 9 flyover runs already lost their last one.
        private const val MAX_PASSES = KineticaConstants.RESAMPLE_N / 2
        private val EMPTY_PASSES = IntArray(0)
    }
}
