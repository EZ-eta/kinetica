package com.kinetica.keyboard.engine

import java.io.BufferedReader
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The personal-word merge floor (KineticaConstants.PERSONAL_MERGE_MIN_COUNT):
 * one stray commit must not become a decodable trie word, because swipe
 * decode then re-commits it and every auto-commit re-learns it - the
 * self-reinforcing poisoning loop captured on a live trace ("sonore" and
 * "imposte" entering the es personal dictionary from language-swap misfires).
 * Rows de-reinforced to zero must not resurrect as freqByte-1 ghosts through
 * the max(1, ..) quantizer either ("quinndi").
 */
class PersonalMergeFloorTest {

    private fun reader(text: String): BufferedReader = BufferedReader(StringReader(text))

    private val base = "cuando\t931329\nsiempre\t424363\n"

    @Test
    fun singleStrayCommitDoesNotEnterTheTrie() {
        val extra = DictionaryLoader.userWordsForMerge(listOf("sonore" to 1))
        val dict = DictionaryLoader.load(reader(base), extra)
        assertFalse("count-1 word must stay out of the trie", dict.trie.contains("sonore"))
    }

    @Test
    fun zeroCountRowDoesNotResurrectAsGhostWord() {
        val extra = DictionaryLoader.userWordsForMerge(listOf("quinndi" to 0))
        val dict = DictionaryLoader.load(reader(base), extra)
        assertFalse("count-0 row must stay out of the trie", dict.trie.contains("quinndi"))
    }

    @Test
    fun floorCountMergesIntoTheTrie() {
        val extra = DictionaryLoader.userWordsForMerge(
            listOf("sonore" to KineticaConstants.PERSONAL_MERGE_MIN_COUNT),
        )
        val dict = DictionaryLoader.load(reader(base), extra)
        assertTrue("floor-count word must merge", dict.trie.contains("sonore"))
    }

    @Test
    fun mergedRowsKeepTheFrequencyScale() {
        val extra = DictionaryLoader.userWordsForMerge(listOf("dinero" to 6))
        assertEquals(
            listOf("dinero" to 6 * KineticaConstants.USER_FREQ_SCALE),
            extra,
        )
    }
}
