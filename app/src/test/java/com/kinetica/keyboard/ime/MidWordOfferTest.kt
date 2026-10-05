package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.Alphabet
import com.kinetica.keyboard.engine.TestData
import com.kinetica.keyboard.engine.models.TapToken
import com.kinetica.keyboard.keys.ShiftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A word changed with the cursor in its middle, the way re-casing does.
 * The bar and the editor have no JVM reach; these are the rules under them.
 */
class MidWordOfferTest {

    private val g = TestData.qwertyGeometry()

    @Test
    fun theWholeWordIsOfferedApostropheIncluded() {
        // The end-of-word reload reopened `don` out of `don|'t`: its guard asks for a letter
        // after the cursor, and the apostrophe is none.
        assertTrue(cursorInsideWord("I don", "'t know"))
        assertEquals(WordAround("don", "'t"), wordAroundCursor("I don", "'t know", 24))
        assertEquals(WordAround("fo", "rm"), wordAroundCursor("the fo", "rm is", 24))
        assertFalse("at a word's end it is the reload's case", cursorInsideWord("the form", " is"))
    }

    @Test
    fun theWordIsSeededAsItsOwnLettersInOrder() {
        val taps = tapAnchors("don't", g, beforeTime = 10_000)!!.map { it as TapToken }
        assertEquals("dont", taps.joinToString("") { Alphabet.LATIN.charOf(it.code).toString() })
        assertTrue(taps.zipWithNext().all { (a, b) -> a.tStart < b.tStart })
        assertTrue("anchors end before the touch that follows", taps.last().tEnd < 10_000)
        // An accent folds onto its key.
        assertEquals("perche", tapAnchors("perché", g, 0)!!.joinToString("") { Alphabet.LATIN.charOf((it as TapToken).code).toString() })
    }

    @Test
    fun aWordThisBoardCannotSpellIsNotSeeded() {
        assertNull(tapAnchors("привіт", g, 0))
        assertNull(tapAnchors("'", g, 0))
    }

    @Test
    fun aPickIsRefusedOnceTheTextAroundTheCursorChanged() {
        val offer = WordAround("fo", "rm")
        assertTrue(midWordStillThere("the fo", "rm is", offer))
        // A letter typed, the cursor moved one, the word rewritten by the app.
        assertFalse(midWordStillThere("the fox", "rm is", offer))
        assertFalse(midWordStillThere("the f", "orm is", offer))
        assertFalse(midWordStillThere("the fo", "r is", offer))
        assertFalse(midWordStillThere("the ", "form", offer))
    }

    @Test
    fun theAlternativesAreShownInTheWordsOwnCase() {
        assertEquals(ShiftState.State.SHIFT, shiftOf("Form"))
        assertEquals(ShiftState.State.CAPS_LOCK, shiftOf("FORM"))
        assertEquals(ShiftState.State.NONE, shiftOf("form"))
        assertEquals(ShiftState.State.SHIFT, shiftOf("A"))
    }

    @Test
    fun aWordWrittenHereOffersWhatItsBarHad() {
        // Captured: `key` re-decoded from its letters gave only completions; its swipe had more.
        val history = CommitHistory.Record(5, "key", listOf("let", "meet", "met", "jet"), emptyMap())
        val offer = WordAround("ke", "y")
        assertEquals(
            listOf("key", "let", "meet", "met", "jet", "keys", "keyboard"),
            midWordWords(listOf("key", "keys", "keyboard"), history, offer),
        )
        assertEquals(listOf("key", "keys"), midWordWords(listOf("key", "keys"), null, offer))
    }

    @Test
    fun theOfferFollowsTheWordBeforeItNotItself() {
        // The captured decode of `key` had ctx=[plus, key].
        assertEquals("plus", wordBefore("so plus ke", "ke"))
        assertEquals("plus", wordBefore("plus, ke", "ke"))
        assertEquals("l'anno", wordBefore("l'anno ke", "ke"))
        assertEquals(null, wordBefore("ke", "ke"))
    }
}
