package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.WordCandidate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A word longer than any bundled one, learned twice as typing teaches it, is decoded like any
 * other: from swiped pieces, from taps, and as a completion. The bundled lists stop at 20 letters,
 * so only learning brings such a word in. Real `it` list. A long bundled word swiped in one
 * stroke is not read as a short frequent word that covers half of it (real `en` and `it` lists).
 */
class LongWordTest {

    private val g = TestData.qwertyGeometry()

    @Test
    fun aLearnedLongWordLeadsFromThreeSwipedPieces() {
        val p = predictor(LONG, TAPPED)
        // Pieces of 11 letters, the thumbs taking turns, each drawn with an overshoot at every turn.
        val pieces = LONG.chunked(11)
        val tokens = ArrayList<InputToken>()
        var t = 0L
        for ((i, piece) in pieces.withIndex()) {
            val stream = if (i % 2 == 0) StreamId.LEFT else StreamId.RIGHT
            tokens.add(TestData.sloppySwipe(piece, g, t, 60L * piece.length, 0.25f, stream))
            t += 60L * piece.length + 150
        }
        assertEquals(LONG, p.decode(tokens, emptyList()).firstOrNull()?.word)
    }

    @Test
    fun aLearnedLongWordLeadsFromOneStroke() {
        val p = predictor(LONG, TAPPED)
        for (overshoot in listOf(0f, 0.25f)) {
            val stroke = TestData.sloppySwipe(LONG, g, 0, 60L * LONG.length, overshoot)
            assertEquals("overshoot $overshoot", LONG, p.decode(listOf(stroke), emptyList()).firstOrNull()?.word)
        }
    }

    @Test
    fun aThirteenLetterWordLeadsFromOneStroke() {
        // Bundled, and one letter past the old cap on a single piece.
        val p = predictor()
        val stroke = TestData.sloppySwipe("probabilmente", g, 0, 900, 0.25f)
        assertEquals("probabilmente", p.decode(listOf(stroke), emptyList()).firstOrNull()?.word)
    }

    @Test
    fun aLearnedLongWordLeadsFromItsTaps() {
        val p = predictor(LONG, TAPPED)
        val tokens = TAPPED.mapIndexed { i, ch ->
            val code = g.alphabet.codeOf(ch)
            val stream = if (g.centerX(code) < g.centerX(g.alphabet.codeOf('g'))) StreamId.LEFT else StreamId.RIGHT
            TestData.tap(ch, g, 150L * i, stream)
        }
        assertEquals(TAPPED, p.decode(tokens, emptyList()).firstOrNull()?.word)
    }

    @Test
    fun aLearnedLongWordCompletesFromItsStart() {
        val p = predictor(LONG, TAPPED)
        val tokens = LONG.take(10).mapIndexed { i, ch -> TestData.tap(ch, g, 150L * i) }
        val c = p.decode(tokens, emptyList()).firstOrNull { it.word == LONG }
        assertEquals(WordCandidate.Source.COMPLETION, c?.source)
    }

    @Test
    fun aLongStrokeIsNotReadAsAShortFrequentWord() {
        // Each, at these overshoots, led with a word whose path covers about half the stroke:
        // `incomprehensible` as `inspector`, `approximately` as `somebody`, `development` as
        // `doctor`, `appuntamento` as `andiamo`, `necessariamente` as `mantenere`.
        val cases = listOf(
            Triple("en", "incomprehensible", listOf(0.6f, 0.8f)),
            Triple("en", "approximately", listOf(0.45f, 0.6f, 0.8f)),
            Triple("en", "development", listOf(0.6f, 0.8f)),
            Triple("it", "appuntamento", listOf(0.6f)),
            Triple("it", "necessariamente", listOf(0.45f)),
        )
        for ((lang, word, overshoots) in cases) {
            val p = if (lang == "en") english() else predictor()
            for (o in overshoots) {
                val stroke = TestData.sloppySwipe(word, g, 0, 60L * word.length, o)
                val got = p.decode(listOf(stroke), emptyList()).map { it.word }
                assertEquals("$word at overshoot $o; got ${got.take(5)}", word, got.firstOrNull())
            }
        }
    }

    @Test
    fun aShortReadingIsChargedOnlyOnALongStroke() {
        val gate = KineticaConstants.SHORT_READING_ARC_KW
        val ratio = KineticaConstants.SHORT_READING_RATIO
        // Under the gate nothing is charged, however short the reading.
        assertEquals(1f, KineticaConstants.shortReadingKeep(1f, gate - 0.1f), 0f)
        // At or past the ratio nothing is charged.
        assertEquals(1f, KineticaConstants.shortReadingKeep(ratio * gate, gate), 1e-6f)
        assertEquals(1f, KineticaConstants.shortReadingKeep(2f * gate, gate), 0f)
        // Half the ratio keeps a quarter.
        assertEquals(0.25f, KineticaConstants.shortReadingKeep(ratio * gate / 2f, gate), 1e-4f)
        // A short word on a short stroke keeps its lead.
        val get = TestData.sloppySwipe("get", g, 0, 300, 0.25f)
        assertEquals("get", english().decode(listOf(get), emptyList()).firstOrNull()?.word)
    }

    @Test
    fun aWordPastTheCapStaysOut() {
        val over = "a".repeat(KineticaConstants.MAX_WORD_LEN + 1)
        val t = Trie.build(listOf(LONG to 1, over to 1))
        assertTrue(t.contains(LONG))
        assertFalse(t.contains(over))
    }

    private fun predictor(vararg learned: String): WordPredictor {
        val path = assetPath("it_wordlist.txt")
        assumeTrue("it assets not found", Files.exists(path))
        val counts = learned.map { it to 2 }
        val dict = Files.newBufferedReader(path).use {
            DictionaryLoader.load(it, DictionaryLoader.userWordsForMerge(counts))
        }
        return WordPredictor(
            dict.trie, BigramTable.EMPTY, g, dict.forms, counts.toMap(), language = "it", learnedOnly = dict.learned,
        )
    }

    private fun english(): WordPredictor {
        val path = assetPath("en_wordlist.txt")
        assumeTrue("en assets not found", Files.exists(path))
        val dict = Files.newBufferedReader(path).use { DictionaryLoader.load(it) }
        return WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms, language = "en")
    }

    private fun assetPath(name: String): Path {
        val direct = Paths.get("src/main/assets/dictionaries/$name")
        if (Files.exists(direct)) return direct
        return Paths.get("app/src/main/assets/dictionaries/$name")
    }

    private companion object {
        const val LONG = "supercalifragilistichespiralidoso"
        const val TAPPED = "precipitevolissimevolmente"
    }
}
