package com.kinetica.keyboard.ime

import com.kinetica.keyboard.ui.BarAdjust.BlockSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Deleting a blocked word from the text (#27): only the word at the cursor, proved there, and
 * held where it is the word written. A wrong delete costs more than a wrong block.
 */
class BlockDeleteTest {

    private fun decide(
        source: BlockSource,
        word: String = "teh",
        tentative: String? = null,
        otherPending: Boolean = false,
        selected: Boolean = false,
        letterAfter: Boolean = false,
        committed: String? = "teh",
        span: Int = 4,
        tailIsSpace: Boolean = true,
    ) = blockDelete(source, word, tentative, otherPending, selected, letterAfter, committed, span, tailIsSpace)

    @Test
    fun theWordJustWrittenGoesFromItsChipOrItsColumn() {
        assertEquals(BlockDelete.COMMIT, decide(BlockSource.CHIP))
        assertEquals(BlockDelete.COMMIT, decide(BlockSource.NEWEST_RECENT))
        // Shown capitalized at a sentence start, written the same.
        assertEquals(BlockDelete.COMMIT, decide(BlockSource.CHIP, word = "Teh", committed = "Teh"))
    }

    @Test
    fun theWordInProgressGoesFromItsLiveZone() {
        assertEquals(BlockDelete.TENTATIVE, decide(BlockSource.LIVE, word = "anb", tentative = "anb", committed = null, span = -1))
        // Another candidate of the same gesture is no word in the text.
        assertEquals(BlockDelete.NONE, decide(BlockSource.LIVE, word = "and", tentative = "anb", committed = null, span = -1))
    }

    @Test
    fun anythingNotProvedAtTheCursorStays() {
        assertEquals(BlockDelete.NONE, decide(BlockSource.OTHER))
        // The editor no longer ends in it.
        assertEquals(BlockDelete.NONE, decide(BlockSource.CHIP, span = -1))
        // Another word was committed since.
        assertEquals(BlockDelete.NONE, decide(BlockSource.CHIP, committed = "the"))
        // Parked inside a word, a selection up, or a new word started.
        assertEquals(BlockDelete.NONE, decide(BlockSource.CHIP, letterAfter = true))
        assertEquals(BlockDelete.NONE, decide(BlockSource.CHIP, selected = true))
        assertEquals(BlockDelete.NONE, decide(BlockSource.NEWEST_RECENT, tentative = "wo"))
        assertEquals(BlockDelete.NONE, decide(BlockSource.CHIP, otherPending = true))
        // A period or a newline typed after it is the user's, and the delete would take it.
        assertEquals(BlockDelete.NONE, decide(BlockSource.CHIP, tailIsSpace = false))
    }
}
