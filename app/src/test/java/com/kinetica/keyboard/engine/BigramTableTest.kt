package com.kinetica.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class BigramTableTest {

    @Test
    fun duplicatePairRowOrderDoesNotChangeBoosts() {
        val entries = listOf(
            Triple(1, 2, 100L),
            Triple(1, 2, 1L),
            Triple(1, 3, 50L),
        )
        val forward = BigramTable.build(entries)
        val reversed = BigramTable.build(entries.reversed())
        for (next in listOf(2, 3)) {
            assertEquals(forward.multiplier(1, next), reversed.multiplier(1, next), 0f)
        }
    }

    @Test
    fun duplicateCountsAreSummedBeforeContextNormalization() {
        val split = BigramTable.build(
            listOf(
                Triple(1, 2, 60L),
                Triple(1, 3, 100L),
                Triple(1, 2, 50L),
                Triple(2, 3, 30L),
                Triple(2, 4, 50L),
                Triple(2, 4, 20L),
            ),
        )
        val combined = BigramTable.build(
            listOf(
                Triple(1, 2, 110L),
                Triple(1, 3, 100L),
                Triple(2, 3, 30L),
                Triple(2, 4, 70L),
            ),
        )
        assertEquals(4, split.size)
        for ((prev, next) in listOf(1 to 2, 1 to 3, 2 to 3, 2 to 4)) {
            assertEquals(combined.multiplier(prev, next), split.multiplier(prev, next), 0f)
        }
    }

    @Test
    fun accentedEndpointsMergeThroughDictionaryLoader() {
        val trie = DictionaryLoader.loadWordlist(
            "e\t100\nè\t90\nsi\t80\nsì\t70\nnon\t60\n".reader().buffered(),
        )
        val split = DictionaryLoader.loadBigrams(
            "e\tsi\t60\nè\tsì\t50\ne\tnon\t100\n".reader().buffered(), trie,
        )
        val combined = DictionaryLoader.loadBigrams(
            "e\tsi\t110\ne\tnon\t100\n".reader().buffered(), trie,
        )
        val prev = trie.nodeFor("e")
        assertEquals(2, split.size)
        for (next in listOf("si", "non")) {
            val id = trie.nodeFor(next)
            assertEquals(combined.multiplier(prev, id), split.multiplier(prev, id), 0f)
        }
    }

    @Test
    fun mergedCountsKeepLongRange() {
        val split = BigramTable.build(
            listOf(
                Triple(1, 2, 3_000_000_000L),
                Triple(1, 2, 3_000_000_000L),
                Triple(1, 3, 5_000_000_000L),
            ),
        )
        val combined = BigramTable.build(
            listOf(Triple(1, 2, 6_000_000_000L), Triple(1, 3, 5_000_000_000L)),
        )
        for (next in listOf(2, 3)) {
            assertEquals(combined.multiplier(1, next), split.multiplier(1, next), 0f)
        }
    }

    @Test
    fun uniquePairsKeepTheirQuantizedBoosts() {
        val table = BigramTable.build(
            listOf(Triple(1, 3, 15L), Triple(2, 3, 3L), Triple(1, 2, 3L)),
        )
        assertEquals(3, table.size)
        // ln(1 + 3) / ln(1 + 15) = 1/2, quantized down to byte 127.
        assertEquals(
            1f + KineticaConstants.BIGRAM_BOOST_MAX * 127 / 255f,
            table.multiplier(1, 2), 0f,
        )
        for (prev in listOf(1, 2)) {
            assertEquals(1f + KineticaConstants.BIGRAM_BOOST_MAX, table.multiplier(prev, 3), 0f)
        }
        assertEquals(1f, table.multiplier(2, 2), 0f)
    }
}
