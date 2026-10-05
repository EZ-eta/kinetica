package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.TapToken

/**
 * How far back in time a word must be allowed to hand over between thumbs to be spelled
 * from the contacts the two thumbs made.
 *
 * A word is read with one cursor per thumb: each thumb's contacts are consumed in their own
 * order, the other thumb is free, and a hand-over from one thumb to the other may step back
 * in time. The answer for a row is the smallest such step-back, over every reading, that
 * spells the word. Zero means the contacts already spell it in time order; infinity means
 * no two-cursor reading exists at any allowance.
 *
 * Contact level only: a letter is matched to a key the thumb was measurably on, never to a
 * pass the path came near. That undercounts what the decoder can reach, the price of needing
 * no geometry: every number here is a reachability bound on device timings, not a decode.
 */
object HandoverDp {

    /** One letter's evidence on one thumb: the key and the time span it was held. */
    data class Contact(val letter: Char, val tEnter: Long, val tExit: Long)

    /** Which instant of a contact stands for its letter. */
    enum class Mode { ENTRY, EXIT, MID }

    fun timeOf(c: Contact, mode: Mode): Double = when (mode) {
        Mode.ENTRY -> c.tEnter.toDouble()
        Mode.EXIT -> c.tExit.toDouble()
        Mode.MID -> (c.tEnter + c.tExit) / 2.0
    }

    /**
     * Each thumb's contacts in time order. A tap is one contact over its replayed span; a
     * swipe contributes every key contact it carries.
     */
    fun streams(tokens: List<InputToken>): Map<StreamId, List<Contact>> {
        val out = HashMap<StreamId, MutableList<Contact>>()
        for (t in tokens) {
            val list = out.getOrPut(t.streamId) { ArrayList() }
            when (t) {
                is TapToken -> letterOf(t.code)?.let { list.add(Contact(it, t.tStart, t.tEnd)) }
                is SwipeToken -> for (c in t.keyContacts) {
                    letterOf(c.code)?.let { list.add(Contact(it, c.tEnter, c.tExit)) }
                }
            }
        }
        for (l in out.values) l.sortBy { it.tEnter }
        return out
    }

    /**
     * The smallest step-back that spells [word], allowing up to [maxMisses] letters no thumb
     * touched (they take no time and cannot anchor a hand-over), or infinity.
     */
    fun minSlack(word: String, streams: Map<StreamId, List<Contact>>, mode: Mode, maxMisses: Int = 0): Double {
        val lists = streams.values.filter { it.isNotEmpty() }
        if (word.isEmpty()) return 0.0
        val memo = HashMap<List<Any>, Double>()

        fun go(i: Int, cursor: IntArray, lastSide: Int, lastT: Double, misses: Int): Double {
            if (i == word.length) return 0.0
            val key = listOf(i, cursor.toList(), lastSide, lastT, misses)
            memo[key]?.let { return it }
            var best = Double.POSITIVE_INFINITY
            if (misses < maxMisses) best = go(i + 1, cursor, lastSide, lastT, misses + 1)
            for ((s, list) in lists.withIndex()) {
                for (j in cursor[s] until list.size) {
                    val c = list[j]
                    if (c.letter != word[i]) continue
                    val t = timeOf(c, mode)
                    val back = if (lastSide >= 0 && lastSide != s) maxOf(0.0, lastT - t) else 0.0
                    if (back >= best) continue
                    val next = cursor.copyOf()
                    next[s] = j + 1
                    best = minOf(best, maxOf(back, go(i + 1, next, s, t, misses)))
                }
            }
            memo[key] = best
            return best
        }
        return go(0, IntArray(lists.size), -1, Double.NEGATIVE_INFINITY, 0)
    }

    /**
     * The earliest-match reading, kept for comparison: each letter takes the first matching
     * contact on each thumb, never a later one. It misses readings the exact search finds, so
     * it is not the answer.
     */
    fun greedySlack(word: String, streams: Map<StreamId, List<Contact>>, mode: Mode): Double {
        val lists = streams.values.filter { it.isNotEmpty() }
        fun go(i: Int, cursor: IntArray, lastSide: Int, lastT: Double): Double {
            if (i == word.length) return 0.0
            var best = Double.POSITIVE_INFINITY
            for ((s, list) in lists.withIndex()) {
                val j = (cursor[s] until list.size).firstOrNull { list[it].letter == word[i] } ?: continue
                val t = timeOf(list[j], mode)
                val back = if (lastSide >= 0 && lastSide != s) maxOf(0.0, lastT - t) else 0.0
                val next = cursor.copyOf()
                next[s] = j + 1
                best = minOf(best, maxOf(back, go(i + 1, next, s, t)))
            }
            return best
        }
        return go(0, IntArray(lists.size), -1, Double.NEGATIVE_INFINITY)
    }

    /** True when every contact of both thumbs, merged in time, holds [word] as a subsequence. */
    fun spelledInTimeOrder(word: String, streams: Map<StreamId, List<Contact>>, mode: Mode): Boolean {
        val merged = streams.values.flatten().sortedBy { timeOf(it, mode) }
        var i = 0
        for (c in merged) if (i < word.length && c.letter == word[i]) i++
        return i == word.length
    }

    /** Folded, lower-cased, doubled letters collapsed: one contact serves a double letter. */
    fun normalise(word: String): String {
        val folded = AccentFolder.fold(word)
        val sb = StringBuilder(folded.length)
        for (ch in folded) if (sb.isEmpty() || sb.last() != ch) sb.append(ch)
        return sb.toString()
    }

    private fun letterOf(code: Int): Char? =
        if (code in 0 until Alphabet.LETTERS) ('a' + code) else null
}
