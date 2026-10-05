package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.KineticaConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When learning a word has to rebuild the trie.
 *
 * A learned word reaches the ranking map at once but the trie only at the next dictionary load,
 * so without a reload a word the dictionary lacks stays undecodable for the session.
 * On one capture an eleven-letter word was committed three times and
 * first appeared as a candidate 4 277 trace lines later, after an unrelated reload.
 */
class UserDictReloadTest {

    private val floor = KineticaConstants.PERSONAL_MERGE_MIN_COUNT

    @Test
    fun aWordReachingTheMergeFloorAsksForAReload() {
        assertTrue(userDictNeedsReload(floor - 1, floor, trieHasWord = false))
    }

    @Test
    fun aWordBelowTheFloorAsksForNothing() {
        // The floor keeps a misdecode out of the trie: a word's first commit changes nothing.
        assertFalse(userDictNeedsReload(0, floor - 1, trieHasWord = false))
    }

    @Test
    fun aWordTheTrieAlreadyHoldsAsksForNothing() {
        // A bundled word being learned is already searchable; only its weight moved.
        assertFalse(userDictNeedsReload(floor - 1, floor, trieHasWord = true))
    }

    @Test
    fun aWordReinforcedPastTheFloorAsksForNothing() {
        // Only the crossing arms a reload; otherwise a word the reload cannot admit (blocked,
        // or past USER_DICT_LIMIT) would ask again on every commit.
        assertFalse(userDictNeedsReload(floor, floor + 1, trieHasWord = false))
        assertFalse(userDictNeedsReload(floor + 8, floor + 9, trieHasWord = false))
    }

    @Test
    fun aReinforceSlideCrossingTheFloorInOneStepStillAsks() {
        // Long-press reinforce adds 5 or 10 at a time, so the crossing can be a jump.
        assertTrue(userDictNeedsReload(0, floor + 9, trieHasWord = false))
    }

    @Test
    fun deReinforcingAsksForNothing() {
        assertFalse(userDictNeedsReload(floor + 1, floor - 1, trieHasWord = false))
    }
}
