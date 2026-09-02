package com.kinetica.keyboard.engine

import kotlin.math.ln
import kotlin.math.max

/**
 * Dictionary trie stored as one flat IntArray, two ints per node:
 *
 *  int0: bits 0-4  letter code (0-25 a-z, 26 apostrophe)
 *        bit  5    isWord
 *        bits 6-13 freqByte (log-quantized unigram frequency, word nodes only)
 *        bits 14-21 maxDescendantFreq (max freqByte in this subtree, incl. self)
 *  int1: bits 0-23 first child node id
 *        bits 24-28 child count (0-27)
 *
 * Children of a node are contiguous and sorted by letter (guaranteed by BFS id
 * assignment at build time), so child lookup is a short linear scan over
 * adjacent memory. ~170k nodes for a 47k-word list = ~1.4 MB.
 *
 * A word's id is its terminal node id; the bigram table is keyed on these ids.
 */
class Trie private constructor(
    private val nodes: IntArray,
    val wordCount: Int,
) {
    val nodeCount: Int get() = nodes.size / 2
    val root: Int get() = 0

    fun letter(node: Int): Int = nodes[node * 2] and 0x1F
    fun isWord(node: Int): Boolean = (nodes[node * 2] shr 5 and 1) == 1
    fun frequency(node: Int): Int = nodes[node * 2] shr 6 and 0xFF
    fun maxDescendantFreq(node: Int): Int = nodes[node * 2] shr 14 and 0xFF
    fun firstChild(node: Int): Int = nodes[node * 2 + 1] and 0xFFFFFF
    fun childCount(node: Int): Int = nodes[node * 2 + 1] shr 24 and 0x1F

    /** Child of [node] with letter [code], or -1. */
    fun child(node: Int, code: Int): Int {
        val first = firstChild(node)
        val count = childCount(node)
        for (i in 0 until count) {
            val c = first + i
            val l = letter(c)
            if (l == code) return c
            if (l > code) return -1  // children are letter-sorted
        }
        return -1
    }

    /** Terminal node id for [word], or -1 if the word is absent. */
    fun nodeFor(word: CharSequence): Int {
        var node = root
        for (ch in word) {
            val code = Alphabet.codeOf(ch)
            if (code < 0) return -1
            node = child(node, code)
            if (node == -1) return -1
        }
        return if (isWord(node)) node else -1
    }

    fun contains(word: CharSequence): Boolean = nodeFor(word) != -1

    /**
     * The node [prefix] reaches, word or not, or -1 if the path runs out.
     *
     * [nodeFor] cannot answer this: it ends on `if (isWord(node)) node else -1`, so it
     * says no to every half-typed word. That distinction is the whole of the autospace
     * retraction gate - `autom` has to answer yes and `automaticop` no.
     */
    fun prefixNode(prefix: CharSequence): Int {
        var node = root
        for (ch in prefix) {
            val code = Alphabet.codeOf(ch)
            if (code < 0) return -1
            node = child(node, code)
            if (node == -1) return -1
        }
        return node
    }

    /** Approximate retained size for the memory-budget test. */
    fun sizeBytes(): Int = nodes.size * 4

    companion object {
        private const val NO_NODE = -1

        /**
         * Log-quantized frequency byte, shared with [DictionaryLoader] so
         * per-variant display forms score on the same scale as trie nodes.
         */
        fun freqByteFor(count: Int, maxCount: Long): Int =
            max(1, (255.0 * ln(1.0 + count) / ln(1.0 + maxCount)).toInt())

        /**
         * Builds from (word, rawCount) pairs. Raw counts are log-quantized to
         * bytes: prediction needs frequency *ranks* spanning orders of
         * magnitude, not absolute counts.
         */
        fun build(words: List<Pair<String, Int>>): Trie {
            val valid = ArrayList<Pair<IntArray, Int>>(words.size)
            var maxCount = 1L
            for ((w, c) in words) {
                if (w.isEmpty() || w.length > KineticaConstants.MAX_WORD_LEN) continue
                val codes = Alphabet.encode(w) ?: continue
                valid.add(codes to c)
                maxCount = max(maxCount, c.toLong())
            }
            // Lexicographic sort: sorted insertion keeps sibling chains letter-
            // sorted, which the packed layout requires.
            valid.sortWith { a, b -> compareCodes(a.first, b.first) }

            val maxCountFinal = maxCount

            // Temporary first-child/next-sibling trie in growable parallel arrays.
            // Sorted insertion keeps every sibling chain letter-sorted for free.
            var cap = max(64, valid.size * 3)
            var letter = IntArray(cap)
            var flags = IntArray(cap)      // freqByte shl 1 | isWord
            var firstChild = IntArray(cap)
            var lastChild = IntArray(cap)
            var nextSibling = IntArray(cap)
            var size = 0

            fun newNode(l: Int): Int {
                if (size == cap) {
                    cap *= 2
                    letter = letter.copyOf(cap)
                    flags = flags.copyOf(cap)
                    firstChild = firstChild.copyOf(cap)
                    lastChild = lastChild.copyOf(cap)
                    nextSibling = nextSibling.copyOf(cap)
                }
                letter[size] = l
                flags[size] = 0
                firstChild[size] = NO_NODE
                lastChild[size] = NO_NODE
                nextSibling[size] = NO_NODE
                return size++
            }

            val tmpRoot = newNode(0)
            val stack = IntArray(KineticaConstants.MAX_WORD_LEN + 1)
            stack[0] = tmpRoot
            var stackDepth = 0
            var prevCodes = IntArray(0)
            var uniqueWords = 0

            for ((codes, count) in valid) {
                val common = minOf(common(codes, prevCodes), stackDepth)
                stackDepth = common
                for (d in common until codes.size) {
                    val node = newNode(codes[d])
                    val parent = stack[stackDepth]
                    if (firstChild[parent] == NO_NODE) firstChild[parent] = node
                    else nextSibling[lastChild[parent]] = node
                    lastChild[parent] = node
                    stackDepth++
                    stack[stackDepth] = node
                }
                val terminal = stack[stackDepth]
                if (flags[terminal] and 1 == 0) uniqueWords++
                val freqByte = freqByteFor(count, maxCountFinal)
                flags[terminal] = (max(freqByte, flags[terminal] shr 1) shl 1) or 1
                prevCodes = codes
            }

            // maxDescendantFreq, post-order via explicit stack.
            val maxDesc = IntArray(size)
            run {
                val st = IntArray(size + 1)
                val visited = BooleanArray(size)
                var sp = 0
                st[sp++] = tmpRoot
                while (sp > 0) {
                    val n = st[sp - 1]
                    if (!visited[n]) {
                        visited[n] = true
                        var c = firstChild[n]
                        while (c != NO_NODE) {
                            st[sp++] = c
                            c = nextSibling[c]
                        }
                    } else {
                        sp--
                        var m = flags[n] shr 1
                        var c = firstChild[n]
                        while (c != NO_NODE) {
                            m = max(m, maxDesc[c])
                            c = nextSibling[c]
                        }
                        maxDesc[n] = m
                    }
                }
            }

            // BFS id assignment: children of each node receive consecutive ids.
            val finalId = IntArray(size) { NO_NODE }
            val order = IntArray(size)
            var head = 0
            var tail = 0
            order[tail] = tmpRoot
            finalId[tmpRoot] = tail++
            while (head < tail) {
                val n = order[head++]
                var c = firstChild[n]
                while (c != NO_NODE) {
                    order[tail] = c
                    finalId[c] = tail++
                    c = nextSibling[c]
                }
            }

            val packed = IntArray(size * 2)
            for (tmp in 0 until size) {
                val id = finalId[tmp]
                var count = 0
                var firstId = 0
                var c = firstChild[tmp]
                if (c != NO_NODE) {
                    firstId = finalId[c]
                    while (c != NO_NODE) {
                        count++
                        c = nextSibling[c]
                    }
                }
                val isWord = flags[tmp] and 1
                val freq = flags[tmp] shr 1
                packed[id * 2] = (letter[tmp] and 0x1F) or
                    (isWord shl 5) or
                    ((freq and 0xFF) shl 6) or
                    ((maxDesc[tmp] and 0xFF) shl 14)
                packed[id * 2 + 1] = (firstId and 0xFFFFFF) or ((count and 0x1F) shl 24)
            }
            return Trie(packed, uniqueWords)
        }

        private fun common(a: IntArray, b: IntArray): Int {
            val n = minOf(a.size, b.size)
            var i = 0
            while (i < n && a[i] == b[i]) i++
            return i
        }

        private fun compareCodes(a: IntArray, b: IntArray): Int {
            val n = minOf(a.size, b.size)
            for (i in 0 until n) {
                if (a[i] != b[i]) return a[i] - b[i]
            }
            return a.size - b.size
        }
    }
}
