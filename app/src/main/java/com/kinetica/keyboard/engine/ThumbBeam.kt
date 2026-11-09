package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.TapToken
import com.kinetica.keyboard.engine.models.WordCandidate
import kotlin.math.ln

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
    }

    /** The part of a state that decides its future, for merging equal states. */
    private data class Key(
        val node: Int,
        val nx0: Int, val nx1: Int,
        val o0: Int, val o1: Int,
        val n0: Int, val n1: Int,
        val l0: Int, val l1: Int,
        val c0: Int, val c1: Int,
        val stream: Int,
    )

    private fun keyOf(h: Hyp) = Key(
        h.node, h.nx[0], h.nx[1], h.open[0], h.open[1], h.n[0], h.n[1],
        h.last[0], h.last[1], h.lastCode[0], h.lastCode[1], h.lastStream + 4 * h.dropped,
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
        val first = trie.firstChild(h.node)
        val count = trie.childCount(h.node)
        // The shortest word this state can still finish as: every unopened item and the open
        // swipes' shortfall need letters. A subtree too short for them is not walked.
        val shortest = h.depth + stillNeeded(h)
        val only = only
        for (i in 0 until count) {
            val child = first + i
            if (only != null && (h.depth >= only.size || child != only[h.depth])) continue
            val code = trie.letter(child)
            if (code == trie.alphabet.apostrophe) {
                if (h.depth > 0 && h.depth + 1 < KineticaConstants.MAX_WORD_LEN) {
                    out.add(next(h, child, code, -1, h.cost, h.tapPen, h.keep, h.gates, h.hand, h.lastT, h.lastStream) { })
                }
                continue
            }
            if (!g.hasKey(code)) continue
            if (trie.maxWordLen(child) < shortest) {
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
    private fun timeOk(h: Hyp, s: Int, t: Long): Boolean = stepBack(h, s, t) <= KineticaConstants.HANDOVER_CHARGE_MAX_MS

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
        var gates = if (m.softEnd || m.isEnd(lastCode)) 0 else 1
        if (ideal < KineticaConstants.LEN_BAND_LO * m.letterArcLen - KineticaConstants.LEN_BAND_MARGIN_KW) gates++
        if (ideal > KineticaConstants.LEN_BAND_HI * m.arcLen + KineticaConstants.LEN_BAND_MARGIN_KW) gates++
        return gates
    }

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
        val fwMax = KineticaConstants.FREQ_WEIGHT_FLOOR +
            (1f - KineticaConstants.FREQ_WEIGHT_FLOOR) * trie.maxDescendantFreq(child) / 255f
        val priority = cost + tapPen - KineticaConstants.BEAM_FREQ_WEIGHT * ln(fwMax)
        val k = Hyp(
            child, h.depth + 1, h, code, item, h.nx.copyOf(), h.open.copyOf(), h.n.copyOf(),
            h.last.copyOf(), h.lastCode.copyOf(), h.ideal.copyOf(), t, stream, cost, tapPen, keep, gates, hand, priority,
            handKeep, back,
        )
        k.dropped = h.dropped
        edit(k)
        return k
    }

    private fun maybeEmit(h: Hyp) {
        if (h.depth == 0 || !trie.isWord(h.node)) return
        val watched = watch?.let { h.node == it.last() } == true
        var gates = h.gates
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
                gates += closeGates(items[o], h.n[s], h.lastCode[s], h.ideal[s]) ?: run {
                    if (watched) emitWhy(h, "emit.close", "s$s it$o n=${h.n[s]}")
                    return
                }
            }
        }
        // The first run offered every reading that reads each tap.
        if (dropsTap && h.dropped == 0 && droppable > 0) return
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
        val source = if (tokens.size == 1) WordCandidate.Source.SWIPE else WordCandidate.Source.MERGED
        // Under [only] each reading is scored alone, so its own score is known, then offered on.
        val alone = if (only != null && watched) CandidateHeap(1) else null
        val outcome = p.scoreReading(
            g, alone ?: heap, prevWordId, prevWord, h.node, letters, h.depth,
            pieceCount, pieceSeg, pieceLetters, pieceFrom, pieceTo, h.tapPen, keep, source,
            unsaturated = KineticaConstants.BEAM_UNSATURATED,
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

        /** Whether the beam reads a buffer: a swipe in it and a pattern item for every token. */
        fun applies(tokens: List<InputToken>, items: List<Matcher>?): Boolean =
            items != null && items.size == tokens.size && tokens.any { it is SwipeToken } &&
                tokens.all { it is SwipeToken || it is TapToken }
    }
}
