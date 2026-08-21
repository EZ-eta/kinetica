package com.kinetica.keyboard.engine

import java.io.BufferedReader
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Blocking a word removes it from the dictionary itself.
 *
 * The distinction this rests on: sliding a suggestion down adjusts the personal
 * count and `UserWordDao` clamps that at zero, which leaves the corpus frequency
 * underneath untouched - so a bundled word can be pushed off the bar but never
 * out of the trie. A user reported exactly that, unable to lose "seok" and "heo"
 * however often they de-prioritised them; both are real entries in the bundled
 * English list, at frequencies 1217 and 634, because an OpenSubtitles corpus is
 * full of transliterated names.
 */
class BlockedWordsTest {

    private fun reader(vararg lines: String): BufferedReader =
        BufferedReader(StringReader(lines.joinToString("\n")))

    private fun corpus(): BufferedReader = reader(
        "the\t12000",
        "seok\t1217",
        "heo\t634",
        "her\t9000",
        "hero\t3000",
        "these\t2500",
    )

    @Test
    fun aBlockedWordIsNotInTheTrieAtAll() {
        val open = DictionaryLoader.load(corpus()).trie
        assertTrue("precondition: the corpus really carries it", open.contains("seok"))

        val trie = DictionaryLoader.load(corpus(), blocked = setOf("seok")).trie
        assertFalse(trie.contains("seok"))
        // And it is gone rather than merely down-weighted: no node, so nothing
        // to decode, complete or suggest.
        assertEquals(-1, trie.nodeFor("seok"))
    }

    @Test
    fun everythingElseSurvivesUntouched() {
        val trie = DictionaryLoader.load(corpus(), blocked = setOf("seok", "heo")).trie
        for (w in listOf("the", "her", "hero", "these")) {
            assertTrue("$w should be untouched", trie.contains(w))
        }
        assertFalse(trie.contains("seok"))
        assertFalse(trie.contains("heo"))
    }

    @Test
    fun blockingAWordThatIsAPrefixLeavesTheLongerWordAlone() {
        // "her" is a prefix of "hero"; removing the short word must not take the
        // subtree with it.
        val trie = DictionaryLoader.load(corpus(), blocked = setOf("her")).trie
        assertFalse(trie.contains("her"))
        assertTrue(trie.contains("hero"))
    }

    @Test
    fun aBlockedWordCannotReturnThroughThePersonalMerge() {
        // The reason the filter applies to extraWords as well. A user who blocks
        // a word they have already typed twice would otherwise see it merged
        // straight back in at USER_FREQ_SCALE, which is worse than before.
        val learned = DictionaryLoader.userWordsForMerge(listOf("seok" to 5))
        assertTrue("precondition: it would be merged", learned.isNotEmpty())

        val trie = DictionaryLoader.load(corpus(), learned, blocked = setOf("seok")).trie
        assertFalse(trie.contains("seok"))
    }

    @Test
    fun blockingIsCaseInsensitiveOnTheStoredSpelling() {
        // The IME lower-cases what it reads from the table; the loader lower-cases
        // the corpus word before testing it, so a capitalised corpus entry is
        // still caught.
        val trie = DictionaryLoader.load(
            reader("Seok\t1217", "the\t12000"),
            blocked = setOf("seok"),
        ).trie
        assertFalse(trie.contains("seok"))
        assertTrue(trie.contains("the"))
    }

    @Test
    fun blockingOneAccentedVariantLeavesItsSiblingsAlone() {
        // Variants fold onto one node, so this is the case where an over-broad
        // filter would take a real word out with the unwanted one.
        val src = reader("perche\t50", "perché\t9000", "per\t4000")
        val open = DictionaryLoader.load(src).trie
        assertTrue(open.contains("perche"))

        val out = DictionaryLoader.load(
            reader("perche\t50", "perché\t9000", "per\t4000"),
            blocked = setOf("perche"),
        )
        // The folded node survives because the accented spelling is still there,
        // and it is the surviving variant that the forms table now offers.
        assertTrue(out.trie.contains("perche"))
        val node = out.trie.nodeFor("perche")
        val displays = out.forms[node]?.map { it.display } ?: listOf("perche")
        assertFalse("the blocked spelling is still on offer", displays.contains("perche"))
        assertTrue(displays.contains("perché"))
        assertTrue(out.trie.contains("per"))
    }

    @Test
    fun anEmptyBlockListChangesNothing() {
        val plain = DictionaryLoader.load(corpus()).trie
        val empty = DictionaryLoader.load(corpus(), blocked = emptySet()).trie
        assertEquals(plain.wordCount, empty.wordCount)
        assertTrue(empty.contains("seok"))
    }

    @Test
    fun blockingEveryWordDoesNotCrashTheLoad() {
        // A degenerate case someone can reach through the settings screen.
        val out = DictionaryLoader.load(
            reader("the\t1"),
            blocked = setOf("the"),
        )
        assertEquals(0, out.trie.wordCount)
    }
}
