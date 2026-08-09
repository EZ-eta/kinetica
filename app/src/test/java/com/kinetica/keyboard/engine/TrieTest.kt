package com.kinetica.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrieTest {

    private val trie = TestData.smallDictionary()

    @Test
    fun containsAllInsertedWords() {
        for (w in listOf("the", "them", "something", "a", "don't", "so")) {
            assertTrue("missing $w", trie.contains(w))
        }
    }

    @Test
    fun rejectsNonWords() {
        assertFalse(trie.contains("th"))       // prefix but not a word
        assertFalse(trie.contains("xyzzy"))
        assertFalse(trie.contains(""))
    }

    @Test
    fun nodeForIsStableWordId() {
        val id1 = trie.nodeFor("the")
        val id2 = trie.nodeFor("the")
        assertTrue(id1 > 0)
        assertEquals(id1, id2)
        assertEquals(-1, trie.nodeFor("notaword"))
    }

    @Test
    fun frequencyQuantizationPreservesOrder() {
        val fThe = trie.frequency(trie.nodeFor("the"))
        val fSomething = trie.frequency(trie.nodeFor("something"))
        val fSmoothing = trie.frequency(trie.nodeFor("smoothing"))
        assertTrue(fThe > fSomething)
        assertTrue(fSomething > fSmoothing)
        assertTrue(fSmoothing >= 1)
    }

    @Test
    fun childrenAreLetterSorted() {
        var node = trie.root
        val count = trie.childCount(node)
        assertTrue(count > 0)
        val first = trie.firstChild(node)
        for (i in 1 until count) {
            assertTrue(trie.letter(first + i) > trie.letter(first + i - 1))
        }
    }

    @Test
    fun maxDescendantFreqDominatesSubtree() {
        val t = trie.child(trie.root, 't' - 'a')
        assertTrue(t != -1)
        // "the" is the most frequent word in the dictionary and lives under t.
        assertEquals(trie.frequency(trie.nodeFor("a")), trie.maxDescendantFreq(trie.root))
        assertTrue(trie.maxDescendantFreq(t) >= trie.frequency(trie.nodeFor("the")))
    }

    @Test
    fun apostropheWordsRoundTrip() {
        assertTrue(trie.contains("don't"))
        assertFalse(trie.contains("don'"))
        assertTrue(trie.contains("dont"))
    }

    @Test
    fun wordCountMatches() {
        assertEquals(25, trie.wordCount)
    }
}
