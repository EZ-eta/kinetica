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

    private fun why(h: Hyp, child: Int, reason: String) {
        val w = watch ?: return
        if (h.depth < w.size && w[h.depth] == child) watchWhy.merge(reason, 1, Int::plus)
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
        val priority: Float,
    )

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
        h.last[0], h.last[1], h.lastCode[0], h.lastCode[1], h.lastStream,
    )

    fun run() {
        val root = Hyp(
            trie.root, 0, null, -1, -1, IntArray(2), intArrayOf(-1, -1), IntArray(2),
            IntArray(2), intArrayOf(-1, -1), FloatArray(2), Long.MIN_VALUE, -1, 0f, 0f, 1f, 0, 0f,
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
            frontier = if (sorted.size > KineticaConstants.BEAM_WIDTH) sorted.subList(0, KineticaConstants.BEAM_WIDTH) else sorted
            val w = watch
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
                if (attempts >= KineticaConstants.BEAM_MAX_ATTEMPTS) break
                maybeEmit(h)
            }
            if (attempts >= KineticaConstants.BEAM_MAX_ATTEMPTS) break
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
        for (i in 0 until count) {
            val child = first + i
            val code = trie.letter(child)
            if (code == trie.alphabet.apostrophe) {
                if (h.depth > 0 && h.depth + 1 < KineticaConstants.MAX_WORD_LEN) {
                    out.add(next(h, child, code, -1, h.cost, h.tapPen, h.keep, h.gates, h.lastT, h.lastStream) { })
                }
                continue
            }
            if (!g.hasKey(code)) continue
            if (trie.maxWordLen(child) < shortest) {
                why(h, child, "short")
                continue
            }
            for (s in 0..1) {
                continueOpen(h, child, code, s, out)
                openNext(h, child, code, s, out)
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
        return need
    }

    /** Hand-over rule: a letter on the other thumb may step back HANDOVER_ALLOWANCE_MS at most. */
    private fun timeOk(h: Hyp, s: Int, t: Long): Boolean =
        h.lastStream < 0 || h.lastStream == s || t >= h.lastT - KineticaConstants.HANDOVER_ALLOWANCE_MS

    private fun continueOpen(h: Hyp, child: Int, code: Int, s: Int, out: MutableList<Hyp>) {
        val o = h.open[s]
        if (o < 0) return
        val m = items[o]
        if (m is Matcher.Segment) {
            if (h.n[s] >= m.maxLetters) return why(h, child, "cont.max")
            val pass = m.passAtOrAfter(code, h.last[s] - KineticaConstants.MONOTONE_SLACK)
            if (pass < 0) return why(h, child, "cont.nopass")
            val t = m.letterTime(code, pass)
            if (!timeOk(h, s, t)) return why(h, child, "cont.time")
            val c = h.cost + passCost(m, code, pass)
            val keep = h.keep * contactKeep(m, code)
            out.add(next(h, child, code, o, c, h.tapPen, keep, h.gates, t, s) { st ->
                st.n[s] = h.n[s] + 1
                st.last[s] = maxOf(h.last[s], pass)
                st.lastCode[s] = code
                if (code != h.lastCode[s]) st.ideal[s] = h.ideal[s] + g.keyDist(h.lastCode[s], code)
            })
        } else if (m is Matcher.Anchor) {
            // One tap may stand for a doubled letter.
            if (h.n[s] != 1 || code != h.lastCode[s]) return
            out.add(
                next(h, child, code, o, h.cost, h.tapPen + KineticaConstants.DOUBLE_TAP_PEN_KW, h.keep, h.gates, m.t, s) { st ->
                    st.n[s] = 2
                },
            )
        }
    }

    private fun openNext(h: Hyp, child: Int, code: Int, s: Int, out: MutableList<Hyp>) {
        val list = streams[s]
        if (h.nx[s] >= list.size) return why(h, child, "open.none")
        var closeGates = 0
        val o = h.open[s]
        if (o >= 0) closeGates = closeGates(items[o], h.n[s], h.lastCode[s], h.ideal[s]) ?: return why(h, child, "open.close")
        val nextItem = list[h.nx[s]]
        val m = items[nextItem]
        if (m is Matcher.Anchor) {
            val pen = if (code == m.code) {
                0f
            } else {
                val d = g.distToCenter(m.x, m.y, code)
                if (d > KineticaConstants.FUZZY_TAP_RADIUS_KW) return why(h, child, "open.tapfar")
                KineticaConstants.FUZZY_TAP_LAMBDA * d
            }
            if (!timeOk(h, s, m.t)) return why(h, child, "open.taptime")
            val c = h.cost + pen + closeGates * KineticaConstants.BEAM_GATE_COST
            out.add(next(h, child, code, nextItem, c, h.tapPen + pen, h.keep, h.gates + closeGates, m.t, s) { st ->
                st.nx[s] = h.nx[s] + 1
                st.open[s] = nextItem
                st.n[s] = 1
                st.last[s] = 0
                st.lastCode[s] = code
            })
        } else if (m is Matcher.Segment) {
            val pass = m.passAtOrAfter(code, -KineticaConstants.MONOTONE_SLACK)
            if (pass < 0) return why(h, child, "open.nopass")
            val t = m.letterTime(code, pass)
            if (!timeOk(h, s, t)) return why(h, child, "open.time")
            val startGate = if (m.softStart || m.isStart(code)) 0 else 1
            val gates = closeGates + startGate
            val c = h.cost + passCost(m, code, pass) + gates * KineticaConstants.BEAM_GATE_COST
            val keep = h.keep * contactKeep(m, code)
            out.add(next(h, child, code, nextItem, c, h.tapPen, keep, h.gates + gates, t, s) { st ->
                st.nx[s] = h.nx[s] + 1
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
        t: Long,
        stream: Int,
        edit: (Hyp) -> Unit,
    ): Hyp {
        // Cheap and common first: the letters' distances to the path, plus a pull toward the
        // subtrees holding frequent words, so a deep common word is not crowded out.
        val fwMax = KineticaConstants.FREQ_WEIGHT_FLOOR +
            (1f - KineticaConstants.FREQ_WEIGHT_FLOOR) * trie.maxDescendantFreq(child) / 255f
        val priority = cost + tapPen - KineticaConstants.BEAM_FREQ_WEIGHT * ln(fwMax)
        val k = Hyp(
            child, h.depth + 1, h, code, item, h.nx.copyOf(), h.open.copyOf(), h.n.copyOf(),
            h.last.copyOf(), h.lastCode.copyOf(), h.ideal.copyOf(), t, stream, cost, tapPen, keep, gates, priority,
        )
        edit(k)
        return k
    }

    private fun maybeEmit(h: Hyp) {
        if (h.depth == 0 || !trie.isWord(h.node)) return
        val watched = watch?.let { h.node == it.last() } == true
        var gates = h.gates
        for (s in 0..1) {
            if (h.nx[s] < streams[s].size) {
                if (watched) watchWhy.merge("emit.itemsLeft", 1, Int::plus)
                return
            }
            val o = h.open[s]
            if (o >= 0) {
                gates += closeGates(items[o], h.n[s], h.lastCode[s], h.ideal[s]) ?: run {
                    if (watched) watchWhy.merge("emit.close", 1, Int::plus)
                    return
                }
            }
        }
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
                if (watched) watchWhy.merge("emit.emptyPiece", 1, Int::plus)
                return
            }
            val arr = IntArray(own)
            var k = 0
            for (j in 0 until h.depth) if (owner[j] == item) arr[k++] = letters[j]
            pieceSeg[pi] = items[item] as Matcher.Segment
            pieceLetters[pi] = arr
            pieceTo[pi] = own
        }
        var keep = h.keep
        repeat(gates) { keep *= KineticaConstants.BEAM_GATE_KEEP }
        val source = if (tokens.size == 1) WordCandidate.Source.SWIPE else WordCandidate.Source.MERGED
        val outcome = p.scoreReading(
            g, heap, prevWordId, prevWord, h.node, letters, h.depth,
            pieceCount, pieceSeg, pieceLetters, pieceFrom, pieceTo, h.tapPen, keep, source,
            unsaturated = KineticaConstants.BEAM_UNSATURATED,
        )
        if (outcome == WordPredictor.Scored.OFFERED) offered++
        val w = watch
        if (w != null && h.node == w.last() && (watchStage.isEmpty() || watchStage.startsWith("emit"))) {
            if (watchStage != "emit:OFFERED") watchStage = "emit:$outcome"
        }
    }

    companion object {
        /** Whether the beam reads a buffer: a swipe in it and a pattern item for every token. */
        fun applies(tokens: List<InputToken>, items: List<Matcher>?): Boolean =
            items != null && items.size == tokens.size && tokens.any { it is SwipeToken } &&
                tokens.all { it is SwipeToken || it is TapToken }
    }
}
