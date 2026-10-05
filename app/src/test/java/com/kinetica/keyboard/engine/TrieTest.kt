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

    @Test
    fun everyNodeKnowsItsLongestWord() {
        // The bound descend prunes on: a subtree too short for the input left.
        val t = Trie.build(listOf("a" to 5, "an" to 4, "and" to 3, "ant" to 2, "b" to 1, "bee" to 1, "don't" to 1))
        assertEquals(5, t.maxWordLen(t.root))
        val a = t.child(t.root, Alphabet.codeOf('a'))
        assertEquals(3, t.maxWordLen(a))
        assertEquals(3, t.maxWordLen(t.child(a, Alphabet.codeOf('n'))))
        val b = t.child(t.root, Alphabet.codeOf('b'))
        assertEquals(3, t.maxWordLen(b))
        assertEquals(3, t.maxWordLen(t.nodeFor("bee")))
        // An apostrophe is a letter position like any other.
        assertEquals(5, t.maxWordLen(t.child(t.root, Alphabet.codeOf('d'))))
    }

    @Test
    fun theLongestWordAllowedFitsTheBits() {
        val w = "a".repeat(KineticaConstants.MAX_WORD_LEN)
        val t = Trie.build(listOf(w to 1))
        assertEquals(KineticaConstants.MAX_WORD_LEN, t.maxWordLen(t.root))
        // The fields beside it are untouched.
        assertTrue(t.contains(w))
        assertEquals(t.frequency(t.nodeFor(w)), t.maxDescendantFreq(t.root))
    }

    @Test
    fun everyWordIsSpelledBackFromItsNode() {
        // Next-word predictions get node ids out of the bigram table and need the word back.
        val words = listOf("a", "an", "and", "ant", "b", "be", "bee", "don't", "zebra", "zed")
        val built = Trie.build(words.map { it to 5 })
        for (w in words) assertEquals(w, built.wordOf(built.nodeFor(w)))
        assertEquals(-1, built.parentOf(built.root))
    }

    @Test
    fun theRealDictionaryIsSpelledBackToo() {
        val p = listOf(
            java.nio.file.Paths.get("src/main/assets/dictionaries/it_wordlist.txt"),
            java.nio.file.Paths.get("app/src/main/assets/dictionaries/it_wordlist.txt"),
        ).firstOrNull { java.nio.file.Files.exists(it) } ?: return
        val real = java.nio.file.Files.newBufferedReader(p).use { DictionaryLoader.load(it) }.trie
        var checked = 0
        for (line in java.nio.file.Files.readAllLines(p).take(3000)) {
            val w = AccentFolder.fold(line.substringBefore('\t').lowercase())
            val node = real.nodeFor(w)
            if (node < 0) continue
            assertEquals(w, real.wordOf(node))
            checked++
        }
        assertTrue("checked $checked", checked > 2500)
    }
}
