package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.WordPredictor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which word the idle bar predicts from, and how two languages' predictions share it. */
class NextWordBarTest {

    @Test
    fun aFinishedWordAndItsSpaceArePredictedFrom() {
        assertEquals("think", previousWordForPrediction("I think "))
        assertEquals("think", previousWordForPrediction("I think  "))
        assertEquals("don't", previousWordForPrediction("I don't "))
        assertEquals("perché", previousWordForPrediction("non so perché "))
        assertEquals("hello", previousWordForPrediction("hello "))
        assertEquals("quote", previousWordForPrediction("a \"quote "))
    }

    @Test
    fun nothingIsPredictedMidWordOrAfterPunctuation() {
        assertNull("mid-word, the user is still writing it", previousWordForPrediction("I think"))
        assertNull("a sentence end has no pair across it", previousWordForPrediction("Done. "))
        assertNull(previousWordForPrediction("ok, "))
        assertNull(previousWordForPrediction("really? "))
        assertNull("the last piece of an address is not a word", previousWordForPrediction("name@mail.com "))
        assertNull(previousWordForPrediction("a-b "))
        assertNull(previousWordForPrediction(" "))
        assertNull(previousWordForPrediction(""))
        assertNull(previousWordForPrediction(null))
        assertNull("a new line is a new start", previousWordForPrediction("line\n"))
    }

    private fun nw(word: String, score: Float, lang: String) = WordPredictor.NextWord(word, score, lang)

    @Test
    fun twoLanguagesShareTheBarStrongestFirstWithOneEntryPerWord() {
        val it = listOf(nw("che", 1.9f, "it"), nw("non", 1.5f, "it"))
        val en = listOf(nw("the", 1.8f, "en"), nw("non", 1.7f, "en"))
        assertEquals(listOf("che", "the", "non"), mergeNextWords(it, en, 10).map { it.word })
        assertEquals("the stronger reading of a shared word wins", "en", mergeNextWords(it, en, 10)[2].language)
        assertEquals(listOf("che", "the"), mergeNextWords(it, en, 2).map { it.word })
    }
}
