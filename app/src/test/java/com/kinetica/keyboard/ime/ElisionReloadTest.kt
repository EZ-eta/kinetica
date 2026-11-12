package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.Alphabet
import com.kinetica.keyboard.engine.BigramTable
import com.kinetica.keyboard.engine.DictionaryLoader
import com.kinetica.keyboard.engine.TestData
import com.kinetica.keyboard.engine.WordPredictor
import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.TapToken
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A word reopened after an apostrophe comes back as tapping wrote it: the piece after the
 * apostrophe, unless the whole run is a word or a tapped apostrophe joined it (`i'`).
 *
 * An elision's apostrophe ends the word, so `l'altro` is typed as `l` and `altro`. A reload that took
 * the whole run back would seed its letters without the apostrophe, commit `l'altro` as one new
 * word and offer `laltro` as the letters typed.
 */
class ElisionReloadTest {

    private fun assetPath(name: String): Path {
        val direct = Paths.get("src/main/assets/dictionaries/$name")
        if (Files.exists(direct)) return direct
        return Paths.get("app/src/main/assets/dictionaries/$name")
    }

    private fun italian(): WordPredictor {
        val p = assetPath("it_wordlist.txt")
        assumeTrue("it_wordlist asset not found", Files.exists(p))
        val dict = Files.newBufferedReader(p).use { DictionaryLoader.load(it) }
        return WordPredictor(dict.trie, BigramTable.EMPTY, TestData.qwertyGeometry(), dict.forms, language = "it")
    }

    private fun english(): WordPredictor {
        val p = assetPath("en_wordlist.txt")
        assumeTrue("en_wordlist asset not found", Files.exists(p))
        val dict = Files.newBufferedReader(p).use { DictionaryLoader.load(it) }
        return WordPredictor(dict.trie, BigramTable.EMPTY, TestData.qwertyGeometry(), dict.forms, language = "en")
    }

    private fun literalOf(tokens: List<InputToken>, p: WordPredictor): String =
        tokens.sortedBy { it.tStart }.joinToString("") { p.alphabet.charOf((it as TapToken).code).toString() }

    @Test
    fun anElisionReopensAfterItsApostrophe() {
        val none: (String) -> Boolean = { false }
        assertEquals("al", reloadFragment("l'al", none))
        assertEquals("ann", reloadFragment("dell'ann", none))
        assertEquals("altro", reloadFragment("l'altro", none))
        // Nothing after the apostrophe: the next letter starts a word, as typed.
        assertEquals("", reloadFragment("l'", none))
        assertEquals("hello", reloadFragment("hello", none))
    }

    @Test
    fun aRunThatIsAWordReopensWhole() {
        // English contractions are words, so `don't` and `it's` come back as before.
        val english = setOf("don't", "it's")
        assertEquals("don't", reloadFragment("don't") { it in english })
        assertEquals("it's", reloadFragment("it's") { it in english })
        // An elision the user has taught is a word too.
        assertEquals("l'altro", reloadFragment("l'altro") { it == "l'altro" })
    }

    @Test
    fun aReopenedElisionCommitsOnlyItsTail() {
        val p = italian()
        val g = TestData.qwertyGeometry()
        // The pause after `l'al` lets the space fire on `al`; `t` takes it back and reopens.
        val run = trailingLetterRun("vediamo se l'al")
        assertEquals("l'al", run)
        val fragment = reloadFragment(run, p::isWordIgnoringCase)
        assertEquals("al", fragment)
        val seeded = tapAnchors(fragment, g, 10_000L) ?: error("no anchors for $fragment")
        val more = "tro".mapIndexed { i, c -> TestData.tap(c, g, 10_100L + 100L * i, StreamId.LEFT) }
        val tokens = seeded + more
        val literal = literalOf(tokens, p)
        val written = fragment + "tro"
        // The letters typed are the word written, so no `laltro` is offered back.
        assertEquals("altro", literal)
        assertEquals(literal, written)
        assertEquals("altro", p.decode(tokens, listOf("se")).first().word)
        // Learned once as `altro`, as the tapped path learns it; `l'altro` is never a new word.
        assertTrue(learnsOnCommit(written, fragment))
        assertFalse(written.contains('\''))
    }

    @Test
    fun anElisionOfferedMidWordDecodesItsPiece() {
        // The cursor parked in `l'al|tro`: the offer seeds `altro`, not `laltro`, and follows `l`.
        val p = italian()
        val g = TestData.qwertyGeometry()
        val before = "vediamo se l'al"
        val around = wordAroundCursor(before, "tro e poi", 24) ?: error("no word around the cursor")
        val piece = midWordPiece(around, p::isWordIgnoringCase) ?: error("no piece offered")
        assertEquals(WordAround("al", "tro"), piece)
        val taps = tapAnchors(piece.head + piece.tail, g, 10_000L) ?: error("no anchors")
        assertEquals("altro", literalOf(taps, p))
        val context = listOfNotNull(wordBefore(before, piece.head))
        assertEquals(listOf("l"), context)
        assertEquals("altro", p.decode(taps, context).first().word)
    }

    @Test
    fun aJoinedRunReopensWhole() {
        // A tapped `'` the join took stays in the word: a backspace from `i'd` leaves `i'` to type on.
        val none: (String) -> Boolean = { false }
        val joined: (String) -> Boolean = { it.lowercase() in setOf("i", "don", "y") }
        assertEquals("i'", reloadFragment("i'", none, joined))
        assertEquals("I'", reloadFragment("I'", none, joined))
        assertEquals("don'", reloadFragment("don'", none, joined))
        assertEquals("y'al", reloadFragment("y'al", none, joined))
        // An elision never joined: cut as before.
        assertEquals("al", reloadFragment("l'al", none, joined))
        assertEquals("ann", reloadFragment("dell'ann", none, joined))
        assertEquals("", reloadFragment("l'", none, joined))
        // Each apostrophe as it was typed: the second could not join `i'd`.
        assertEquals("ve", reloadFragment("i'd've", none, joined))
        // A `’` never joins.
        assertEquals("d", reloadFragment("i\u2019d", none, joined))
    }

    @Test
    fun aBackspaceIntoAJoinedContractionTypesOn() {
        val en = english()
        val residents = listOf("it" to italian(), "en" to en)
        val joins: (String) -> Boolean = { apostropheJoins(it, residents, Alphabet.LATIN, elidingOn = true) }
        val isWord: (String) -> Boolean = { w -> residents.any { it.second.isWordIgnoringCase(w) } }
        val g = TestData.qwertyGeometry()
        // `I'd`, one backspace: `I'` comes back whole and `m` makes `I'm`.
        val fragment = reloadFragment(reloadRun("ok I'"), isWord, joins)
        assertEquals("I'", fragment)
        val tokens = tapAnchors(fragment, g, 10_000L)!! + TestData.tap('m', g, 10_100L, StreamId.RIGHT)
        assertEquals("i'm", en.decode(tokens, listOf("ok")).first().word)
        // The Italian elision beside it still reopens after its apostrophe.
        assertEquals("al", reloadFragment(reloadRun("vediamo se l'al"), isWord, joins))
    }

    @Test
    fun aSmartQuotedContractionReopensWhole() {
        val en = english()
        assertEquals("I\u2019d", reloadRun("ok I\u2019d"))
        assertEquals("don\u2019t", reloadRun("I don\u2019t"))
        assertEquals("I\u2019d", reloadFragment(reloadRun("ok I\u2019d"), en::isWordIgnoringCase))
        // A quote mark with no letter on one side still ends the run.
        assertEquals("", reloadRun("the \u2018best\u2019"))
        assertEquals("n", reloadRun("rock \u2019n"))
        assertEquals("altro", reloadFragment(reloadRun("se l\u2019altro"), italian()::isWordIgnoringCase))
        // Letters and the straight apostrophe as trailingLetterRun reads them.
        assertEquals(trailingLetterRun("vediamo se l'al"), reloadRun("vediamo se l'al"))
        // Its anchors keep both readings on the bar, as a reopened `I'd` does.
        val words = en.decode(tapAnchors("I\u2019d", TestData.qwertyGeometry(), 10_000L)!!, listOf("ok")).map { it.word }
        assertEquals("i'd", words.first())
        assertTrue("id" in words)
    }

    @Test
    fun theReloadAsksTheJoinOnTheRunWithItsQuotes() {
        // The service has no JVM reach: the reload, the offer and its pick cut alike.
        val src = ImeSources.declaring("fun reloadWordUnderCursor(").text
        assertTrue(src.contains("reloadFragment(reloadRun(before), ::isResidentWord, ime::joinsAtApostrophe)"))
        assertTrue(src.contains("midWordPiece(around, ::isResidentWord, ime::joinsAtApostrophe)"))
        assertTrue(src.contains("midWordStillThere(before, after, offer, ::isResidentWord, ime::joinsAtApostrophe)"))
    }
}
