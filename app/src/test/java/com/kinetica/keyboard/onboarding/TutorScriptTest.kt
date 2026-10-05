package com.kinetica.keyboard.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tutor's split of a word between the thumbs, on QWERTY's halves. */
class TutorScriptTest {

    private val left = "qwertasdfzxc".toSet()
    private fun isLeft(c: Char) = c in left

    @Test
    fun eachThumbTakesTheRunOfLettersOnItsHalf() {
        assertEquals(
            listOf(TutorScript.Stroke(true, "strea"), TutorScript.Stroke(false, "m")),
            TutorScript.strokes("stream", true, ::isLeft),
        )
        assertEquals(listOf("minu", "te"), TutorScript.strokes("minute", true, ::isLeft).map { it.letters })
        assertEquals(listOf("k", "e", "ybo", "ard"), TutorScript.strokes("keyboard", true, ::isLeft).map { it.letters })
        assertTrue(TutorScript.strokes("keyboard", true, ::isLeft)[0].isTap)
    }

    @Test
    fun oneThumbSwipesTheWholeWord() {
        assertEquals(listOf("hello"), TutorScript.strokes("hello", false, ::isLeft).map { it.letters })
        assertEquals(emptyList<TutorScript.Stroke>(), TutorScript.strokes("", true, ::isLeft))
    }

    @Test
    fun noLessonWordHasALetterOnTheMidline() {
        for (l in TutorScript.LESSONS) {
            assertFalse(l.word, l.word.any { it == 'g' || it == 'v' })
        }
    }

    @Test
    fun aLessonIsDoneWhenItsWordIsTheLastOneTyped() {
        assertTrue(TutorScript.done("Hello ", "hello"))
        assertTrue(TutorScript.done("some text hello.", "hello"))
        assertFalse(TutorScript.done("hell", "hello"))
        assertFalse(TutorScript.done("", "hello"))
    }
}
