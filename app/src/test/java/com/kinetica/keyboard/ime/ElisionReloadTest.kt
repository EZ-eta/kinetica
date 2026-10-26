package com.kinetica.keyboard.ime

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
 * apostrophe, unless the whole run is a word.
 *
 * The apostrophe key ends a word, so `l'altro` is typed as `l` and `altro`. A reload that took
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
}
