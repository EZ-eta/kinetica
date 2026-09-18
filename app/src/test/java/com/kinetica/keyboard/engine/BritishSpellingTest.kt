package com.kinetica.keyboard.engine

import java.io.BufferedReader
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * British spelling is a re-rank, not a language: both spellings of every pair
 * are already in the English wordlist with the American form the more frequent,
 * so the setting exchanges the two counts while the trie is built. These tests
 * pin the three properties that makes it safe - the swap is exact, it is
 * direction-guarded, and it leaves the language's total frequency mass alone.
 */
class BritishSpellingTest {

    private fun reader(s: String): BufferedReader = BufferedReader(StringReader(s))

    private fun assetPath(name: String): Path {
        val direct = Paths.get("src/main/assets/dictionaries/$name")
        if (Files.exists(direct)) return direct
        return Paths.get("app/src/main/assets/dictionaries/$name")
    }

    @Test
    fun swapExchangesTheTwoCountsExactly() {
        val list = "color\t32837\ncolour\t11281\nthe\t100000\n"
        val plain = DictionaryLoader.load(reader(list))
        val swapped = DictionaryLoader.load(
            reader(list), spellingSwaps = mapOf("color" to "colour"),
        )
        // Different words, so different trie nodes: the counts move, not the words.
        val plainColor = plain.trie.frequency(plain.trie.nodeFor("color"))
        val plainColour = plain.trie.frequency(plain.trie.nodeFor("colour"))
        val newColor = swapped.trie.frequency(swapped.trie.nodeFor("color"))
        val newColour = swapped.trie.frequency(swapped.trie.nodeFor("colour"))
        assertNotEquals("the fixture must not be symmetric", plainColor.toLong(), plainColour.toLong())
        assertEquals("colour did not take color's frequency", plainColor.toLong(), newColour.toLong())
        assertEquals("color did not take colour's frequency", plainColour.toLong(), newColor.toLong())
    }

    @Test
    fun swapIsSkippedWhenThePreferredSpellingIsAlreadyAhead() {
        // Four of the generator's candidate pairs are already the other way
        // round ("dialogue" 5 975 against "dialog" 399). Swapping those would
        // demote the British spelling, so the loader declines.
        val list = "dialog\t399\ndialogue\t5975\n"
        val plain = DictionaryLoader.load(reader(list))
        val swapped = DictionaryLoader.load(
            reader(list), spellingSwaps = mapOf("dialog" to "dialogue"),
        )
        for (w in listOf("dialog", "dialogue")) {
            assertEquals(
                "$w moved despite the preferred spelling already leading",
                plain.trie.frequency(plain.trie.nodeFor(w)).toLong(),
                swapped.trie.frequency(swapped.trie.nodeFor(w)).toLong(),
            )
        }
    }

    @Test
    fun aPairWithOnlyOneSpellingPresentIsLeftAlone() {
        val list = "colour\t11281\nthe\t100000\n"
        val dict = DictionaryLoader.load(
            reader(list), spellingSwaps = mapOf("color" to "colour"),
        )
        assertEquals(2, dict.trie.wordCount.toLong().toInt())
        assertTrue(dict.trie.contains("colour"))
    }

    @Test
    fun parserSkipsMalformedLinesRatherThanFailing() {
        val swaps = DictionaryLoader.loadSpellingSwaps(
            reader("color\tcolour\nno tab here\n\tleading\ntrailing\t\nsame\tsame\ncenter\tcentre\n"),
        )
        assertEquals(mapOf("color" to "colour", "center" to "centre"), swaps)
    }

    @Test
    fun theBundledPairListIsWellFormedAndOneDirectional() {
        val p = assetPath("en_gb_variants.txt")
        assumeTrue("en_gb_variants asset not found", Files.exists(p))
        val swaps = Files.newBufferedReader(p).use { DictionaryLoader.loadSpellingSwaps(it) }
        assertTrue("pair list is empty", swaps.size > 100)
        // No chains: a preferred spelling must never itself be a key, or the
        // order the map is walked in would decide the outcome.
        for (to in swaps.values) {
            assertTrue("$to is both a source and a target", to !in swaps.keys)
        }
        // Every pair must be present in the shipped wordlist with the American
        // form ahead, which is the generator's own contract.
        val wl = assetPath("en_wordlist.txt")
        assumeTrue("en wordlist asset not found", Files.exists(wl))
        val counts = HashMap<String, Int>(50_000)
        Files.newBufferedReader(wl).use { r ->
            r.forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) {
                    line.substring(tab + 1).trim().toIntOrNull()
                        ?.let { counts[line.substring(0, tab)] = it }
                }
            }
        }
        for ((from, to) in swaps) {
            val a = counts[from]
            val b = counts[to]
            assertTrue("$from missing from the wordlist", a != null)
            assertTrue("$to missing from the wordlist", b != null)
            assertTrue("$from ($a) is not ahead of $to ($b)", a!! > b!!)
        }
    }

    @Test
    fun theBundledListReRanksRealEnglishSuggestions() {
        // The point of the feature, on the real asset rather than a fixture.
        val wl = assetPath("en_wordlist.txt")
        val pl = assetPath("en_gb_variants.txt")
        assumeTrue("en assets not found", Files.exists(wl) && Files.exists(pl))
        val swaps = Files.newBufferedReader(pl).use { DictionaryLoader.loadSpellingSwaps(it) }
        val plain = Files.newBufferedReader(wl).use { DictionaryLoader.load(it) }
        val british = Files.newBufferedReader(wl).use {
            DictionaryLoader.load(it, spellingSwaps = swaps)
        }
        for ((us, gb) in listOf("color" to "colour", "center" to "centre",
                                "realize" to "realise", "favorite" to "favourite")) {
            val usPlain = plain.trie.frequency(plain.trie.nodeFor(us))
            val gbPlain = plain.trie.frequency(plain.trie.nodeFor(gb))
            val usNew = british.trie.frequency(british.trie.nodeFor(us))
            val gbNew = british.trie.frequency(british.trie.nodeFor(gb))
            assertTrue("$us should start ahead of $gb", usPlain > gbPlain)
            assertTrue("$gb should end ahead of $us", gbNew > usNew)
        }
        // Nothing added or removed: a re-rank must not change the vocabulary.
        assertEquals(plain.trie.wordCount.toLong(), british.trie.wordCount.toLong())
    }
}
