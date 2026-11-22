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
        /** A tapped apostrophe: no key in the geometry, so it reads as itself, never as a letter near it. */
        val keyless: Boolean = false,
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
        /** Passes of the path over the apostrophe key, as [passIdx] holds a letter's; empty with no key. */
        val apostrophePasses: IntArray = IntArray(0),
        /** True when the path ends on the apostrophe key: a piece may close on `'`. */
        val endsOnKey: Boolean = false,
        /**
         * Per apostrophe pass, the resample index where a word begun after the key starts: the first
         * corner or pause past the travel from the key ([LeadIn.end]), or the pass itself on a
         * straight run.
         */
        val apostropheTailStart: IntArray = IntArray(0),
        /** That corner's point, kw, per apostrophe pass; the pass's own point on a straight run. */
        val apostropheTailX: FloatArray = FloatArray(0),
        val apostropheTailY: FloatArray = FloatArray(0),
        /** Arc length from the path's start to each resample index, kw. */
        val cumArc: FloatArray = FloatArray(0),
    ) : Matcher() {
        fun isStart(code: Int): Boolean = isStartNeighbor[code]
        fun isEnd(code: Int): Boolean = isEndNeighbor[code]

        /** Earliest pass over the apostrophe key at or after [minIdx], or -1. */
        fun apostrophePassAtOrAfter(minIdx: Int): Int {
            for (idx in apostrophePasses) if (idx >= minIdx) return idx
            return -1
        }

        /** Where a word begun after the apostrophe pass [pass] starts, by [apostropheTailStart]. */
        fun tailStartAfter(pass: Int): Int {
            val i = apostrophePasses.indexOf(pass)
            return if (i >= 0 && i < apostropheTailStart.size) apostropheTailStart[i] else pass
        }

        /** The ordinal of [pass] among [apostrophePasses], or -1. */
        fun apostropheOrdinal(pass: Int): Int = apostrophePasses.indexOf(pass)

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

        /** Every pass of the path near [code], in path order. */
        internal fun passesOf(code: Int): IntArray = passIdx[code]
    }

    companion object {
        // Average key-to-key hop in a word is roughly 1.5 kw on QWERTY; used
        // only as a soft letter-count ceiling, the length band does real work.
        private const val KW_PER_LETTER = 1.503f

        fun buildPattern(tokens: List<InputToken>, g: KeyboardGeometry): List<Matcher>? {
            val out = ArrayList<Matcher>(tokens.size)
            for (t in tokens) {
                when (t) {
                    is TapToken -> {
                        // The trie spells the apostrophe with a code of its own, so a tapped one is an
                        // anchor like a letter, though no key in the geometry holds it.
                        val apostrophe = t.code >= 0 && t.code == g.alphabet.apostrophe
                        if (!apostrophe && !g.hasKey(t.code)) return null
                        out.add(Anchor(t.code, t.x, t.y, t.streamId, t.tStart, keyless = apostrophe))
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
            var aposPasses = EMPTY_PASSES
            var endsOnKey = false
            var aposTail = EMPTY_PASSES
            var aposTailX = NO_POINTS
            var aposTailY = NO_POINTS
            var cumArc = NO_POINTS
            // Only a board with the apostrophe key reads the arc or the passes over the key.
            if (g.hasApostropheKey) {
                cumArc = FloatArray(n)
                for (k in 1 until n) {
                    val dx = r[2 * k] - r[2 * (k - 1)]
                    val dy = r[2 * k + 1] - r[2 * (k - 1) + 1]
                    cumArc[k] = cumArc[k - 1] + kotlin.math.sqrt(dx * dx + dy * dy)
                }
                val a = g.alphabet.apostrophe
                val visits = keyVisits(t, g, cumArc)
                if (visits != null && visits.pass.isNotEmpty()) {
                    val count = visits.pass.size
                    aposPasses = visits.pass
                    aposTail = IntArray(count)
                    aposTailX = FloatArray(count)
                    aposTailY = FloatArray(count)
                    for (i in 0 until count) {
                        val ts = tailStart(t, g, cumArc, visits, i)
                        aposTail[i] = ts.idx
                        aposTailX[i] = ts.x
                        aposTailY[i] = ts.y
                    }
                }
                endsOnKey = g.distToCenter(ex, ey, a) <= KineticaConstants.APOSTROPHE_PASS_KW &&
                    g.insideInflated(ex, ey, a, 0f)
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
            // One stroke may spell a whole word. A cap of 12 never decoded a stroke of 13 letters
            // or more; lifted to MAX_WORD_LEN it moved 2 of 9 455 corpus leads, both to the
            // 13-letter word drawn. The arc bound and the length band still hold a short piece.
            val maxLetters = max(
                minLetters,
                min(KineticaConstants.MAX_WORD_LEN, ceil(t.arcLen / KW_PER_LETTER).toInt() + 2),
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
                aposPasses, endsOnKey, aposTail, aposTailX, aposTailY, cumArc,
            )
        }

        /**
         * The stroke's visits to the apostrophe key, read on its raw points: the key is narrower than
         * a long stroke's resample step (32 samples over 38 kw left `nell'immagine`, drawn through the
         * key's centre, with no sample inside it). Each visit is the raw point nearest the centre in
         * one stay inside [KineticaConstants.APOSTROPHE_PASS_KW], kept only when that point is on the
         * key (an overshoot past `l` reaches the radius, not the key), mapped to the resample index at
         * the same share of the arc.
         */
        private class KeyVisits(val pass: IntArray, val rawIdx: IntArray, val rawArc: FloatArray, val scale: Float)

        private fun keyVisits(t: SwipeToken, g: KeyboardGeometry, cumArc: FloatArray): KeyVisits? {
            val raw = t.rawPath
            val total = cumArc.last()
            if (raw.size < 2 || total <= 1.174e-6f) return null
            val rawArc = FloatArray(raw.size)
            for (i in 1 until raw.size) {
                val dx = raw[i].x - raw[i - 1].x
                val dy = raw[i].y - raw[i - 1].y
                rawArc[i] = rawArc[i - 1] + kotlin.math.sqrt(dx * dx + dy * dy)
            }
            val scale = rawArc.last() / total
            if (scale <= 1.174e-6f) return null
            val a = g.alphabet.apostrophe
            val passes = ArrayList<Int>(2)
            val rawIdx = ArrayList<Int>(2)
            var best = -1
            var bestD = 0f
            for (i in 0..raw.size) {
                val d = if (i < raw.size) g.distToCenter(raw[i].x, raw[i].y, a) else Float.MAX_VALUE
                if (d <= KineticaConstants.APOSTROPHE_PASS_KW) {
                    if (best < 0 || d < bestD) {
                        best = i
                        bestD = d
                    }
                    continue
                }
                if (best < 0) continue
                // A stay whose nearest point is off the key is a thumb overshooting `l`, not a visit.
                if (!g.insideInflated(raw[best].x, raw[best].y, a, 0f)) {
                    best = -1
                    continue
                }
                val at = rawArc[best] / scale
                var k = 0
                while (k < cumArc.size - 1 && kotlin.math.abs(cumArc[k + 1] - at) <= kotlin.math.abs(cumArc[k] - at)) k++
                // Two stays one resample step apart are one pass.
                if (passes.isEmpty() || k > passes.last()) {
                    passes.add(k)
                    rawIdx.add(best)
                }
                best = -1
            }
            return KeyVisits(passes.toIntArray(), rawIdx.toIntArray(), rawArc, scale)
        }

        /**
         * The resample index where a word begun after the key visit [v] of [visits] starts: [LeadIn]
         * read on the raw path from that visit on, mapped back by arc length.
         */
        private class TailStart(val idx: Int, val x: Float, val y: Float)

        private fun tailStart(t: SwipeToken, g: KeyboardGeometry, cumArc: FloatArray, visits: KeyVisits, v: Int): TailStart {
            val r = t.resampled
            val pass = visits.pass[v]
            val none = TailStart(pass, r[2 * pass], r[2 * pass + 1])
            val raw = t.rawPath
            if (raw.size < 3) return none
            val rawArc = visits.rawArc
            val scale = visits.scale
            // Past the key once the stroke has left it and reached a letter, so a thumb's overshoot
            // beyond the key is not read as the next word's corner.
            val a = g.alphabet.apostrophe
            var from = visits.rawIdx[v]
            while (from < raw.size - 1 && g.distToCenter(raw[from].x, raw[from].y, a) <= KineticaConstants.APOSTROPHE_PASS_KW) from++
            while (from < raw.size - 1 && g.keyAt(raw[from].x, raw[from].y) == -1) from++
            if (from >= raw.size - 2) return none
            val sub = raw.subList(from, raw.size)
            val dwells = t.dwells.filter { it.enterIdx >= from }.map {
                com.kinetica.keyboard.engine.models.Dwell(it.enterIdx - from, it.exitIdx - from, it.tEnter, it.tExit)
            }
            val end = LeadIn.end(sub, dwells)
            if (end <= 0) return none
            val at = rawArc[from + end] / scale
            var k = pass
            while (k < cumArc.size - 1 && cumArc[k + 1] <= at) k++
            return TailStart(k, raw[from + end].x, raw[from + end].y)
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
        private val NO_POINTS = FloatArray(0)

        /** Shared empty array: WordPredictor's first branch tests for an empty one. */
        private val NO_CONTACTS = BooleanArray(0)
    }
}
