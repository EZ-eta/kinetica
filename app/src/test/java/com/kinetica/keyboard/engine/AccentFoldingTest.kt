package com.kinetica.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

/** Accented dictionaries: folded trie keys with per-node display variants. */
class AccentFoldingTest {

    private val g = TestData.qwertyGeometry()

    private fun dict(vararg rows: Pair<String, Int>): LoadedDictionary {
        val text = rows.joinToString("\n") { "${it.first}\t${it.second}" }
        return DictionaryLoader.load(BufferedReader(StringReader(text)))
    }

    @Test
    fun foldMapsAccentsAndSharpS() {
        assertEquals("perche", AccentFolder.fold("perché"))
        assertEquals("citta", AccentFolder.fold("città"))
        assertEquals("strasse", AccentFolder.fold("straße"))
        val plain = "already plain"
        assertTrue(plain === AccentFolder.fold(plain))
    }

    @Test
    fun accentedWordDecodesFromBaseKeyTaps() {
        val d = dict("perché" to 9000, "il" to 20000)
        val p = WordPredictor(d.trie, BigramTable.EMPTY, g, d.forms)
        val tokens = "perche".mapIndexed { i, c -> TestData.tap(c, g, i * 100L) }
        val result = p.decode(tokens, emptyList())
        assertEquals("perché", result[0].word)
    }

    @Test
    fun collidingVariantsBothSurface() {
        val d = dict("e" to 100000, "è" to 90000)
        val p = WordPredictor(d.trie, BigramTable.EMPTY, g, d.forms)
        val result = p.decode(listOf(TestData.tap('e', g, 0)), emptyList())
        val words = result.map { it.word }
        assertTrue("expected both variants in $words", words.containsAll(listOf("e", "è")))
        // Higher raw count must win the ranking.
        assertEquals("e", result[0].word)
    }

    @Test
    fun isWordDistinguishesVariantsFromFoldedKeys() {
        val d = dict("perché" to 9000, "e" to 100000, "è" to 90000, "casa" to 5000)
        val p = WordPredictor(d.trie, BigramTable.EMPTY, g, d.forms)
        assertTrue(p.isWord("perché"))
        assertFalse("folded spelling is not a word", p.isWord("perche"))
        assertTrue(p.isWord("e"))
        assertTrue(p.isWord("è"))
        assertTrue(p.isWord("casa"))
    }

    @Test
    fun autocorrectRestoresAccent() {
        val d = dict("perché" to 9000, "casa" to 5000)
        val p = WordPredictor(d.trie, BigramTable.EMPTY, g, d.forms)
        val tokens = "perche".mapIndexed { i, c -> TestData.tap(c, g, i * 100L) }
        val candidates = p.decode(tokens, emptyList())
        val target = p.autocorrectTarget("perche", candidates, 0.85f)
        assertEquals("perché", target?.word)
    }

    @Test
    fun accentedLetterCodeAcceptsOnlyRealAccents() {
        // What KineticaIME composes into the word instead of committing it
        // An accent, in either case:
        assertEquals('o' - 'a', AccentFolder.accentedLetterCode("ó"))
        assertEquals('a' - 'a', AccentFolder.accentedLetterCode("à"))
        assertEquals('n' - 'a', AccentFolder.accentedLetterCode("ñ"))
        assertEquals('c' - 'a', AccentFolder.accentedLetterCode("Ç"))
        assertEquals('e' - 'a', AccentFolder.accentedLetterCode("É"))
        // The popup's own base cell is a letter but not an accented one: it keeps
        // the shipped commit-then-insert path, deliberately.
        assertEquals(-1, AccentFolder.accentedLetterCode("o"))
        assertEquals(-1, AccentFolder.accentedLetterCode("O"))
        // Everything else a popup, an edge swipe or the enter strip can insert.
        for (t in listOf("", "1", "?", ",", ";", "€", "ß", "ss", "óó", "😀")) {
            assertEquals("'$t' must not compose", -1, AccentFolder.accentedLetterCode(t))
        }
    }

    @Test
    fun englishDictionaryHasNoForms() {
        val d = dict("the" to 12000, "then" to 5000, "don't" to 2200)
        assertTrue(d.forms.isEmpty())
    }
}
