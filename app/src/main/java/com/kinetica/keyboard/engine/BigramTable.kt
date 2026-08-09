package com.kinetica.keyboard.engine

import kotlin.math.ln
import kotlin.math.max

/**
 * Bigram context boosts keyed on trie word ids: sorted packed longs
 * (prevId << 32 | nextId) with one quantized boost byte each. Boost bytes are
 * normalized per previous word (an approximation of P(next|prev)), so the
 * multiplier reorders near-ties without overriding clear gesture geometry.
 */
class BigramTable private constructor(
    private val keys: LongArray,
    private val boosts: ByteArray,
) {
    val size: Int get() = keys.size

    fun multiplier(prevWordId: Int, nextWordId: Int): Float {
        if (prevWordId < 0 || nextWordId < 0 || keys.isEmpty()) return 1f
        val key = pack(prevWordId, nextWordId)
        val i = java.util.Arrays.binarySearch(keys, key)
        if (i < 0) return 1f
        return 1f + KineticaConstants.BIGRAM_BOOST_MAX * (boosts[i].toInt() and 0xFF) / 255f
    }

    fun sizeBytes(): Int = keys.size * 8 + boosts.size

    companion object {
        val EMPTY = BigramTable(LongArray(0), ByteArray(0))

        private fun pack(prev: Int, next: Int): Long =
            (prev.toLong() shl 32) or (next.toLong() and 0xFFFFFFFFL)

        /** Entries are (prevWordId, nextWordId, rawCount). */
        fun build(entries: List<Triple<Int, Int, Long>>): BigramTable {
            if (entries.isEmpty()) return EMPTY
            val keys = LongArray(entries.size)
            val counts = LongArray(entries.size)
            val idx = entries.indices.sortedBy { pack(entries[it].first, entries[it].second) }
            for (i in idx.indices) {
                val e = entries[idx[i]]
                keys[i] = pack(e.first, e.second)
                counts[i] = e.third
            }
            // Same-prev entries are contiguous after sorting; normalize each group
            // against its own maximum so every context uses the full byte range.
            val boosts = ByteArray(entries.size)
            var start = 0
            while (start < keys.size) {
                val prev = keys[start] ushr 32
                var end = start
                var maxCount = 1L
                while (end < keys.size && (keys[end] ushr 32) == prev) {
                    maxCount = max(maxCount, counts[end])
                    end++
                }
                val logMax = ln(1.0 + maxCount)
                for (i in start until end) {
                    val b = (255.0 * ln(1.0 + counts[i]) / logMax).toInt().coerceIn(1, 255)
                    boosts[i] = b.toByte()
                }
                start = end
            }
            return BigramTable(keys, boosts)
        }
    }
}
