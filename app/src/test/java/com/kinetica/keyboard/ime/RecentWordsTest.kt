package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The recent-words bar's memory, and how it finds its words again in the editor. */
class RecentWordsTest {

    @Test
    fun theWordsAreFoundNewestFirstAsDistancesToTheCursor() {
        assertEquals(listOf(3, 8, 13), alignRecent("I have seen it ", 0, listOf("have", "seen", "it")))
    }

    @Test
    fun theWordBeingTypedIsSkipped() {
        // `wor` is the word in progress, committed letters in this keyboard's text model.
        assertEquals(listOf(7, 13), alignRecent("hello the wor", 3, listOf("hello", "the")))
    }

    @Test
    fun punctuationBetweenWordsIsAllowed() {
        assertEquals(listOf(8, 14), alignRecent("fine, thanks! ", 0, listOf("fine", "thanks")))
    }

    @Test
    fun theWalkStopsWhereTheEditorNoLongerAgrees() {
        // The user deleted `seen` and wrote `saw`.
        assertEquals(listOf(3), alignRecent("I have saw it ", 0, listOf("have", "seen", "it")))
        // A word inside a longer one is not the word.
        assertEquals(emptyList<Int>(), alignRecent("unseen ", 0, listOf("seen")))
        assertEquals(emptyList<Int>(), alignRecent("seenit", 2, listOf("seen")))
        assertEquals(emptyList<Int>(), alignRecent("", 0, listOf("seen")))
        assertEquals(emptyList<Int>(), alignRecent("it", 5, listOf("it")))
    }

    @Test
    fun aSwapRewritesFromTheWordToTheCursorAndKeepsTheRest() {
        val before = "I have seen it "
        val span = alignRecent(before, 0, listOf("have", "seen", "it"))[1]
        assertEquals("saw it ", recentRewrite(before, span, "seen".length, "saw"))
        // With `wor` being typed, the pending letters are part of the tail and come back.
        val typing = "hello the wor"
        val s = alignRecent(typing, 3, listOf("hello", "the"))[0]
        assertEquals("then wor", recentRewrite(typing, s, "the".length, "then"))
        assertEquals("hello then wor", typing.substring(0, typing.length - s) + recentRewrite(typing, s, 3, "then"))
    }

    @Test
    fun caseIsMatchedNotCompared() {
        assertEquals(listOf(4), alignRecent("The ", 0, listOf("the")))
        assertEquals("There", matchCase("The", "there"))
        assertEquals("THERE", matchCase("THE", "there"))
        assertEquals("there", matchCase("the", "there"))
        assertEquals("I", matchCase("I", "i"))
    }

    @Test
    fun aReplacedWordLeadsItsOwnAlternativesSoItCanComeBack() {
        val r = RecentWords(3)
        r.onCommit("world", listOf("word", "worked", "World"), emptyMap())
        assertEquals(listOf("word", "worked"), r.entries().single().alternatives)
        r.onReplaced(0, "word")
        assertEquals("word", r.entries().single().word)
        assertEquals(listOf("world", "worked"), r.entries().single().alternatives)
    }

    @Test
    fun onlyTheLastFewAreKeptAndARetypeTakesTheNewestBack() {
        val r = RecentWords(2)
        r.onCommit("a", emptyList(), emptyMap())
        r.onCommit("b", emptyList(), emptyMap())
        r.onCommit("c", emptyList(), emptyMap())
        assertEquals(listOf("b", "c"), r.entries().map { it.word })
        r.dropNewest()
        assertEquals(listOf("b"), r.entries().map { it.word })
        r.onReplaced(5, "x")
        assertEquals(listOf("b"), r.entries().map { it.word })
    }

    @Test
    fun aSwapMovesTheLearnedPairsAroundTheWord() {
        assertEquals(
            listOf(PairMove("hello", "word", -1), PairMove("hello", "world", 1), PairMove("word", "peace", -1), PairMove("world", "peace", 1)),
            recentPairMoves("hello", "word", "world", "peace"),
        )
        assertEquals(listOf(PairMove("word", "is", -1), PairMove("world", "is", 1)), recentPairMoves(null, "word", "world", "is"))
        assertEquals(emptyList<PairMove>(), recentPairMoves("a", "Word", "word", "b"))
    }

    @Test
    fun aColumnOffersTheWordsOwnLanguageFirst() {
        val langs = mapOf("word" to "en", "world" to "en", "mondo" to "it", "parola" to "it")
        assertEquals(listOf("world"), sameLanguage(listOf("mondo", "world", "parola"), langs, "word", "it"))
        // A word the map does not name takes the active language.
        assertEquals(listOf("mondo", "parola"), sameLanguage(listOf("mondo", "world", "parola"), langs, "casa", "it"))
        // None share it: all of them, not an empty column.
        assertEquals(listOf("mondo"), sameLanguage(listOf("mondo"), langs, "word", "it"))
    }

    @Test
    fun aSwapTakesTheOldPairsBackInTheOldWordsLanguage() {
        // All four moves were filed under the new word's language, so an Italian pair
        // taken back from the English store did nothing and the Italian one stayed.
        val moves = recentPairMoves("ciao", "word", "world", "peace")
        val filed = moves.map { it to pairMoveLanguage(it, oldLang = "it", newLang = "en") }
        assertEquals(listOf("it", "en", "it", "en"), filed.map { it.second })
        assertEquals(listOf(-1, 1, -1, 1), filed.map { it.first.delta })
    }

    @Test
    fun aDecrementReachesTheSpellingThatHoldsTheCount() {
        // Learned once as `perché`, once as `perche`: one folded key with two rows. Taking two
        // back takes the larger row first, then the other, and never a pair with another key.
        val rows = listOf(
            StoredPair("perché", "no", 1),
            StoredPair("perche", "no", 3),
            StoredPair("perche", "si", 5),
        )
        val key = com.kinetica.keyboard.engine.WordPredictor.pairKey("perché", "no")
        assertEquals(listOf(StoredPair("perche", "no", 3) to 2), pickPairRows(rows, key, 2))
        assertEquals(
            listOf(StoredPair("perche", "no", 3) to 3, StoredPair("perché", "no", 1) to 1),
            pickPairRows(rows, key, 9),
        )
        assertEquals(emptyList<Pair<StoredPair, Int>>(), pickPairRows(rows, com.kinetica.keyboard.engine.WordPredictor.pairKey("a", "b"), 1))
    }

    @Test
    fun aRecentWordIsAdjustedInTheLanguageItWasDecodedIn() {
        val r = RecentWords(4)
        r.onCommit("perché", listOf("perche"), mapOf("perché" to "it"))
        r.onCommit("world", listOf("word"), mapOf("world" to "en", "word" to "en"))
        assertEquals("it", r.languageOf("Perché"))
        assertEquals("en", r.languageOf("word"))
        assertEquals(null, r.languageOf("ciao"))
    }

    @Test
    fun aDemotedMergedWordReloadsTheTrie() {
        // Without a reload its merged trie frequency keeps the old count, so a slide down
        // barely moves it.
        assertTrue(userDictDemoted(300, 150))
        assertFalse(userDictDemoted(300, 300))
        assertFalse("never merged, nothing stale", userDictDemoted(1, 0))
    }
}
