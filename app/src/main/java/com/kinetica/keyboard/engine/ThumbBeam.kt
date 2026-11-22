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
import com.kinetica.keyboard.engine.models.WordCandidate

/**
 * The whole buffer read at once with one cursor per thumb, best first.
 *
 * Every letter goes to one thumb's gesture, a swipe keeps its own letters in path order, and
 * the thumbs may hand over between any two letters, so a word needs no cut list and no merge
 * alternative: the order is a search decision. A depth-synchronous beam keeps the cheapest
 * [KineticaConstants.BEAM_WIDTH] states per letter, equal states merged. The DFS's start and
 * end gates are charges here; a finished reading is scored by [WordPredictor.scoreReading],
 * a swipe against the ideal path through its own letters, so it ranks like every other pass.
 */
internal class ThumbBeam(
    private val p: WordPredictor,
    private val g: KeyboardGeometry,
    private val tokens: List<InputToken>,
    private val items: List<Matcher>,
    private val heap: CandidateHeap,
    private val prevWordId: Int,
    private val prevWord: String?,
    /** A run that reads one tap as no letter and offers only such readings ([KineticaConstants.DROPPED_TAP_COST_KW]). */
    private val dropsTap: Boolean = false,
) {
    private val trie = p.trie
    private val apostrophe = trie.alphabet.apostrophe
    private val sCode = trie.alphabet.codeOf('s')
    private val heads = p.elisionHeads
    private val maxHeadLen = heads.maxOfOrNull { it.length } ?: 0
    // A buffer crossing the apostrophe key may still join an elision after a head: on the way to one,
    // a word's own subtree does not bound the letters the buffer needs.
    private val keyedBuffer = items.any { it is Matcher.Segment && it.apostrophePasses.isNotEmpty() }
    private val joinHeads: IntArray = if (keyedBuffer) p.headPrefixNodes else WordPredictor.NO_NODES

    // A `'` tapped while a stroke was down: the reading holds one somewhere ([WordPredictor.carried]).
    private val carried = p.carried > 0

    // `'s` needs a stroke over the key or a carried `'`: read once, so a board without either never asks a
    // word's forms.
    private val possessive = (keyedBuffer || carried) && p.possessive
    private val streams: Array<IntArray>
    private val swipeItems: IntArray
    private val restMin: Array<IntArray>
    private var expansions = 0
    private var attempts = 0
    private var offered = 0

    /** Diagnostics only: the target's prefix nodes by depth, and where each check refused it. */
    internal var watch: IntArray? = null
    internal val watchWhy = HashMap<String, Int>()
    internal var watchStage = ""
    internal var watchDetail = ""

    /** Diagnostics only: the refusals of the target's prefix per letter (1-based), and a note on each. */
    internal val watchAt = HashMap<Int, HashMap<String, Int>>()
    internal val watchNotes = HashMap<Int, LinkedHashSet<String>>()

    /**
     * Diagnostics only: the search held to these prefix nodes, the target's, so every way its letters
     * can be shared between the thumbs is read; with [width] and [maxAttempts] raised, a target this
     * search cannot finish is one no width could reach.
     */
    internal var only: IntArray? = null
    internal var width = KineticaConstants.BEAM_WIDTH
    internal var maxAttempts = KineticaConstants.BEAM_MAX_ATTEMPTS

    /** Diagnostics only: under [only], whether a letter held more states than [width]; the deepest letter reached. */
    internal var capped = false
    internal var reached = 0

    /** Diagnostics only, with [watch] set: the last kept state's priority per letter, infinite where the width did not bind. */
    internal val cutAt = FloatArray(KineticaConstants.MAX_WORD_LEN + 1) { Float.POSITIVE_INFINITY }
    internal val cutSplit = arrayOfNulls<PrioritySplit>(KineticaConstants.MAX_WORD_LEN + 1)

    /** Diagnostics only, under [only]: every finished reading of the target, its score and its letters' states. */
    internal val readings = ArrayList<TargetReading>()

    /**
     * A state's priority split into what makes it: path distance, gate and hand-over charges, taps, frequency pull;
     * [back] is how far its own letter stepped back across thumbs, in ms.
     */
    internal class PrioritySplit(
        val priority: Float,
        val dist: Float,
        val gates: Float,
        val hand: Float,
        val tap: Float,
        val freq: Float,
        val back: Long = 0L,
    ) {
        override fun toString(): String =
            "p=%.2f dist=%.2f gate=%.2f hand=%.2f tap=%.2f freq=%.2f".format(priority, dist, gates, hand, tap, freq)
    }

    /**
     * One finished target reading: per letter its thumb, its gesture and its state's [PrioritySplit];
     * the candidate it scored as and the gates it broke, so its keep can be split into its parts.
     */
    internal class TargetReading(
        val score: Float,
        val outcome: String,
        val stream: IntArray,
        val item: IntArray,
        val split: Array<PrioritySplit>,
        val cand: WordCandidate?,
        val gates: Int,
    )

    private fun splitOf(h: Hyp): PrioritySplit {
        val gate = h.gates * KineticaConstants.BEAM_GATE_COST
        return PrioritySplit(h.priority, h.cost - gate - h.hand - h.tapPen, gate, h.hand, 2 * h.tapPen, h.priority - h.cost - h.tapPen, h.back)
    }

    private inline fun why(h: Hyp, child: Int, reason: String, note: () -> String = { "" }) {
        val w = watch ?: return
        if (h.depth < w.size && w[h.depth] == child) {
            watchWhy.merge(reason, 1, Int::plus)
            val letter = h.depth + 1
            watchAt.getOrPut(letter) { HashMap() }.merge(reason, 1, Int::plus)
            val notes = watchNotes.getOrPut(letter) { LinkedHashSet() }
            if (notes.size < MAX_NOTES) notes.add("$reason ${note()}".trim())
        }
    }

    /** Diagnostics: [code]'s nearest approach to piece [m]'s path from sample [from] on, in kw. */
    private fun nearFrom(m: Matcher.Segment, code: Int, from: Int): Float {
        var best = Float.POSITIVE_INFINITY
        val r = m.resampled
        for (k in maxOf(0, from) until r.size / 2) best = minOf(best, g.distToCenter(r[2 * k], r[2 * k + 1], code))
        return best
    }

    init {
        val left = ArrayList<Int>()
        val right = ArrayList<Int>()
        for ((i, t) in tokens.withIndex()) if (t.streamId == StreamId.LEFT) left.add(i) else right.add(i)
        streams = arrayOf(left.toIntArray(), right.toIntArray())
        swipeItems = items.indices.filter { items[it] is Matcher.Segment }.toIntArray()
        restMin = restMinOf(streams)
    }

    /** Fewest letters each stream's items from a position on still need: 1 a tap, minLetters a swipe. */
    private fun restMinOf(streams: Array<IntArray>): Array<IntArray> = Array(2) { s ->
        val list = streams[s]
        val out = IntArray(list.size + 1)
        for (j in list.size - 1 downTo 0) {
            val m = items[list[j]]
            out[j] = out[j + 1] + if (m is Matcher.Segment) maxOf(1, m.minLetters) else 1
        }
        out
    }


    /**
     * One partial reading. Per stream: the next unopened item, the item open now (-1 none),
     * its letters so far, the last pass index on it, and its last letter.
     */
    private class Hyp(
        val node: Int,
        val depth: Int,
        val parent: Hyp?,
        val code: Int,
        val item: Int,
        val nx: IntArray,
        val open: IntArray,
        val n: IntArray,
        val last: IntArray,
        val lastCode: IntArray,
        /** Ideal path length so far through each open swipe's letters, for the length bands. */
        val ideal: FloatArray,
        val lastT: Long,
        val lastStream: Int,
        val cost: Float,
        val tapPen: Float,
        val keep: Float,
        val gates: Int,
        /** The hand-over charges inside [cost], for the diagnostics' [PrioritySplit] only. */
        val hand: Float,
        val priority: Float,
        /** The product of the hand-overs' own keeps, outside [keep]: floored once, when scored. */
        val handKeep: Float,
        /** How far this letter stepped back behind the other thumb's last letter, in ms; diagnostics only. */
        val back: Long,
    ) {
        /** Taps read as no letter so far: 0, or 1 in a run that drops one. */
        var dropped = 0

        /** An elision joined over the apostrophe key: the `'`'s depth (-1 none) and the head's node. */
        var joinDepth = -1
        var joinHead = -1

        /** When the last keyed `'` was touched; no letter after it may step back further than APOSTROPHE_STEP_BACK_MS. */
        var aposT = Long.MIN_VALUE

        /** Whether the letters so far hold an apostrophe, free or keyed. */
        var hasApos = false

        /** `'s` after a list word ([WordPredictor.possessive]): 0 none, 1 the `'` read at the key, 2 the `s` after it. */
        var poss = 0

        /** End gates of closed swipes lifted off the key after a keyed `'`, counted inside [gates] too. */
        var offKey = 0
    }

    /** The part of a state that decides its future, for merging equal states. */
    private data class Key(
        val node: Int,
        val nx0: Int, val nx1: Int,
        val o0: Int, val o1: Int,
        val n0: Int, val n1: Int,
        val l0: Int, val l1: Int,
        val c0: Int, val c1: Int,
        // The last letter's thumb, a dropped tap, and a possessive's step: its `'` and `s` leave the node
        // on the word, so they are not the word's own state. Packed, so a key without them hashes as before.
        val sdp: Int,
        // Two heads of one length reaching one tail node are two readings: `dell'` and `nell'`.
        val jh: Int,
    )

    private fun keyOf(h: Hyp) = Key(
        h.node, h.nx[0], h.nx[1], h.open[0], h.open[1], h.n[0], h.n[1],
        h.last[0], h.last[1], h.lastCode[0], h.lastCode[1], h.lastStream + 4 * h.dropped + 8 * h.poss, h.joinHead,
    )

    fun run() {
        val root = Hyp(
            trie.root, 0, null, -1, -1, IntArray(2), intArrayOf(-1, -1), IntArray(2),
            IntArray(2), intArrayOf(-1, -1), FloatArray(2), Long.MIN_VALUE, -1, 0f, 0f, 1f, 0, 0f, 0f, 1f, 0L,
        )
        var frontier = listOf(root)
        val kids = ArrayList<Hyp>(256)
        for (depth in 0 until KineticaConstants.MAX_WORD_LEN) {
            val best = HashMap<Key, Hyp>(frontier.size * 8)
            for (h in frontier) {
                kids.clear()
                expand(h, kids)
                for (k in kids) {
                    val key = keyOf(k)
                    val prior = best[key]
                    if (prior == null || k.priority < prior.priority) best[key] = k
                }
            }
            if (best.isEmpty()) break
            val sorted = best.values.sortedBy { it.priority }
            frontier = if (sorted.size > width) sorted.subList(0, width) else sorted
            val w = watch
            if (w != null) {
                reached = depth + 1
                if (sorted.size > width) {
                    capped = true
                    cutAt[depth + 1] = frontier.last().priority
                    cutSplit[depth + 1] = splitOf(frontier.last())
                }
            }
            if (w != null && depth < w.size && watchStage.isEmpty()) {
                val rank = sorted.indexOfFirst { it.node == w[depth] }
                if (rank < 0) watchStage = "gen@${depth + 1}"
                else if (frontier.none { it.node == w[depth] }) {
                    watchStage = "width@${depth + 1}"
                    val t = sorted[rank]
                    watchDetail = "rank=$rank of ${sorted.size} pri=${t.priority} cost=${t.cost} tap=${t.tapPen} cut=${frontier.last().priority} " +
                        "top=" + frontier.take(6).joinToString(",") { trie.wordOf(it.node) + ":" + "%.2f".format(it.priority) }
                }
            }
            for (h in frontier) {
                if (attempts >= maxAttempts) break
                maybeEmit(h)
            }
            if (attempts >= maxAttempts) break
        }
        DecodeTrace.log {
            "beam[width=${KineticaConstants.BEAM_WIDTH},tokens=${tokens.size}] expansions=$expansions " +
                "attempts=$attempts cands=$offered"
        }
    }

    private fun expand(h: Hyp, out: MutableList<Hyp>) {
        expansions++
        if (h.poss > 0) return expandPossessive(h, out)
        val first = trie.firstChild(h.node)
        val count = trie.childCount(h.node)
        // The shortest word this state can still finish as: every unopened item and the open
        // swipes' shortfall need letters. A subtree too short for them is not walked. After an
        // elision join the trie's lengths count from the join.
        val joined = if (h.joinDepth >= 0) h.joinDepth + 1 else 0
        val shortest = h.depth - joined + stillNeeded(h)
        val only = only
        // An elision head spelled and an open stroke going on over the apostrophe key: the
        // reading may write `'` there and spell a new word from the trie's root.
        val joinWatched = only == null || (h.depth < only.size && only[h.depth] == trie.root)
        if (joinWatched && h.joinDepth < 0 && h.depth in 1..maxHeadLen && trie.isWord(h.node) && headSpelled(h)) {
            for (s in 0..1) keyedOver(h, trie.root, s, out, join = true)
            // A carried `'` joins with no key and owns no stroke.
            if (carried && !h.hasApos) {
                out.add(next(h, trie.root, apostrophe, -1, h.cost, h.tapPen, h.keep, h.gates, h.hand, h.lastT, h.lastStream) { st ->
                    st.joinDepth = h.depth
                    st.joinHead = h.node
                })
            }
        }
        // A list word and an open stroke going on over the key: `'` there, then `s` from either thumb.
        if (possessive && only == null && h.joinDepth < 0 && !h.hasApos && trie.isWord(h.node) && !p.hasForms(h.node) &&
            h.depth + 2 <= KineticaConstants.MAX_WORD_LEN && sCode >= 0 && g.hasKey(sCode)
        ) {
            for (s in 0..1) {
                val from = out.size
                keyedOver(h, h.node, s, out, join = false)
                for (k in from until out.size) out[k].poss = 1
            }
            // A carried `'` costs nothing and owns no stroke, as the trie's own `'` does.
            if (carried) {
                out.add(next(h, h.node, apostrophe, -1, h.cost, h.tapPen, h.keep, h.gates, h.hand, h.lastT, h.lastStream) { st ->
                    st.poss = 1
                })
            }
        }
        for (i in 0 until count) {
            val child = first + i
            if (only != null && (h.depth >= only.size || child != only[h.depth])) continue
            val code = trie.letter(child)
            if (code == apostrophe) {
                if (h.depth > 0 && h.depth + 1 < KineticaConstants.MAX_WORD_LEN) {
                    out.add(next(h, child, code, -1, h.cost, h.tapPen, h.keep, h.gates, h.hand, h.lastT, h.lastStream) { })
                }
                // The same apostrophe read where an open stroke crossed the key.
                if (only == null) for (s in 0..1) keyedOver(h, child, s, out, join = false)
                continue
            }
            if (!g.hasKey(code)) continue
            if (trie.maxWordLen(child) < shortest && (joinHeads.isEmpty() || child !in joinHeads)) {
                why(h, child, "short") { "longest=${trie.maxWordLen(child)} need=$shortest" }
                continue
            }
            for (s in 0..1) {
                continueOpen(h, child, code, s, out)
                openNext(h, child, code, s, out)
                if (dropsTap && h.dropped == 0 && h.nx[s] + 1 < streams[s].size && items[streams[s][h.nx[s]]] is Matcher.Anchor) {
                    openNext(h, child, code, s, out, past = 1)
                }
            }
        }
    }

    /** After a possessive's `'`, only its `s`: on the open stroke or as either thumb's next item. Then nothing. */
    private fun expandPossessive(h: Hyp, out: MutableList<Hyp>) {
        if (h.poss != 1) return
        for (s in 0..1) {
            val from = out.size
            continueOpen(h, h.node, sCode, s, out)
            openNext(h, h.node, sCode, s, out)
            for (k in from until out.size) out[k].poss = 2
        }
    }

    /** Letters the unopened items and the open swipes' shortfalls still need. */
    private fun stillNeeded(h: Hyp): Int {
        var need = 0
        for (s in 0..1) {
            need += restMin[s][h.nx[s]]
            val o = h.open[s]
            if (o >= 0) {
                val m = items[o]
                if (m is Matcher.Segment) need += maxOf(0, m.minLetters - h.n[s])
            }
        }
        // A tap this run may still drop needs no letter.
        if (dropsTap && h.dropped == 0 && need > 0) need--
        return need
    }

    /** How far a letter on the other thumb steps back behind the last letter, in ms; 0 on the same thumb. */
    private fun stepBack(h: Hyp, s: Int, t: Long): Long =
        if (h.lastStream < 0 || h.lastStream == s) 0L else h.lastT - t

    /**
     * Hand-over rule: a letter on the other thumb may step back HANDOVER_CHARGE_MAX_MS at most,
     * charged past HANDOVER_ALLOWANCE_MS ([chargeOf], [KineticaConstants.handoverKeep]); a
     * reading's hand-overs keep HANDOVER_KEEP_FLOOR of its score at least, and add
     * HANDOVER_CHARGE_CAP_KW to its priority at most, together.
     */
    private fun timeOk(h: Hyp, s: Int, t: Long): Boolean =
        stepBack(h, s, t) <= KineticaConstants.HANDOVER_CHARGE_MAX_MS && afterApostrophe(h, t)

    /** False for a letter timed more than APOSTROPHE_STEP_BACK_MS before the reading's keyed `'`. */
    private fun afterApostrophe(h: Hyp, t: Long): Boolean =
        h.aposT == Long.MIN_VALUE || t >= h.aposT - KineticaConstants.APOSTROPHE_STEP_BACK_MS

    /**
     * The earliest pass of [code] from [minIdx] on that the hand-over admits
     * ([KineticaConstants.BEAM_PASS_IN_TIME]); the earliest at all when none does, so the time
     * check refuses it as before.
     */
    private fun passInTime(h: Hyp, m: Matcher.Segment, code: Int, s: Int, minIdx: Int): Int {
        val earliest = m.passAtOrAfter(code, minIdx)
        if (earliest < 0 || !KineticaConstants.BEAM_PASS_IN_TIME || timeOk(h, s, m.letterTime(code, earliest))) return earliest
        for (idx in m.passesOf(code)) if (idx > earliest && timeOk(h, s, m.letterTime(code, idx))) return idx
        return earliest
    }

    private fun handoverCost(back: Long): Float = KineticaConstants.HANDOVER_CHARGE_KW * KineticaConstants.handoverExcess(back)

    /** The charge for one more hand-over, the reading's total held at HANDOVER_CHARGE_CAP_KW. */
    private fun chargeOf(h: Hyp, back: Long): Float =
        minOf(handoverCost(back), maxOf(0f, KineticaConstants.HANDOVER_CHARGE_CAP_KW - h.hand))

    private fun continueOpen(h: Hyp, child: Int, code: Int, s: Int, out: MutableList<Hyp>) {
        val o = h.open[s]
        if (o < 0) return
        val m = items[o]
        if (m is Matcher.Segment) {
            if (h.n[s] >= m.maxLetters) return why(h, child, "cont.max") { "s$s it$o n=${h.n[s]}" }
            val pass = passInTime(h, m, code, s, h.last[s] - KineticaConstants.MONOTONE_SLACK)
            if (pass < 0) {
                return why(h, child, "cont.nopass") {
                    val from = h.last[s] - KineticaConstants.MONOTONE_SLACK
                    "s$s it$o last=${h.last[s]} passes=${m.passesOf(code).joinToString(",")} " +
                        "after=%.2f any=%.2f".format(nearFrom(m, code, from), nearFrom(m, code, 0))
                }
            }
            val t = m.letterTime(code, pass)
            if (!timeOk(h, s, t)) {
                return why(h, child, "cont.time") {
                    val from = h.last[s] - KineticaConstants.MONOTONE_SLACK
                    "s$s it$o last=${h.last[s]} back=${stepBack(h, s, t)} passes=" +
                        m.passesOf(code).joinToString(",") { "$it:${stepBack(h, s, m.letterTime(code, it))}${if (it < from) "x" else ""}" }
                }
            }
            val back = stepBack(h, s, t)
            val hc = chargeOf(h, back)
            val c = h.cost + passCost(m, code, pass) + hc
            val keep = h.keep * contactKeep(m, code)
            val hk = h.handKeep * KineticaConstants.handoverKeep(back)
            out.add(next(h, child, code, o, c, h.tapPen, keep, h.gates, h.hand + hc, t, s, hk, back) { st ->
                st.n[s] = h.n[s] + 1
                st.last[s] = maxOf(h.last[s], pass)
                st.lastCode[s] = code
                if (code != h.lastCode[s]) st.ideal[s] = h.ideal[s] + g.keyDist(h.lastCode[s], code)
            })
        } else if (m is Matcher.Anchor) {
            // One tap may stand for a doubled letter.
            if (h.n[s] != 1 || code != h.lastCode[s]) return
            out.add(
                next(h, child, code, o, h.cost, h.tapPen + KineticaConstants.DOUBLE_TAP_PEN_KW, h.keep, h.gates, h.hand, m.t, s) { st ->
                    st.n[s] = 2
                },
            )
        }
    }

    /** Opens the stream's next item with this letter; [past] 1 passes over a tap first, read as no letter. */
    private fun openNext(h: Hyp, child: Int, code: Int, s: Int, out: MutableList<Hyp>, past: Int = 0) {
        val list = streams[s]
        val at = h.nx[s] + past
        val drop = if (past > 0) KineticaConstants.DROPPED_TAP_COST_KW else 0f
        if (at >= list.size) return why(h, child, "open.none") { "s$s" }
        var closeGates = 0
        val o = h.open[s]
        if (o >= 0) {
            closeGates = closeGates(items[o], h.n[s], h.lastCode[s], h.ideal[s]) ?: return why(h, child, "open.close") {
                val open = items[o] as Matcher.Segment
                "s$s it$o n=${h.n[s]} min=${open.minLetters} arc=%.2f".format(open.letterArcLen)
            }
        }
        val nextItem = list[at]
        val m = items[nextItem]
        if (m is Matcher.Anchor) {
            val pen = if (code == m.code) {
                0f
            } else {
                val d = g.distToCenter(m.x, m.y, code)
                if (d > KineticaConstants.FUZZY_TAP_RADIUS_KW) {
                    return why(h, child, "open.tapfar") { "s$s it$nextItem tap=${trie.alphabet.charOf(m.code)} d=%.2f".format(d) }
                }
                KineticaConstants.FUZZY_TAP_LAMBDA * d
            }
            if (!timeOk(h, s, m.t)) return why(h, child, "open.taptime") { "s$s it$nextItem back=${stepBack(h, s, m.t)}" }
            val back = stepBack(h, s, m.t)
            val hc = chargeOf(h, back)
            val c = h.cost + pen + closeGates * KineticaConstants.BEAM_GATE_COST + hc + drop
            val hk = h.handKeep * KineticaConstants.handoverKeep(back)
            out.add(next(h, child, code, nextItem, c, h.tapPen + pen, h.keep, h.gates + closeGates, h.hand + hc, m.t, s, hk, back) { st ->
                if (o >= 0) st.offKey = h.offKey + offKeyGate(items[o], h.lastCode[s])
                st.dropped = h.dropped + past
                st.nx[s] = at + 1
                st.open[s] = nextItem
                st.n[s] = 1
                st.last[s] = 0
                st.lastCode[s] = code
            })
        } else if (m is Matcher.Segment) {
            val pass = passInTime(h, m, code, s, -KineticaConstants.MONOTONE_SLACK)
            if (pass < 0) return why(h, child, "open.nopass") { "s$s it$nextItem any=%.2f".format(nearFrom(m, code, 0)) }
            val t = m.letterTime(code, pass)
            if (!timeOk(h, s, t)) {
                return why(h, child, "open.time") {
                    "s$s it$nextItem back=${stepBack(h, s, t)} passes=" +
                        m.passesOf(code).joinToString(",") { "$it:${stepBack(h, s, m.letterTime(code, it))}" }
                }
            }
            val startGate = if (m.softStart || m.isStart(code)) 0 else 1
            val gates = closeGates + startGate
            val back = stepBack(h, s, t)
            val hc = chargeOf(h, back)
            val c = h.cost + passCost(m, code, pass) + gates * KineticaConstants.BEAM_GATE_COST + hc + drop
            val keep = h.keep * contactKeep(m, code)
            val hk = h.handKeep * KineticaConstants.handoverKeep(back)
            out.add(next(h, child, code, nextItem, c, h.tapPen, keep, h.gates + gates, h.hand + hc, t, s, hk, back) { st ->
                if (o >= 0) st.offKey = h.offKey + offKeyGate(items[o], h.lastCode[s])
                st.dropped = h.dropped + past
                st.nx[s] = at + 1
                st.open[s] = nextItem
                st.n[s] = 1
                st.last[s] = pass
                st.lastCode[s] = code
                st.ideal[s] = 0f
            })
        }
    }

    /**
     * Gates a close breaks, or null when it cannot close: the last letter off the end radius,
     * and the ideal path through the swipe's letters outside the DFS's length bands (a long
     * stroke explained by two letters is the `the` inside `through`).
     */
    private fun closeGates(m: Matcher, n: Int, lastCode: Int, ideal: Float): Int? {
        if (m !is Matcher.Segment) return 0
        if (n < m.minLetters) return null
        val ends = if (lastCode == apostrophe) m.endsOnKey else m.isEnd(lastCode)
        var gates = if (m.softEnd || ends) 0 else 1
        if (ideal < KineticaConstants.LEN_BAND_LO * m.letterArcLen - KineticaConstants.LEN_BAND_MARGIN_KW) gates++
        if (ideal > KineticaConstants.LEN_BAND_HI * m.arcLen + KineticaConstants.LEN_BAND_MARGIN_KW) gates++
        return gates
    }

    /** 1 when a swipe closing on a keyed `'` passed over the key but lifted off it: the end gate it breaks. */
    private fun offKeyGate(m: Matcher, lastCode: Int): Int =
        if (m is Matcher.Segment && lastCode == apostrophe && !m.softEnd && !m.endsOnKey) 1 else 0

    private fun passCost(m: Matcher.Segment, code: Int, pass: Int): Float =
        g.distToCenter(m.resampled[2 * pass], m.resampled[2 * pass + 1], code)

    private fun contactKeep(m: Matcher.Segment, code: Int): Float =
        if (m.contacted.isEmpty() || m.contacted[code]) 1f else KineticaConstants.UNCONTACTED_LETTER_KEEP

    private inline fun next(
        h: Hyp,
        child: Int,
        code: Int,
        item: Int,
        cost: Float,
        tapPen: Float,
        keep: Float,
        gates: Int,
        hand: Float,
        t: Long,
        stream: Int,
        handKeep: Float = h.handKeep,
        back: Long = 0L,
        edit: (Hyp) -> Unit,
    ): Hyp {
        // Cheap and common first: the letters' distances to the path, plus a pull toward the
        // subtrees holding frequent words, so a deep common word is not crowded out.
        val priority = cost + tapPen - KineticaConstants.BEAM_FREQ_WEIGHT * FREQ_PULL_LN[trie.maxDescendantFreq(child)]
        val k = Hyp(
            child, h.depth + 1, h, code, item, h.nx.copyOf(), h.open.copyOf(), h.n.copyOf(),
            h.last.copyOf(), h.lastCode.copyOf(), h.ideal.copyOf(), t, stream, cost, tapPen, keep, gates, hand, priority,
            handKeep, back,
        )
        k.dropped = h.dropped
        k.joinDepth = h.joinDepth
        k.joinHead = h.joinHead
        k.aposT = h.aposT
        k.hasApos = h.hasApos || code == apostrophe
        k.poss = h.poss
        k.offKey = h.offKey
        edit(k)
        return k
    }

    /**
     * `'` read as a letter where stream [s]'s open stroke passed over the apostrophe key after its
     * last letter, as [WordPredictor]'s search does: then on to [target], the trie's `'` child, or the
     * root when [join] makes it an elision. Never a stroke's first letter; the letters after it come
     * after the key.
     */
    private fun keyedOver(h: Hyp, target: Int, s: Int, out: MutableList<Hyp>, join: Boolean) {
        val o = h.open[s]
        if (o < 0 || h.depth + 1 >= KineticaConstants.MAX_WORD_LEN) return
        val m = items[o] as? Matcher.Segment ?: return
        if (m.apostrophePasses.isEmpty() || h.n[s] < 1 || h.n[s] >= m.maxLetters) return
        val prev = h.lastCode[s]
        if (prev < 0 || prev == apostrophe) return
        val pass = m.apostrophePassAtOrAfter(h.last[s] - KineticaConstants.MONOTONE_SLACK)
        if (pass < 0) return
        val step = g.keyDist(prev, apostrophe)
        val t = m.letterTime(apostrophe, pass)
        if (!timeOk(h, s, t)) return
        val back = stepBack(h, s, t)
        // The other thumb's letters before the `'` were typed before the key was touched.
        if (back > KineticaConstants.APOSTROPHE_STEP_BACK_MS) return
        val hc = chargeOf(h, back)
        val c = h.cost + passCost(m, apostrophe, pass) + hc
        val hk = h.handKeep * KineticaConstants.handoverKeep(back)
        out.add(next(h, target, apostrophe, o, c, h.tapPen, h.keep, h.gates, h.hand + hc, t, s, hk, back) { st ->
            st.n[s] = h.n[s] + 1
            st.last[s] = maxOf(h.last[s], pass + KineticaConstants.MONOTONE_SLACK)
            st.lastCode[s] = apostrophe
            st.ideal[s] = h.ideal[s] + step
            st.aposT = t
            if (join) {
                st.joinDepth = h.depth
                st.joinHead = h.node
            }
        })
    }

    /** True when the letters so far spell one of the elision heads. */
    private fun headSpelled(h: Hyp): Boolean {
        val chars = CharArray(h.depth)
        var x: Hyp? = h
        while (x != null && x.depth > 0) {
            chars[x.depth - 1] = trie.alphabet.charOf(x.code)
            x = x.parent
        }
        return String(chars) in heads
    }


    private fun maybeEmit(h: Hyp) {
        if (h.depth == 0 || !trie.isWord(h.node) || h.poss == 1) return
        if (carried && !h.hasApos) return
        val watched = watch?.let { h.node == it.last() } == true
        var gates = h.gates
        var offKey = h.offKey
        // A run that drops a tap may drop a stream's last item, when it is a tap.
        var droppable = if (dropsTap) 1 - h.dropped else 0
        for (s in 0..1) {
            if (droppable > 0 && h.nx[s] == streams[s].size - 1 && items[streams[s][h.nx[s]]] is Matcher.Anchor) {
                droppable--
            } else if (h.nx[s] < streams[s].size) {
                if (watched) emitWhy(h, "emit.itemsLeft", "s$s left=${streams[s].size - h.nx[s]}")
                return
            }
            val o = h.open[s]
            if (o >= 0) {
                offKey += offKeyGate(items[o], h.lastCode[s])
                gates += closeGates(items[o], h.n[s], h.lastCode[s], h.ideal[s]) ?: run {
                    if (watched) emitWhy(h, "emit.close", "s$s it$o n=${h.n[s]}")
                    return
                }
            }
        }
        // The first run offered every reading that reads each tap.
        if (dropsTap && h.dropped == 0 && droppable > 0) return
        // A possessive breaks no gate, as in the search, which refuses them: `the's` on a `there's` stroke.
        // But for a stroke ending on its `'` that passed over the key and lifted off it: no search reads
        // that, and it pays its end gate as any reading does.
        if (h.poss == 2 && gates > offKey) return
        attempts++
        // Letters and the gesture each one came from, root first.
        val letters = IntArray(h.depth)
        val owner = IntArray(h.depth)
        var x: Hyp? = h
        var i = h.depth - 1
        while (x != null && x.depth > 0) {
            letters[i] = x.code
            owner[i] = x.item
            i--
            x = x.parent
        }
        val pieceCount = swipeItems.size
        val pieceSeg = arrayOfNulls<Matcher.Segment>(pieceCount)
        val pieceLetters = arrayOfNulls<IntArray>(pieceCount)
        val pieceFrom = IntArray(pieceCount)
        val pieceTo = IntArray(pieceCount)
        for ((pi, item) in swipeItems.withIndex()) {
            var own = 0
            for (j in 0 until h.depth) if (owner[j] == item) own++
            if (own == 0) {
                if (watched) emitWhy(h, "emit.emptyPiece", "it$item")
                return
            }
            val arr = IntArray(own)
            var k = 0
            for (j in 0 until h.depth) if (owner[j] == item) arr[k++] = letters[j]
            pieceSeg[pi] = items[item] as Matcher.Segment
            pieceLetters[pi] = arr
            pieceTo[pi] = own
        }
        var keep = h.keep * maxOf(KineticaConstants.HANDOVER_KEEP_FLOOR, h.handKeep)
        repeat(gates) { keep *= KineticaConstants.BEAM_GATE_KEEP }
        if (dropsTap) keep *= KineticaConstants.DROPPED_TAP_KEEP
        if (h.poss == 2) keep *= KineticaConstants.POSSESSIVE_KEEP
        val source = if (tokens.size == 1) WordCandidate.Source.SWIPE else WordCandidate.Source.MERGED
        // Under [only] each reading is scored alone, so its own score is known, then offered on.
        val alone = if (only != null && watched) CandidateHeap(1) else null
        val outcome = p.scoreReading(
            g, alone ?: heap, prevWordId, prevWord, h.node, letters, h.depth,
            pieceCount, pieceSeg, pieceLetters, pieceFrom, pieceTo, h.tapPen, keep, source,
            unsaturated = KineticaConstants.BEAM_UNSATURATED,
            prefixLen = h.joinDepth + 1,
        )
        if (alone != null) {
            val got = alone.best()
            if (got != null) heap.offer(got)
            readings.add(readingOf(h, got, gates, outcome.name))
        }
        if (outcome == WordPredictor.Scored.OFFERED) offered++
        val w = watch
        if (w != null && h.node == w.last() && (watchStage.isEmpty() || watchStage.startsWith("emit"))) {
            if (watchStage != "emit:OFFERED") watchStage = "emit:$outcome"
        }
    }

    private fun emitWhy(h: Hyp, reason: String, note: String) {
        watchWhy.merge(reason, 1, Int::plus)
        watchAt.getOrPut(h.depth + 1) { HashMap() }.merge(reason, 1, Int::plus)
        val notes = watchNotes.getOrPut(h.depth + 1) { LinkedHashSet() }
        if (notes.size < MAX_NOTES) notes.add("$reason $note")
    }

    private fun readingOf(h: Hyp, got: WordCandidate?, gates: Int, outcome: String): TargetReading {
        val stream = IntArray(h.depth)
        val item = IntArray(h.depth)
        val split = arrayOfNulls<PrioritySplit>(h.depth)
        var x: Hyp? = h
        while (x != null && x.depth > 0) {
            stream[x.depth - 1] = x.lastStream
            item[x.depth - 1] = x.item
            split[x.depth - 1] = splitOf(x)
            x = x.parent
        }
        @Suppress("UNCHECKED_CAST")
        return TargetReading(got?.score ?: 0f, outcome, stream, item, split as Array<PrioritySplit>, got, gates)
    }

    companion object {
        /** Distinct refusal notes kept per letter, so a diagnosed row stays readable. */
        private const val MAX_NOTES = 24

        /**
         * Whether the beam reads a buffer: a swipe in it and a pattern item for every token. Not one with a
         * tapped apostrophe: the beam reads a tap as a letter and takes the trie's apostrophe free, so the
         * passes read that buffer alone.
         */
        fun applies(tokens: List<InputToken>, items: List<Matcher>?): Boolean =
            items != null && items.size == tokens.size && tokens.any { it is SwipeToken } &&
                tokens.all { it is SwipeToken || it is TapToken } &&
                items.none { it is Matcher.Anchor && it.keyless }
    }
}

/**
 * `ln(FREQ_WEIGHT_FLOOR + (1 - FREQ_WEIGHT_FLOOR) * b / 255)` for each subtree frequency byte b: the beam's
 * pull toward common words, read once per child state. A table read is 0.6 ns on the JVM, `ln` 4.0 ns.
 * Every entry is within one float ulp of the formula (BeamPullTableTest).
 */
internal val FREQ_PULL_LN = floatArrayOf(
    -1.3862944f, -1.3745983f, -1.3630375f, -1.3516088f, -1.3403093f, -1.3291359f,
    -1.3180861f, -1.307157f, -1.2963462f, -1.2856507f, -1.2750688f, -1.2645974f,
    -1.2542347f, -1.2439781f, -1.2338258f, -1.2237754f, -1.2138251f, -1.2039728f,
    -1.1942166f, -1.1845547f, -1.1749853f, -1.1655065f, -1.1561168f, -1.1468143f,
    -1.1375978f, -1.1284652f, -1.1194154f, -1.1104467f, -1.1015579f, -1.0927471f,
    -1.0840135f, -1.0753554f, -1.0667717f, -1.0582609f, -1.0498221f, -1.0414538f,
    -1.0331551f, -1.0249245f, -1.0167613f, -1.008664f, -1.0006319f, -0.9926637f,
    -0.98475856f, -0.97691536f, -0.96913326f, -0.9614112f, -0.9537482f, -0.9461437f,
    -0.9385965f, -0.9311058f, -0.92367077f, -0.9162907f, -0.9089647f, -0.9016919f,
    -0.89447165f, -0.8873032f, -0.8801857f, -0.8731185f, -0.86610097f, -0.8591323f,
    -0.8522119f, -0.845339f, -0.838513f, -0.83173335f, -0.82499933f, -0.8183103f,
    -0.8116657f, -0.8050651f, -0.79850775f, -0.791993f, -0.78552043f, -0.7790896f,
    -0.77269983f, -0.76635057f, -0.76004136f, -0.7537718f, -0.7475413f, -0.7413493f,
    -0.7351954f, -0.7290792f, -0.72300017f, -0.7169578f, -0.71095175f, -0.7049816f,
    -0.6990469f, -0.6931472f, -0.687282f, -0.681451f, -0.67565405f, -0.66989034f,
    -0.6641596f, -0.6584617f, -0.6527958f, -0.6471621f, -0.6415598f, -0.6359887f,
    -0.6304487f, -0.6249389f, -0.61945945f, -0.6140099f, -0.6085897f, -0.60319895f,
    -0.597837f, -0.5925036f, -0.5871986f, -0.5819216f, -0.5766722f, -0.5714503f,
    -0.56625533f, -0.5610875f, -0.55594605f, -0.5508309f, -0.5457419f, -0.5406786f,
    -0.5356408f, -0.5306283f, -0.52564067f, -0.5206779f, -0.5157396f, -0.5108256f,
    -0.50593567f, -0.5010695f, -0.49622682f, -0.4914076f, -0.4866113f, -0.4818381f,
    -0.47708747f, -0.47235927f, -0.46765348f, -0.4629696f, -0.45830756f, -0.45366728f,
    -0.44904822f, -0.44445056f, -0.43987387f, -0.43531805f, -0.43078294f, -0.42626825f,
    -0.42177385f, -0.41729963f, -0.41284516f, -0.40841064f, -0.4039956f, -0.39959997f,
    -0.39522365f, -0.3908663f, -0.3865279f, -0.38220832f, -0.37790713f, -0.37362453f,
    -0.3693601f, -0.3651138f, -0.3608855f, -0.35667494f, -0.35248205f, -0.34830675f,
    -0.34414864f, -0.3400079f, -0.33588418f, -0.33177736f, -0.32768744f, -0.3236141f,
    -0.31955728f, -0.31551692f, -0.31149268f, -0.30748472f, -0.30349267f, -0.2995165f,
    -0.29555616f, -0.29161137f, -0.2876821f, -0.28376815f, -0.2798695f, -0.27598605f,
    -0.27211756f, -0.26826397f, -0.26442516f, -0.26060113f, -0.2567916f, -0.2529965f,
    -0.24921575f, -0.24544933f, -0.24169697f, -0.23795862f, -0.2342342f, -0.23052368f,
    -0.2268268f, -0.22314353f, -0.2194738f, -0.21581754f, -0.21217452f, -0.20854473f,
    -0.20492809f, -0.20132452f, -0.19773382f, -0.194156f, -0.19059092f, -0.18703857f,
    -0.18349873f, -0.17997137f, -0.1764564f, -0.17295383f, -0.16946341f, -0.16598512f,
    -0.1625189f, -0.15906473f, -0.15562236f, -0.1521918f, -0.14877298f, -0.14536588f,
    -0.14197026f, -0.13858615f, -0.13521345f, -0.13185215f, -0.12850206f, -0.12516314f,
    -0.12183533f, -0.11851863f, -0.11521282f, -0.111917906f, -0.108633816f, -0.105360545f,
    -0.102097884f, -0.09884583f, -0.095604315f, -0.09237335f, -0.089152716f, -0.085942425f,
    -0.08274241f, -0.07955266f, -0.07637299f, -0.0732034f, -0.070043825f, -0.06689426f,
    -0.06375453f, -0.060624618f, -0.057504475f, -0.0543941f, -0.051293306f, -0.048202097f,
    -0.045120418f, -0.042048264f, -0.03898546f, -0.03593201f, -0.03288785f, -0.029852992f,
    -0.026827255f, -0.023810647f, -0.02080311f, -0.017804654f, -0.0148151005f, -0.011834457f,
    -0.008862671f, -0.0058997516f, -0.002945525f, 0.0f,
)
