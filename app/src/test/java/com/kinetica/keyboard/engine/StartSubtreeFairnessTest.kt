package com.kinetica.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for start-subtree starvation, found on "siempre": emit attempts count against one
 * global budget (MAX_CANDIDATES) while children are explored frequency-first, so a giant
 * start-letter subtree beside the path start (es: d-, holding "de") could spend the whole
 * budget before the intended word's subtree was visited. The budget is sliced across admissible
 * start subtrees so the cap bites within a subtree, never across one.
 *
 * Synthetic so the lock is dictionary-independent: a d- subtree stuffed with hundreds of
 * path-admissible fillers, all ahead of the s- subtree in frequency order, must not starve the
 * exact-overlay target word.
 */
class StartSubtreeFairnessTest {

    @Test
    fun dominantNeighborSubtreeDoesNotStarveTheTargetWord() {
        val g = TestData.qwertyGeometry()
        // Fillers: d + four letters near the s-i-e-m-p-r-e path + closing e
        // (start d is within R_ENDPOINT of the s start; the middles are path
        // passes; the final e closes at the path end). Enough order-valid
        // combinations close and emit to exhaust the global budget before
        // the s- subtree is visited.
        val near = "iemproukn"
        val fillers = ArrayList<Pair<String, Int>>()
        var f = 2000
        for (a in near) for (b in near) for (c in near) for (d in near) {
            fillers.add("d$a$b$c${d}e" to f)
            f++
        }
        val words = fillers + listOf("siempre" to 1000)
        assertTrue("need more fillers than the emit budget",
            fillers.size > KineticaConstants.MAX_CANDIDATES)
        val predictor = WordPredictor(Trie.build(words), BigramTable.EMPTY, g)
        val result = predictor.decode(
            listOf(TestData.swipe("siempre", g, 0, 700)), emptyList(),
        )
        assertTrue("decode came back empty", result.isNotEmpty())
        assertEquals(
            "exact-overlay target lost to ${result.take(3).map { it.word }}",
            "siempre", result[0].word,
        )
    }
}
